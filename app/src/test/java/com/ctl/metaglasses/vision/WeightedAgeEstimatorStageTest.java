package com.ctl.metaglasses.vision;

import android.graphics.Bitmap;

import com.ctl.metaglasses.vision.pipeline.AgeEstimatorStage;
import com.ctl.metaglasses.vision.pipeline.WeightedAgeEstimatorStage;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class WeightedAgeEstimatorStageTest {
    @Test
    public void mixedVotersExposeDisplayAgeNotMinimumAge() {
        FaceQuality quality = new FaceQuality(0.9f, true, Collections.emptyList());
        WeightedAgeEstimatorStage stage = new WeightedAgeEstimatorStage();
        stage.add(new FixedEstimator(new AgeVerdict(
                AgeLabel.MINOR,
                1f,
                16f,
                0.9f,
                Collections.singletonList("fixed-minor"),
                "minor"
        )), 1f);
        stage.add(new FixedEstimator(new AgeVerdict(
                AgeLabel.ADULT_CONFIRMED,
                0.01f,
                35f,
                0.9f,
                Collections.singletonList("fixed-adult"),
                "adult"
        )), 1f);

        AgeVerdict verdict = stage.estimate(null, null, quality);

        assertEquals(AgeLabel.MINOR, verdict.getLabel());
        assertEquals(35f, verdict.getEstimatedAge(), 0.001f);
    }

    private static final class FixedEstimator implements AgeEstimatorStage {
        private final AgeVerdict verdict;

        FixedEstimator(AgeVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public String modelId() {
            return verdict.getModelVersion();
        }

        @Override
        public AgeVerdict estimate(Bitmap alignedFace, float[] embedding, FaceQuality quality) {
            return verdict;
        }

        @Override
        public void close() {
        }
    }
}
