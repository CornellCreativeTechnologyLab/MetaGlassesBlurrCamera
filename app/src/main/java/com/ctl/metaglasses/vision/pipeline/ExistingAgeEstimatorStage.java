package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

import com.ctl.metaglasses.vision.AgeClassifier;
import com.ctl.metaglasses.vision.AgeVerdict;
import com.ctl.metaglasses.vision.FaceQuality;

/** Adapter around the current TFLite/ONNX age ensemble. */
public final class ExistingAgeEstimatorStage implements AgeEstimatorStage {
    private final AgeClassifier classifier;

    public ExistingAgeEstimatorStage(AgeClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public boolean isLoaded() {
        return classifier.isLoaded();
    }

    @Override
    public String modelId() {
        return "existing-age-ensemble";
    }

    @Override
    public AgeVerdict estimate(Bitmap alignedFace, float[] embedding, FaceQuality quality) {
        return classifier.classify(alignedFace, quality);
    }

    @Override
    public void close() {
        classifier.close();
    }
}
