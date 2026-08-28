package com.ctl.metaglasses.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Privacy-first age classification.
 *
 * Public UI still uses [BlurMode.MINORS_ONLY], but internally age is a 3-state decision:
 * [AgeLabel.MINOR], [AgeLabel.UNKNOWN], or [AgeLabel.ADULT_CONFIRMED]. Callers must blur anything
 * except [AgeLabel.ADULT_CONFIRMED]. This fail-closed policy reflects the fact that face-only age
 * estimation is noisy around 18 and across image quality/demographic conditions.
 */
enum class AgeLabel { MINOR, ADULT_CONFIRMED, UNKNOWN }

data class AgeVerdict(
    val label: AgeLabel,
    val pMinor: Float,
    val estimatedAge: Float?,
    val qualityScore: Float,
    val reasons: List<String>,
    val modelVersion: String,
) {
    val shouldBlurForMinors: Boolean
        get() = label != AgeLabel.ADULT_CONFIRMED

    companion object {
        @JvmStatic
        fun unknown(reason: String, quality: FaceQuality? = null, modelVersion: String = "unknown"): AgeVerdict {
            return AgeVerdict(
                AgeLabel.UNKNOWN,
                1f,
                null,
                quality?.score ?: 0f,
                listOf(reason) + (quality?.reasons ?: emptyList()),
                modelVersion,
            )
        }
    }
}

data class FaceQuality(
    val score: Float,
    val passes: Boolean,
    val reasons: List<String>,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun evaluate(faceBitmap: Bitmap, minShortSide: Int = 96): FaceQuality {
            val reasons = ArrayList<String>()
            val shortSide = min(faceBitmap.width, faceBitmap.height)
            if (shortSide < minShortSide) reasons.add("face-too-small")

            val sample = Bitmap.createScaledBitmap(faceBitmap, 24, 24, true)
            val pixels = IntArray(24 * 24)
            sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            if (sample !== faceBitmap) sample.recycle()

            var sum = 0f
            val luma = FloatArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                val y = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f
                luma[i] = y
                sum += y
            }
            val mean = sum / luma.size
            if (mean < 0.16f) reasons.add("too-dark")
            if (mean > 0.88f) reasons.add("too-bright")

            var contrast = 0f
            for (y in luma) contrast += abs(y - mean)
            contrast /= luma.size
            if (contrast < 0.045f) reasons.add("low-contrast-or-blurry")

            var edgeEnergy = 0f
            for (y in 1 until 24) {
                for (x in 1 until 24) {
                    val i = y * 24 + x
                    edgeEnergy += abs(luma[i] - luma[i - 1])
                    edgeEnergy += abs(luma[i] - luma[i - 24])
                }
            }
            edgeEnergy /= (23 * 23 * 2)
            if (edgeEnergy < 0.018f) reasons.add("low-detail-or-occluded")

            val sizeScore = (shortSide.toFloat() / minShortSide.toFloat()).coerceIn(0f, 1f)
            val exposureScore = (1f - abs(mean - 0.52f) / 0.52f).coerceIn(0f, 1f)
            val contrastScore = (contrast / 0.12f).coerceIn(0f, 1f)
            val edgeScore = (edgeEnergy / 0.055f).coerceIn(0f, 1f)
            val score = (0.35f * sizeScore + 0.25f * exposureScore + 0.2f * contrastScore + 0.2f * edgeScore)
                .coerceIn(0f, 1f)
            return FaceQuality(score, reasons.isEmpty() && score >= 0.55f, reasons)
        }
    }
}

object AgeDecisionPolicy {
    private const val ADULT_CONFIRMED_MAX_MINOR_PROBABILITY = 0.01f
    private const val ADULT_CONFIRMED_MIN_AGE = 25f
    private const val LEGACY_MINOR_AGE = 18f
    private const val LEGACY_UNKNOWN_UNTIL_AGE = 25f

    @JvmStatic
    fun fromAgeEstimate(estimatedAge: Float?, quality: FaceQuality, modelVersion: String): AgeVerdict {
        if (!quality.passes) return AgeVerdict.unknown("quality-gate", quality, modelVersion)
        if (estimatedAge == null || estimatedAge.isNaN()) {
            return AgeVerdict.unknown("missing-age-estimate", quality, modelVersion)
        }
        return when {
            estimatedAge < LEGACY_MINOR_AGE -> AgeVerdict(
                AgeLabel.MINOR,
                1f,
                estimatedAge,
                quality.score,
                listOf("estimated-age-below-$LEGACY_MINOR_AGE"),
                modelVersion,
            )
            estimatedAge >= LEGACY_UNKNOWN_UNTIL_AGE -> AgeVerdict(
                AgeLabel.ADULT_CONFIRMED,
                ADULT_CONFIRMED_MAX_MINOR_PROBABILITY,
                estimatedAge,
                quality.score,
                listOf("legacy-age-safely-adult"),
                modelVersion,
            )
            else -> AgeVerdict(
                AgeLabel.UNKNOWN,
                0.5f,
                estimatedAge,
                quality.score,
                listOf("near-age-boundary"),
                modelVersion,
            )
        }
    }

    @JvmStatic
    fun fromBuckets(probabilities: FloatArray, quality: FaceQuality, modelVersion: String): AgeVerdict {
        if (!quality.passes) return AgeVerdict.unknown("quality-gate", quality, modelVersion)
        if (probabilities.size < 6) return AgeVerdict.unknown("invalid-bucket-output", quality, modelVersion)

        val pMinor = (probabilities[0] + probabilities[1] + probabilities[2]).coerceIn(0f, 1f)
        val pOver24 = (probabilities[4] + probabilities[5]).coerceIn(0f, 1f)
        val estimatedAge = weightedBucketAge(probabilities)
        return when {
            pMinor >= 0.5f -> AgeVerdict(
                AgeLabel.MINOR,
                pMinor,
                estimatedAge,
                quality.score,
                listOf("bucket-minor-probability"),
                modelVersion,
            )
            pMinor <= ADULT_CONFIRMED_MAX_MINOR_PROBABILITY && pOver24 >= 0.65f -> AgeVerdict(
                AgeLabel.ADULT_CONFIRMED,
                pMinor,
                estimatedAge,
                quality.score,
                listOf("bucket-adult-confirmed"),
                modelVersion,
            )
            else -> AgeVerdict(
                AgeLabel.UNKNOWN,
                pMinor,
                estimatedAge,
                quality.score,
                listOf("bucket-uncertain"),
                modelVersion,
            )
        }
    }

    private fun weightedBucketAge(probabilities: FloatArray): Float {
        val midpoints = floatArrayOf(3f, 9.5f, 15f, 21f, 29.5f, 45f)
        var total = 0f
        var weight = 0f
        for (i in 0 until min(probabilities.size, midpoints.size)) {
            total += probabilities[i] * midpoints[i]
            weight += probabilities[i]
        }
        return if (weight > 0f) total / weight else Float.NaN
    }
}

interface AgeClassifierV2 {
    val isLoaded: Boolean
    fun classify(faceBitmap: Bitmap, quality: FaceQuality = FaceQuality.evaluate(faceBitmap)): AgeVerdict
    fun close()
}

/**
 * Legacy adapter kept during migration. Old call sites get fail-closed behavior because anything
 * except [AgeLabel.ADULT_CONFIRMED] still returns true and therefore gets blurred.
 *
 * Ships with a UTKFace-trained age-estimation model (`assets/age_classifier.tflite`, MIT-licensed):
 *   - Input:  1 x N x N x 3 float32, RGB, pixels normalized to [0, 1]  (N auto-detected, model uses 200)
 *   - Output: 1 x 1 float32 = normalized age; estimated age = output * [ageScale] (default 116)
 *   - A face is treated as a minor when estimated age < 18. Ages 18-24 stay UNKNOWN
 *     until safely adult, so privacy blur remains fail-closed without calling adults minors.
 *
 * To swap in your own fine-tuned model, replace the asset. If it keeps the same output convention
 * (normalized-age * scale) it just works; otherwise ping me to adjust the output parsing. Input size
 * and float/uint8 type are detected from the model automatically.
 *
 * If the model is missing or fails, [isLoaded] is false and [isLikelyMinor] returns true (fail safe)
 * so a real child is never missed while you gather data / validate the model.
 */
interface AgeClassifier : AgeClassifierV2 {
    fun isLikelyMinor(faceBitmap: Bitmap): Boolean = classify(faceBitmap).shouldBlurForMinors

    override fun classify(faceBitmap: Bitmap, quality: FaceQuality): AgeVerdict {
        if (!isLoaded) return AgeVerdict.unknown("model-not-loaded", quality, "legacy-adapter")
        return if (isLikelyMinor(faceBitmap)) {
            AgeVerdict(AgeLabel.MINOR, 1f, null, quality.score, listOf("legacy-minor"), "legacy-adapter")
        } else {
            AgeVerdict(AgeLabel.ADULT_CONFIRMED, 0.01f, null, quality.score, listOf("legacy-adult"), "legacy-adapter")
        }
    }
}

class TFLiteAgeClassifier @JvmOverloads constructor(
    context: Context,
    // Kept for constructor compatibility; the shared AgeDecisionPolicy owns the actual boundary.
    @Suppress("UNUSED_PARAMETER")
    private val minorAgeThreshold: Float = 18f,
    private val ageScale: Float = 116f,
) : AgeClassifier {

    private companion object {
        const val TAG = "TFLiteAgeClassifier"
        const val MODEL_ASSET = "age_classifier.tflite"
        const val DEFAULT_INPUT = 200
    }

    private var interpreter: Interpreter? = null
    private var inputSize = DEFAULT_INPUT
    private var inputIsFloat = true

    private var inputBuffer: ByteBuffer? = null
    private var pixels = IntArray(0)
    private var outputBuffer = Array(1) { FloatArray(1) }
    private var outputSize = 1

    override val isLoaded: Boolean
        get() = interpreter != null

    init {
        try {
            context.assets.openFd(MODEL_ASSET).use { afd ->
                FileInputStream(afd.fileDescriptor).use { fis ->
                    val mapped = fis.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength,
                    )
                    val itp = Interpreter(mapped)
                    val inputTensor = itp.getInputTensor(0)
                    val shape = inputTensor.shape() // [1, H, W, 3]
                    if (shape.size == 4 && shape[1] > 0) inputSize = shape[1]
                    inputIsFloat = inputTensor.dataType() == DataType.FLOAT32
                    val outputShape = itp.getOutputTensor(0).shape()
                    outputSize = outputShape.lastOrNull()?.coerceAtLeast(1) ?: 1
                    outputBuffer = Array(1) { FloatArray(outputSize) }

                    val bytesPerChannel = if (inputIsFloat) 4 else 1
                    inputBuffer = ByteBuffer
                        .allocateDirect(bytesPerChannel * inputSize * inputSize * 3)
                        .order(ByteOrder.nativeOrder())
                    pixels = IntArray(inputSize * inputSize)
                    interpreter = itp
                    Log.i(TAG, "Age model loaded (input=${inputSize}, float=$inputIsFloat)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No age model ($MODEL_ASSET); minors mode fails safe to blur-all. ${e.message}")
            interpreter = null
        }
    }

    override fun isLikelyMinor(faceBitmap: Bitmap): Boolean {
        return classify(faceBitmap).shouldBlurForMinors
    }

    override fun classify(faceBitmap: Bitmap, quality: FaceQuality): AgeVerdict {
        val itp = interpreter ?: return AgeVerdict.unknown("model-not-loaded", quality, MODEL_ASSET)
        if (!quality.passes) return AgeVerdict.unknown("quality-gate", quality, MODEL_ASSET)

        val variants = faceVariants(faceBitmap)
        val verdicts = ArrayList<AgeVerdict>(variants.size)
        try {
            for (variant in variants) {
                verdicts.add(classifySingleCrop(itp, variant, quality))
            }
        } finally {
            for (variant in variants) {
                if (variant !== faceBitmap && !variant.isRecycled) variant.recycle()
            }
        }
        if (verdicts.any { it.label == AgeLabel.MINOR }) {
            return verdicts.first { it.label == AgeLabel.MINOR }
        }
        if (verdicts.all { it.label == AgeLabel.ADULT_CONFIRMED }) {
            val maxPMinor = verdicts.maxOf { it.pMinor }
            val minAge = verdicts.mapNotNull { it.estimatedAge }.minOrNull()
            return AgeVerdict(
                AgeLabel.ADULT_CONFIRMED,
                maxPMinor,
                minAge,
                quality.score,
                listOf("all-crops-adult-confirmed"),
                MODEL_ASSET,
            )
        }
        return AgeVerdict(
            AgeLabel.UNKNOWN,
            verdicts.maxOfOrNull { it.pMinor } ?: 1f,
            verdicts.mapNotNull { it.estimatedAge }.minOrNull(),
            quality.score,
            listOf("multi-crop-uncertain"),
            MODEL_ASSET,
        )
    }

    private fun classifySingleCrop(itp: Interpreter, faceBitmap: Bitmap, quality: FaceQuality): AgeVerdict {
        val buffer = inputBuffer ?: return AgeVerdict.unknown("input-buffer-not-ready", quality, MODEL_ASSET)
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        buffer.rewind()
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (inputIsFloat) {
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            } else {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            }
        }
        if (resized != faceBitmap) resized.recycle()

        return try {
            itp.run(buffer, outputBuffer)
            val output = outputBuffer[0].copyOf()
            if (outputSize >= 6) {
                AgeDecisionPolicy.fromBuckets(normalizedProbabilities(output), quality, MODEL_ASSET)
            } else {
                val estimatedAge = output[0] * ageScale
                AgeDecisionPolicy.fromAgeEstimate(estimatedAge, quality, MODEL_ASSET)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Age inference failed: ${e.message}")
            AgeVerdict.unknown("inference-failed", quality, MODEL_ASSET)
        }
    }

    private fun normalizedProbabilities(values: FloatArray): FloatArray {
        val sum = values.sum()
        if (sum in 0.98f..1.02f && values.all { it >= 0f }) return values
        val maxValue = values.maxOrNull() ?: 0f
        var denom = 0f
        val out = FloatArray(values.size)
        for (i in values.indices) {
            val v = exp((values[i] - maxValue).toDouble()).toFloat()
            out[i] = v
            denom += v
        }
        if (denom <= 0f) return values
        for (i in out.indices) out[i] /= denom
        return out
    }

    private fun faceVariants(faceBitmap: Bitmap): List<Bitmap> {
        val variants = ArrayList<Bitmap>(3)
        variants.add(faceBitmap)
        variants.add(cropAroundCenter(faceBitmap, 0.9f))
        variants.add(cropAroundCenter(faceBitmap, 0.78f))
        return variants
    }

    private fun cropAroundCenter(bitmap: Bitmap, fraction: Float): Bitmap {
        val w = max(1, (bitmap.width * fraction).toInt())
        val h = max(1, (bitmap.height * fraction).toInt())
        val left = ((bitmap.width - w) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - h) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
