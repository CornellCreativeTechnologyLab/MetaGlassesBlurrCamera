package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

/** Small deterministic fallback embedding used until a mobile embedding ONNX asset is available. */
public final class HashFaceEmbedder implements FaceEmbedder {
    private static final int W = 16;
    private static final int H = 8;

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public String modelId() {
        return "thumbnail-hash-fallback";
    }

    @Override
    public float[] embed(Bitmap alignedFace) {
        Bitmap small = Bitmap.createScaledBitmap(alignedFace, W, H, true);
        int[] pixels = new int[W * H];
        small.getPixels(pixels, 0, W, 0, 0, W, H);
        if (small != alignedFace) small.recycle();
        float[] vector = new float[pixels.length];
        float mean = 0f;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float y = (((p >> 16) & 0xFF) * 0.299f + ((p >> 8) & 0xFF) * 0.587f + (p & 0xFF) * 0.114f) / 255f;
            vector[i] = y;
            mean += y;
        }
        mean /= vector.length;
        float norm = 0f;
        for (int i = 0; i < vector.length; i++) {
            vector[i] -= mean;
            norm += vector[i] * vector[i];
        }
        norm = (float) Math.sqrt(Math.max(norm, 1e-6f));
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    @Override
    public void close() {
    }
}
