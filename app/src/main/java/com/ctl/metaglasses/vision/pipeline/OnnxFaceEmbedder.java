package com.ctl.metaglasses.vision.pipeline;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Generic ArcFace/MobileFaceNet-style ONNX embedder. Disabled when asset loading fails. */
public final class OnnxFaceEmbedder implements FaceEmbedder {
    private static final String TAG = "OnnxFaceEmbedder";
    private final ModelManifest.ModelConfig config;
    private final OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private int inputWidth;
    private int inputHeight;
    private boolean nchw;
    private float[] input;

    public OnnxFaceEmbedder(Context context, ModelManifest.ModelConfig config) {
        this.config = config;
        this.env = OrtEnvironment.getEnvironment();
        try {
            byte[] bytes = AssetIO.readAsset(context, config.assetPath);
            session = env.createSession(bytes, new OrtSession.SessionOptions());
            Map.Entry<String, NodeInfo> inputInfo = session.getInputInfo().entrySet().iterator().next();
            inputName = inputInfo.getKey();
            long[] shape = ((TensorInfo) inputInfo.getValue().getInfo()).getShape();
            nchw = !"NHWC".equalsIgnoreCase(config.inputLayout);
            inputHeight = config.inputHeight > 0 ? config.inputHeight : dim(shape, nchw ? 2 : 1, 112);
            inputWidth = config.inputWidth > 0 ? config.inputWidth : dim(shape, nchw ? 3 : 2, 112);
            input = new float[3 * inputWidth * inputHeight];
            Log.i(TAG, "Loaded " + config.assetPath + " input=" + inputWidth + "x" + inputHeight);
        } catch (Throwable t) {
            Log.w(TAG, "Embedding model unavailable; fallback embedder will be used. " + t.getMessage());
            session = null;
        }
    }

    @Override
    public boolean isLoaded() {
        return session != null;
    }

    @Override
    public String modelId() {
        return config.id == null || config.id.isEmpty() ? "onnx-embedder" : config.id;
    }

    @Override
    public float[] embed(Bitmap alignedFace) throws Exception {
        OrtSession s = session;
        if (s == null) throw new IllegalStateException("Embedding model not loaded");
        Bitmap resized = Bitmap.createScaledBitmap(alignedFace, inputWidth, inputHeight, true);
        preprocess(resized);
        if (resized != alignedFace) resized.recycle();
        long[] shape = nchw
                ? new long[] {1, 3, inputHeight, inputWidth}
                : new long[] {1, inputHeight, inputWidth, 3};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = s.run(Collections.singletonMap(inputName, tensor))) {
            OnnxValue value = result.get(0);
            if (value == null) throw new IllegalStateException("No embedding output");
            float[] embedding = flatten(value.getValue());
            l2Normalize(embedding);
            return embedding;
        }
    }

    private void preprocess(Bitmap bitmap) {
        int[] pixels = new int[inputWidth * inputHeight];
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        int plane = inputWidth * inputHeight;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF);
            float g = ((p >> 8) & 0xFF);
            float b = (p & 0xFF);
            if ("imagenet".equalsIgnoreCase(config.normalization)) {
                r = (r / 255f - 0.485f) / 0.229f;
                g = (g / 255f - 0.456f) / 0.224f;
                b = (b / 255f - 0.406f) / 0.225f;
            } else if ("minus_one_to_one".equalsIgnoreCase(config.normalization)) {
                r = (r - 127.5f) / 128f;
                g = (g - 127.5f) / 128f;
                b = (b - 127.5f) / 128f;
            } else {
                r /= 255f;
                g /= 255f;
                b /= 255f;
            }
            if (nchw) {
                input[i] = r;
                input[plane + i] = g;
                input[2 * plane + i] = b;
            } else {
                int j = i * 3;
                input[j] = r;
                input[j + 1] = g;
                input[j + 2] = b;
            }
        }
    }

    static float[] flatten(Object value) {
        List<Float> out = new ArrayList<>();
        flattenInto(value, out);
        float[] arr = new float[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    private static void flattenInto(Object value, List<Float> out) {
        if (value instanceof float[]) {
            for (float v : (float[]) value) out.add(v);
        } else if (value instanceof float[][]) {
            for (float[] row : (float[][]) value) flattenInto(row, out);
        } else if (value instanceof float[][][]) {
            for (float[][] row : (float[][][]) value) flattenInto(row, out);
        } else if (value instanceof float[][][][]) {
            for (float[][][] row : (float[][][][]) value) flattenInto(row, out);
        }
    }

    static void l2Normalize(float[] vector) {
        float norm = 0f;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(Math.max(norm, 1e-6f));
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    }

    private static int dim(long[] shape, int index, int fallback) {
        return shape.length > index && shape[index] > 0 ? (int) shape[index] : fallback;
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
    }
}
