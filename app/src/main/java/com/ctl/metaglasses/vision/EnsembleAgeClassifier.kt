package com.ctl.metaglasses.vision

import android.graphics.Bitmap
import android.util.Log

/**
 * Combines several [AgeClassifier]s into a single verdict by majority vote.
 *
 * Every loaded member votes "minor" or "adult" on the same face; the face is treated as a minor when
 * minor votes are at least adult votes. That means:
 *   - all agree                    -> that answer
 *   - they split evenly (e.g. 1-1) -> minor (privacy-safe tie-break)
 *   - no member is loaded          -> minor (fail safe, same as the single-model behavior)
 *
 * This keeps the app's "never miss a child" bias while letting a second model correct the first when
 * they agree. Scales to any number of models — today it's [TFLiteAgeClassifier] + [OnnxAgeClassifier].
 */
class EnsembleAgeClassifier(
    private val classifiers: List<AgeClassifier>,
) : AgeClassifier {

    private companion object {
        const val TAG = "EnsembleAgeClassifier"
    }

    override val isLoaded: Boolean
        get() = classifiers.any { it.isLoaded }

    override fun isLikelyMinor(faceBitmap: Bitmap): Boolean {
        val voters = classifiers.filter { it.isLoaded }
        if (voters.isEmpty()) return true // fail safe

        var minorVotes = 0
        for (c in voters) {
            if (c.isLikelyMinor(faceBitmap)) minorVotes++
        }
        val adultVotes = voters.size - minorVotes
        val minor = minorVotes >= adultVotes
        Log.d(TAG, "Age vote: minor=$minorVotes adult=$adultVotes -> ${if (minor) "MINOR" else "ADULT"}")
        return minor
    }

    override fun close() {
        classifiers.forEach {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed: ${e.message}")
            }
        }
    }
}
