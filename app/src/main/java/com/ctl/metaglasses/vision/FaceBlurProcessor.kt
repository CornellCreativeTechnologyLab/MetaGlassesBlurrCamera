package com.ctl.metaglasses.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
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
        const val MOSAIC = 30 // larger = coarser (more obscured) blocks
        const val FACE_PADDING = 0.25f // grow the face box so hair/jaw/ears are covered too
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
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
        val boxes = facesToBlur(bitmap, mode)
        if (boxes.isNotEmpty()) blurBoxes(bitmap, boxes)
        return bitmap
    }

    /**
     * Detects faces in [bitmap] and returns the padded boxes that should be blurred under [mode]
     * (all faces for [BlurMode.ALL_FACES]; only likely-minors for [BlurMode.MINORS_ONLY]). Boxes are
     * in [bitmap] pixel space. Blocking — call off the main thread. Used both to blur the live
     * preview and to record the face map at capture time so blurring stays non-destructive.
     */
    fun facesToBlur(bitmap: Bitmap, mode: BlurMode): List<Rect> {
        if (mode == BlurMode.OFF) return emptyList()

        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed: ${e.message}")
            return emptyList()
        }
        if (faces.isEmpty()) return emptyList()

        val result = ArrayList<Rect>(faces.size)
        for (face in faces) {
            val box = clampToBitmap(face.boundingBox, bitmap.width, bitmap.height) ?: continue
            val shouldBlur = when (mode) {
                BlurMode.ALL_FACES -> true
                BlurMode.MINORS_ONLY -> {
                    val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
                    val minor = ageClassifier.isLikelyMinor(crop)
                    crop.recycle()
                    minor
                }
                BlurMode.OFF -> false
            }
            if (shouldBlur) result.add(box)
        }
        return result
    }

    /** Pixelates each of [boxes] in place on [bitmap]. */
    fun blurBoxes(bitmap: Bitmap, boxes: List<Rect>) {
        if (boxes.isEmpty()) return
        val canvas = Canvas(bitmap)
        for (box in boxes) pixelate(bitmap, canvas, box)
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

    private fun pixelate(bitmap: Bitmap, canvas: Canvas, box: Rect) {
        val w = box.width()
        val h = box.height()
        val crop = Bitmap.createBitmap(bitmap, box.left, box.top, w, h)
        val small = Bitmap.createScaledBitmap(
            crop,
            (w / MOSAIC).coerceAtLeast(1),
            (h / MOSAIC).coerceAtLeast(1),
            false,
        )
        val mosaic = Bitmap.createScaledBitmap(small, w, h, false)
        canvas.drawBitmap(mosaic, box.left.toFloat(), box.top.toFloat(), null)
        crop.recycle()
        small.recycle()
        mosaic.recycle()
    }

    fun close() {
        detector.close()
        ageClassifier.close()
    }
}
