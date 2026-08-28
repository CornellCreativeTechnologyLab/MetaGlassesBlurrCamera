package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

import com.ctl.metaglasses.vision.AgeVerdict;
import com.ctl.metaglasses.vision.FaceQuality;

public interface AgeEstimatorStage extends AutoCloseable {
    boolean isLoaded();
    String modelId();
    AgeVerdict estimate(Bitmap alignedFace, float[] embedding, FaceQuality quality);
    @Override void close();
}
