package com.ctl.metaglasses.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Decides whether a cropped face likely belongs to a minor.
 *
 * Ships with a UTKFace-trained age-estimation model (`assets/age_classifier.tflite`, MIT-licensed):
 *   - Input:  1 x N x N x 3 float32, RGB, pixels normalized to [0, 1]  (N auto-detected, model uses 200)
 *   - Output: 1 x 1 float32 = normalized age; estimated age = output * [ageScale] (default 116)
 *   - A face is treated as a minor when estimated age < [minorAgeThreshold].
 *
 * To swap in your own fine-tuned model, replace the asset. If it keeps the same output convention
 * (normalized-age * scale) it just works; otherwise ping me to adjust the output parsing. Input size
 * and float/uint8 type are detected from the model automatically.
 *
 * If the model is missing or fails, [isLoaded] is false and [isLikelyMinor] returns true (fail safe)
 * so a real child is never missed while you gather data / validate the model.
 */
interface AgeClassifier {
    val isLoaded: Boolean
    fun isLikelyMinor(faceBitmap: Bitmap): Boolean
    fun close()
}

class TFLiteAgeClassifier @JvmOverloads constructor(
    context: Context,
    // Conservative by default: age models are noisy (±several years) and missing a child is the
    // costly error, so we flag under-20 as minor. Raise toward 25 to miss even fewer minors.
    private val minorAgeThreshold: Float = 20f,
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
    private val outputBuffer = Array(1) { FloatArray(1) }

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
        val itp = interpreter ?: return true // fail safe: treat unknown as minor
        val buffer = inputBuffer ?: return true

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
            val estimatedAge = outputBuffer[0][0] * ageScale
            estimatedAge < minorAgeThreshold
        } catch (e: Exception) {
            Log.e(TAG, "Age inference failed: ${e.message}")
            true // fail safe
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
