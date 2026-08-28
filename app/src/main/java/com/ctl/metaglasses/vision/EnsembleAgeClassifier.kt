package com.ctl.metaglasses.vision

import android.graphics.Bitmap
import android.util.Log

/**
 * Combines several [AgeClassifier]s into a single conservative verdict.
 *
 * Each loaded member contributes a weighted minor-risk score. The aggregate score is useful for UI
 * diagnostics, but the final policy remains privacy-first:
 *   - any loaded model that calls MINOR keeps the person blurred
 *   - UNKNOWN keeps the person blurred unless every model confirms adult
 *   - ADULT_CONFIRMED requires every loaded model to agree and weighted pMinor <= 0.01
 *
 * Add genuinely independent models to [classifiers] and matching [weights] as they are validated.
 * Reusing the same model multiple times would create fake confidence, so callers should not do that.
 */
class EnsembleAgeClassifier @JvmOverloads constructor(
    private val classifiers: List<AgeClassifier>,
    private val weights: List<Float> = List(classifiers.size) { 1f },
) : AgeClassifier {

    private companion object {
        const val TAG = "EnsembleAgeClassifier"
    }

    override val isLoaded: Boolean
        get() = classifiers.any { it.isLoaded }

    override fun isLikelyMinor(faceBitmap: Bitmap): Boolean {
        return classify(faceBitmap).shouldBlurForMinors
    }

    override fun classify(faceBitmap: Bitmap, quality: FaceQuality): AgeVerdict {
        val voters = classifiers.withIndex().filter { it.value.isLoaded }
        if (voters.isEmpty()) {
            return AgeVerdict.unknown("no-age-model-loaded", quality, "weighted-ensemble")
        }

        val verdicts = ArrayList<AgeVerdict>(voters.size)
        var totalWeight = 0f
        var weightedPMinor = 0f
        for ((index, classifier) in voters) {
            val verdict = classifier.classify(faceBitmap, quality)
            val weight = weightAt(index)
            totalWeight += weight
            weightedPMinor += weight * minorRisk(verdict)
            verdicts.add(verdict)
        }
        weightedPMinor = if (totalWeight > 0f) {
            (weightedPMinor / totalWeight).coerceIn(0f, 1f)
        } else {
            1f
        }

        val minorVotes = verdicts.count { it.label == AgeLabel.MINOR }
        val unknownVotes = verdicts.count { it.label == AgeLabel.UNKNOWN }
        val adultVotes = verdicts.count { it.label == AgeLabel.ADULT_CONFIRMED }
        val verdict = when {
            minorVotes > 0 -> AgeLabel.MINOR
            unknownVotes > 0 -> AgeLabel.UNKNOWN
            adultVotes == voters.size && weightedPMinor <= 0.01f -> AgeLabel.ADULT_CONFIRMED
            else -> AgeLabel.UNKNOWN
        }
        Log.d(
            TAG,
            "Weighted age vote: minor=$minorVotes unknown=$unknownVotes adult=$adultVotes " +
                "pMinor=$weightedPMinor -> $verdict",
        )
        return AgeVerdict(
            verdict,
            weightedPMinor,
            displayAge(verdicts),
            quality.score,
            verdicts.flatMap { it.reasons }
                .ifEmpty { listOf("weighted-ensemble") } +
                "votes:$minorVotes minor/$unknownVotes unknown/$adultVotes adult",
            "weighted-ensemble:${verdicts.joinToString("+") { it.modelVersion }}",
        )
    }

    private fun displayAge(verdicts: List<AgeVerdict>): Float? {
        return verdicts.mapNotNull { it.estimatedAge }
            .filter { !it.isNaN() }
            .maxOrNull()
    }

    private fun weightAt(index: Int): Float {
        val weight = weights.getOrNull(index) ?: 1f
        return if (weight > 0f && !weight.isNaN() && !weight.isInfinite()) weight else 1f
    }

    private fun minorRisk(verdict: AgeVerdict): Float {
        return when (verdict.label) {
            AgeLabel.MINOR -> verdict.pMinor.coerceAtLeast(0.5f)
            AgeLabel.UNKNOWN -> verdict.pMinor.coerceAtLeast(0.5f)
            AgeLabel.ADULT_CONFIRMED -> verdict.pMinor
        }.coerceIn(0f, 1f)
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
