package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

import java.util.List;

public interface FaceDetectorStage extends AutoCloseable {
    boolean isLoaded();
    String modelId();
    List<FaceCandidate> detect(Bitmap frame) throws Exception;
    @Override void close();
}
