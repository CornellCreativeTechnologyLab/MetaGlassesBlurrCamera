package com.ctl.metaglasses.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * Second age classifier, used as a voting member alongside [TFLiteAgeClassifier].
 *
 * Wraps `assets/age_classifier.onnx`, a NumPy logistic-regression model:
 *   - Input  `input`:         1 x 2304 float32  (a 48x48 grayscale face, pixels /255, row-major)
 *   - Output `probabilities`: 1 x 2 float32     (softmax over two age classes)
 *
 * A face is judged a minor when the minor class wins the softmax.
 *
 * ⚠ ASSUMPTIONS worth validating: the model ships without label metadata or a training script, so
 * two things are educated guesses:
 *   1. [minorClassIndex] — which output index is "under 18". Default 1; flip to 0 if validation
 *      shows it inverted.
 *   2. Pixel scaling — assumed [0,1] via /255 and luma grayscale.
 * If the ONNX vote looks systematically wrong, these are the two knobs. Because the ensemble
 * ([EnsembleAgeClassifier]) breaks ties toward "minor", a wrong guess here still fails safe when the
 * TFLite model disagrees, but a confidently-inverted model could reveal a minor — so validate.
 */
class OnnxAgeClassifier @JvmOverloads constructor(
    context: Context,
    private val minorClassIndex: Int = 1,
) : AgeClassifier {

    private companion object {
        const val TAG = "OnnxAgeClassifier"
        const val MODEL_ASSET = "age_classifier.onnx"
        const val INPUT_NAME = "input"
        const val INPUT_SIZE = 48
        const val FEATURES = INPUT_SIZE * INPUT_SIZE // 2304
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val lock = Any()
    private val features = FloatArray(FEATURES)
    private val pixels = IntArray(FEATURES)

    override val isLoaded: Boolean
        get() = session != null

    init {
        try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            session = environment.createSession(bytes, OrtSession.SessionOptions())
            env = environment
            Log.i(TAG, "ONNX age model loaded (features=$FEATURES, minorIndex=$minorClassIndex)")
        } catch (e: Throwable) {
            Log.w(TAG, "No ONNX age model ($MODEL_ASSET); it just won't vote. ${e.message}")
            session = null
        }
    }

    override fun isLikelyMinor(faceBitmap: Bitmap): Boolean {
        val ortSession = session ?: return true // fail safe: unknown => treat as minor
        val environment = env ?: return true

        return synchronized(lock) {
            var input: OnnxTensor? = null
            try {
                preprocess(faceBitmap)
                input = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(features),
                    longArrayOf(1, FEATURES.toLong()),
                )
                ortSession.run(mapOf(INPUT_NAME to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val probs = (result[0].value as Array<FloatArray>)[0]
                    val adultIndex = if (minorClassIndex == 0) 1 else 0
                    probs[minorClassIndex] >= probs[adultIndex]
                }
            } catch (e: Exception) {
                Log.e(TAG, "ONNX inference failed: ${e.message}")
                true // fail safe
            } finally {
                input?.close()
            }
        }
    }

    /** Face -> 48x48 grayscale (luma), normalized to [0,1], flattened row-major into [features]. */
    private fun preprocess(faceBitmap: Bitmap) {
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (i in 0 until FEATURES) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b)
            features[i] = gray / 255f
        }
        if (resized != faceBitmap) resized.recycle()
    }

    override fun close() {
        synchronized(lock) {
            try {
                session?.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed: ${e.message}")
            }
            session = null
            // OrtEnvironment is a shared singleton; do not close it here.
            env = null
        }
    }
}
