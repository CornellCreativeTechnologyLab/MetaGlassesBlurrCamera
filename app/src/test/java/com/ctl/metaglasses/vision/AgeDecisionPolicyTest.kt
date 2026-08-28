package com.ctl.metaglasses.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeDecisionPolicyTest {

    @Test
    fun lowQualityAlwaysReturnsUnknown() {
        val quality = FaceQuality(0.2f, false, listOf("face-too-small"))

        val verdict = AgeDecisionPolicy.fromAgeEstimate(40f, quality, "test")

        assertEquals(AgeLabel.UNKNOWN, verdict.label)
        assertTrue(verdict.shouldBlurForMinors)
    }

    @Test
    fun legacyAgeNearBoundaryFailsClosed() {
        val quality = FaceQuality(0.9f, true, emptyList())

        val verdict = AgeDecisionPolicy.fromAgeEstimate(22f, quality, "test")

        assertEquals(AgeLabel.UNKNOWN, verdict.label)
        assertTrue(verdict.shouldBlurForMinors)
    }

    @Test
    fun legacyAge18IsUnknownNotMinor() {
        val quality = FaceQuality(0.9f, true, emptyList())

        val verdict = AgeDecisionPolicy.fromAgeEstimate(18f, quality, "test")

        assertEquals(AgeLabel.UNKNOWN, verdict.label)
        assertTrue(verdict.shouldBlurForMinors)
    }

    @Test
    fun legacyAgeUnder18IsMinor() {
        val quality = FaceQuality(0.9f, true, emptyList())

        val verdict = AgeDecisionPolicy.fromAgeEstimate(17.9f, quality, "test")

        assertEquals(AgeLabel.MINOR, verdict.label)
        assertTrue(verdict.shouldBlurForMinors)
    }

    @Test
    fun legacyAgeSafelyAdultCanBeConfirmed() {
        val quality = FaceQuality(0.9f, true, emptyList())

        val verdict = AgeDecisionPolicy.fromAgeEstimate(31f, quality, "test")

        assertEquals(AgeLabel.ADULT_CONFIRMED, verdict.label)
        assertEquals(false, verdict.shouldBlurForMinors)
    }

    @Test
    fun bucketAdultRequiresTinyMinorProbabilityAndOver24Evidence() {
        val quality = FaceQuality(0.9f, true, emptyList())

        val verdict = AgeDecisionPolicy.fromBuckets(
            floatArrayOf(0.001f, 0.002f, 0.003f, 0.10f, 0.55f, 0.344f),
            quality,
            "test",
        )

        assertEquals(AgeLabel.ADULT_CONFIRMED, verdict.label)
    }

    @Test
    fun bucketUncertainBlurredEvenWhenAdultLeaning() {
        val quality = FaceQuality(0.9f, true, emptyList())

        val verdict = AgeDecisionPolicy.fromBuckets(
            floatArrayOf(0.01f, 0.01f, 0.02f, 0.75f, 0.14f, 0.07f),
            quality,
            "test",
        )

        assertEquals(AgeLabel.UNKNOWN, verdict.label)
        assertTrue(verdict.shouldBlurForMinors)
    }
}
