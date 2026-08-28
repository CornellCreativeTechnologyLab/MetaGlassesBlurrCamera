package com.ctl.metaglasses.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

enum class BlurMode { OFF, ALL_FACES, MINORS_ONLY }

/**
 * Real-time face blurring for camera frames.
 *
 * Detects faces with ML Kit (on-device, bundled model) and pixelates them per [BlurMode].
 * In [BlurMode.MINORS_ONLY], each face is gated by [ageClassifier]; when no age model is
 * loaded the classifier fails safe (blurs the face), so children are never missed.
 */
class FaceBlurProcessor(private val ageClassifier: AgeClassifier) {

    private companion object {
        const val TAG = "FaceBlurProcessor"
        const val FACE_PADDING = 0.25f // grow the face box so hair/jaw/ears are covered too
        const val LIVE_MIN_FACE_SHORT_SIDE = 96
        const val TRACK_KEEP_BLUR_MS = 2_000L
        const val ADULT_CONFIRM_OBSERVATIONS = 5
        const val ADULT_CONFIRM_WINDOW_MS = 1_000L
        const val TRACK_MATCH_IOU = 0.25f
        const val SMALL_FACE_MIN_SIZE = 0.01f
        const val ALL_FACE_KEEP_BLUR_MS = 700L
        const val ALL_FACE_MATCH_IOU = 0.12f
    }

    private data class AgeTrack(
        var box: Rect,
        var lastSeenAt: Long,
        var lastUnsafeAt: Long,
        val adultObservations: ArrayDeque<Long> = ArrayDeque(),
    )

    private data class FaceHold(
        var box: Rect,
        var lastSeenAt: Long,
    )

    private val ageTracks = ArrayList<AgeTrack>()
    private val allFaceTracks = ArrayList<FaceHold>()

    private val fastBoxDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(SMALL_FACE_MIN_SIZE)
            .build()
    )

    private val accurateBoxDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(SMALL_FACE_MIN_SIZE)
            .build()
    )

    private val contourDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            // Contours give the real face outline so the blur follows the face, not a square.
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
    )

    val ageModelLoaded: Boolean
        get() = ageClassifier.isLoaded

    /**
     * Detects faces in [bitmap] (which must be mutable) and pixelates them per [mode].
     * Mutates [bitmap] in place and returns it. Blocking — call off the main thread.
     */
    fun process(bitmap: Bitmap, mode: BlurMode): Bitmap {
        if (mode == BlurMode.OFF) return bitmap
        val regions = facesToBlur(bitmap, mode)
        if (regions.isNotEmpty()) blurRegions(bitmap, regions)
        return bitmap
    }

    /**
     * Detects faces in [bitmap] and returns the regions that should be blurred under [mode] (all
     * faces for [BlurMode.ALL_FACES]; only likely-minors for [BlurMode.MINORS_ONLY]). Each region
     * carries a padded bounding box plus the ML Kit face-outline contour (when available), both in
     * [bitmap] pixel space. Blocking — call off the main thread. Used both to blur the live preview
     * and to record the face map at capture time so blurring stays non-destructive.
     */
    fun facesToBlur(bitmap: Bitmap, mode: BlurMode): List<FaceRegion> {
        if (mode == BlurMode.OFF) return emptyList()

        if (mode == BlurMode.ALL_FACES) {
            return allFacesFast(bitmap)
        }

        val faces = try {
            Tasks.await(contourDetector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed: ${e.message}")
            return emptyList()
        }
        if (faces.isEmpty()) return emptyList()

        val result = ArrayList<FaceRegion>(faces.size)
        val now = SystemClock.elapsedRealtime()
        for (face in faces) {
            val box = clampToBitmap(face.boundingBox, bitmap.width, bitmap.height) ?: continue
            val shouldBlur = when (mode) {
                BlurMode.ALL_FACES -> true
                BlurMode.MINORS_ONLY -> shouldBlurForMinors(bitmap, box, now)
                BlurMode.OFF -> false
            }
            if (shouldBlur) result.add(FaceRegion(box, contourPoints(face)))
        }
        ageTracks.removeAll { now - it.lastSeenAt > TRACK_KEEP_BLUR_MS }
        return result
    }

    private fun allFacesFast(bitmap: Bitmap): List<FaceRegion> {
        val now = SystemClock.elapsedRealtime()
        val fastFaces = try {
            Tasks.await(fastBoxDetector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            Log.e(TAG, "Fast face detection failed: ${e.message}")
            emptyList()
        }
        val accurateFaces = try {
            Tasks.await(accurateBoxDetector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            Log.e(TAG, "Accurate face detection failed: ${e.message}")
            emptyList()
        }

        for (face in fastFaces + accurateFaces) {
            val box = clampToBitmap(face.boundingBox, bitmap.width, bitmap.height) ?: continue
            val track = matchFaceHold(box, now)
            track.box = Rect(box)
            track.lastSeenAt = now
        }
        allFaceTracks.removeAll { now - it.lastSeenAt > ALL_FACE_KEEP_BLUR_MS }
        return heldAllFaces(now)
    }

    private fun matchFaceHold(box: Rect, now: Long): FaceHold {
        var best: FaceHold? = null
        var bestIou = 0f
        for (track in allFaceTracks) {
            val score = iou(box, track.box)
            if (score > bestIou) {
                bestIou = score
                best = track
            }
        }
        if (best != null && bestIou >= ALL_FACE_MATCH_IOU) return best
        val track = FaceHold(Rect(box), now)
        allFaceTracks.add(track)
        return track
    }

    private fun heldAllFaces(now: Long): List<FaceRegion> {
        allFaceTracks.removeAll { now - it.lastSeenAt > ALL_FACE_KEEP_BLUR_MS }
        if (allFaceTracks.isEmpty()) return emptyList()
        val result = ArrayList<FaceRegion>(allFaceTracks.size)
        for (track in allFaceTracks) {
            result.add(FaceRegion(Rect(track.box), null))
        }
        return result
    }

    private fun shouldBlurForMinors(bitmap: Bitmap, box: Rect, now: Long): Boolean {
        val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val quality = FaceQuality.evaluate(crop, LIVE_MIN_FACE_SHORT_SIDE)
        val verdict = try {
            ageClassifier.classify(crop, quality)
        } catch (e: Exception) {
            Log.e(TAG, "Age classification failed: ${e.message}")
            AgeVerdict.unknown("classification-exception", quality, "live")
        } finally {
            crop.recycle()
        }

        val track = matchTrack(box, now)
        track.box = Rect(box)
        track.lastSeenAt = now

        if (verdict.label != AgeLabel.ADULT_CONFIRMED) {
            track.lastUnsafeAt = now
            track.adultObservations.clear()
            return true
        }

        track.adultObservations.addLast(now)
        while (track.adultObservations.isNotEmpty() &&
            now - track.adultObservations.first() > TRACK_KEEP_BLUR_MS
        ) {
            track.adultObservations.removeFirst()
        }
        val observations = track.adultObservations.size
        val windowMs = if (observations > 1) now - track.adultObservations.first() else 0L
        val unsafeRecently = now - track.lastUnsafeAt < TRACK_KEEP_BLUR_MS
        return observations < ADULT_CONFIRM_OBSERVATIONS ||
            windowMs < ADULT_CONFIRM_WINDOW_MS ||
            unsafeRecently
    }

    private fun matchTrack(box: Rect, now: Long): AgeTrack {
        var best: AgeTrack? = null
        var bestIou = 0f
        for (track in ageTracks) {
            val score = iou(box, track.box)
            if (score > bestIou) {
                bestIou = score
                best = track
            }
        }
        if (best != null && bestIou >= TRACK_MATCH_IOU) return best
        val track = AgeTrack(Rect(box), now, now)
        ageTracks.add(track)
        return track
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0, right - left) * maxOf(0, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }

    /** Softly blurs each region in place on [bitmap], clipped to its (feathered) face shape. */
    fun blurRegions(bitmap: Bitmap, regions: List<FaceRegion>) {
        if (regions.isEmpty()) return
        val canvas = Canvas(bitmap)
        for (r in regions) BlurRenderer.blurRegion(bitmap, canvas, r.box, r.contour)
    }

    /** Flattens the ML Kit FACE contour into {x0,y0,x1,y1,...}, or null if unavailable. */
    private fun contourPoints(face: com.google.mlkit.vision.face.Face): FloatArray? {
        val points = face.getContour(FaceContour.FACE)?.points ?: return null
        if (points.size < 3) return null
        val out = FloatArray(points.size * 2)
        for (i in points.indices) {
            out[2 * i] = points[i].x
            out[2 * i + 1] = points[i].y
        }
        return out
    }

    private fun clampToBitmap(r: Rect, w: Int, h: Int): Rect? {
        // Pad the detected box outward so hair, jaw and ears are also covered.
        val padX = (r.width() * FACE_PADDING).toInt()
        val padY = (r.height() * FACE_PADDING).toInt()
        val left = (r.left - padX).coerceIn(0, w - 1)
        val top = (r.top - padY).coerceIn(0, h - 1)
        val right = (r.right + padX).coerceIn(left + 1, w)
        val bottom = (r.bottom + padY).coerceIn(top + 1, h)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    fun close() {
        fastBoxDetector.close()
        accurateBoxDetector.close()
        contourDetector.close()
        ageClassifier.close()
    }

    fun resetTracking() {
        ageTracks.clear()
        allFaceTracks.clear()
    }
}
