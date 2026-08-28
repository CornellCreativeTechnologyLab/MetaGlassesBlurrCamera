package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

public interface FaceEmbedder extends AutoCloseable {
    boolean isLoaded();
    String modelId();
    float[] embed(Bitmap alignedFace) throws Exception;
    @Override void close();
}
