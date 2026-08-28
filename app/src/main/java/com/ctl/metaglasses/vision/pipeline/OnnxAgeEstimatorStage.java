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

import com.ctl.metaglasses.vision.AgeDecisionPolicy;
import com.ctl.metaglasses.vision.AgeVerdict;
import com.ctl.metaglasses.vision.FaceQuality;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/** Generic direct-crop ONNX age estimator for SSR/MobileNet/EfficientNet/genderage style heads. */
public final class OnnxAgeEstimatorStage implements AgeEstimatorStage {
    private static final String TAG = "OnnxAgeEstimator";

    private final ModelManifest.ModelConfig config;
    private final OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private int inputWidth;
    private int inputHeight;
    private boolean nchw;
    private float[] input;

    public OnnxAgeEstimatorStage(Context context, ModelManifest.ModelConfig config) {
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
            Log.w(TAG, "Age ONNX unavailable: " + config.assetPath + " " + t.getMessage());
            session = null;
        }
    }

    @Override
    public boolean isLoaded() {
        return session != null;
    }

    @Override
    public String modelId() {
        return config.id == null || config.id.isEmpty() ? "onnx-age" : config.id;
    }

    @Override
    public AgeVerdict estimate(Bitmap alignedFace, float[] embedding, FaceQuality quality) {
        OrtSession s = session;
        if (s == null) return AgeVerdict.unknown("age-model-not-loaded", quality, modelId());
        try {
            Bitmap resized = Bitmap.createScaledBitmap(alignedFace, inputWidth, inputHeight, true);
            preprocess(resized);
            if (resized != alignedFace) resized.recycle();
            long[] shape = nchw
                    ? new long[] {1, 3, inputHeight, inputWidth}
                    : new long[] {1, inputHeight, inputWidth, 3};
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
                 OrtSession.Result result = s.run(Collections.singletonMap(inputName, tensor))) {
                OnnxValue value = result.get(0);
                if (value == null) throw new IllegalStateException("No age output");
                return parseOutput(OnnxFaceEmbedder.flatten(value.getValue()), quality);
            }
        } catch (Exception e) {
            Log.w(TAG, modelId() + " age inference failed: " + e.getMessage());
            return AgeVerdict.unknown("age-inference-failed", quality, modelId());
        }
    }

    private AgeVerdict parseOutput(float[] output, FaceQuality quality) {
        if (output.length >= 101) {
            float[] probs = normalized(output);
            float pMinor = 0f;
            float expected = 0f;
            for (int i = 0; i < probs.length; i++) {
                if (i < 18) pMinor += probs[i];
                expected += i * probs[i];
            }
            return AgeDecisionPolicy.fromAgeEstimate(expected, quality, modelId() + ":pMinor=" + pMinor);
        }
        if (output.length >= 6 && output.length < 32) {
            return AgeDecisionPolicy.fromBuckets(normalized(output), quality, modelId());
        }
        Float age = null;
        if (output.length >= 3 && "genderage".equalsIgnoreCase(config.type)) {
            age = output[2] <= 1.5f ? output[2] * 100f : output[2];
        } else if (output.length >= 1) {
            age = output[0] <= 1.5f ? output[0] * 100f : output[0];
        }
        return AgeDecisionPolicy.fromAgeEstimate(age, quality, modelId());
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
            if ("minus_one_to_one".equalsIgnoreCase(config.normalization)) {
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

    private static float[] normalized(float[] values) {
        float sum = 0f;
        boolean allPositive = true;
        for (float value : values) {
            sum += value;
            allPositive = allPositive && value >= 0f;
        }
        if (allPositive && sum > 0.98f && sum < 1.02f) return values;
        float max = Float.NEGATIVE_INFINITY;
        for (float value : values) max = Math.max(max, value);
        float denom = 0f;
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) Math.exp(values[i] - max);
            denom += out[i];
        }
        if (denom <= 0f) return values;
        for (int i = 0; i < out.length; i++) out[i] /= denom;
        return out;
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
