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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SCRFD detector adapter. It supports the common score/bbox/kps output family. */
public final class ScrfdOnnxFaceDetectorStage implements FaceDetectorStage {
    private static final String TAG = "ScrfdOnnxDetector";
    private static final float NMS_IOU = 0.40f;

    private final ModelManifest.ModelConfig config;
    private final OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private int inputWidth;
    private int inputHeight;
    private float[] input;

    public ScrfdOnnxFaceDetectorStage(Context context, ModelManifest.ModelConfig config) {
        this.config = config;
        this.env = OrtEnvironment.getEnvironment();
        try {
            byte[] bytes = AssetIO.readAsset(context, config.assetPath);
            session = env.createSession(bytes, new OrtSession.SessionOptions());
            Map.Entry<String, NodeInfo> inputInfo = session.getInputInfo().entrySet().iterator().next();
            inputName = inputInfo.getKey();
            long[] shape = ((TensorInfo) inputInfo.getValue().getInfo()).getShape();
            inputHeight = config.inputHeight > 0 ? config.inputHeight : dim(shape, 2, 640);
            inputWidth = config.inputWidth > 0 ? config.inputWidth : dim(shape, 3, 640);
            input = new float[3 * inputWidth * inputHeight];
            Log.i(TAG, "Loaded " + config.assetPath + " input=" + inputWidth + "x" + inputHeight);
        } catch (Throwable t) {
            Log.w(TAG, "SCRFD unavailable; falling back. " + t.getMessage());
            session = null;
        }
    }

    @Override
    public boolean isLoaded() {
        return session != null;
    }

    @Override
    public String modelId() {
        return config.id == null || config.id.isEmpty() ? "scrfd" : config.id;
    }

    @Override
    public List<FaceCandidate> detect(Bitmap frame) throws Exception {
        OrtSession s = session;
        if (s == null) return Collections.emptyList();
        Bitmap resized = Bitmap.createScaledBitmap(frame, inputWidth, inputHeight, true);
        preprocess(resized);
        if (resized != frame) resized.recycle();

        long[] shape = new long[] {1, 3, inputHeight, inputWidth};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = s.run(Collections.singletonMap(inputName, tensor))) {
            List<Output> outputs = new ArrayList<>();
            int i = 0;
            for (String name : s.getOutputNames()) {
                OnnxValue value = result.get(i++);
                if (value == null) continue;
                Object raw = value.getValue();
                long[] outShape = outputShape(s, name);
                outputs.add(new Output(name, flatten(raw), outShape));
            }
            return nms(decode(outputs, frame.getWidth(), frame.getHeight()));
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
            input[i] = (r - 127.5f) / 128f;
            input[plane + i] = (g - 127.5f) / 128f;
            input[2 * plane + i] = (b - 127.5f) / 128f;
        }
    }

    private List<FaceCandidate> decode(List<Output> outputs, int frameWidth, int frameHeight) {
        List<FaceCandidate> candidates = new ArrayList<>();
        Map<Integer, OutputSet> sets = groupOutputs(outputs);
        float sx = frameWidth / (float) inputWidth;
        float sy = frameHeight / (float) inputHeight;
        for (Map.Entry<Integer, OutputSet> entry : sets.entrySet()) {
            int stride = entry.getKey();
            OutputSet set = entry.getValue();
            if (set.score == null || set.bbox == null) continue;
            int featW = inputWidth / stride;
            int featH = inputHeight / stride;
            int anchors = Math.max(1, set.score.count() / Math.max(1, featW * featH));
            int count = Math.min(set.score.count(), set.bbox.count() / 4);
            for (int i = 0; i < count; i++) {
                float score = set.score.values[i];
                if (score < config.threshold) continue;
                int anchorIndex = i / anchors;
                int y = anchorIndex / featW;
                int x = anchorIndex % featW;
                float cx = (x + 0.5f) * stride;
                float cy = (y + 0.5f) * stride;
                int b = i * 4;
                float left = (cx - set.bbox.values[b] * stride) * sx;
                float top = (cy - set.bbox.values[b + 1] * stride) * sy;
                float right = (cx + set.bbox.values[b + 2] * stride) * sx;
                float bottom = (cy + set.bbox.values[b + 3] * stride) * sy;
                float[] landmarks = null;
                if (set.kps != null && set.kps.values.length >= i * 10 + 10) {
                    landmarks = new float[10];
                    int k = i * 10;
                    for (int p = 0; p < 5; p++) {
                        landmarks[p * 2] = (cx + set.kps.values[k + p * 2] * stride) * sx;
                        landmarks[p * 2 + 1] = (cy + set.kps.values[k + p * 2 + 1] * stride) * sy;
                    }
                }
                FaceBox box = new FaceBox(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom))
                        .clamp(frameWidth, frameHeight);
                candidates.add(new FaceCandidate(box, landmarks, score));
            }
        }
        return candidates;
    }

    private Map<Integer, OutputSet> groupOutputs(List<Output> outputs) {
        Map<Integer, OutputSet> sets = new LinkedHashMap<>();
        int[] strides = new int[] {8, 16, 32};
        for (Output output : outputs) {
            int stride = strideFor(output, strides);
            OutputSet set = sets.computeIfAbsent(stride, k -> new OutputSet());
            String name = output.name.toLowerCase();
            int last = output.lastDim();
            if (name.contains("score") || last == 1) set.score = output;
            else if (name.contains("bbox") || last == 4) set.bbox = output;
            else if (name.contains("kps") || name.contains("landmark") || last == 10) set.kps = output;
        }
        return sets;
    }

    private int strideFor(Output output, int[] strides) {
        String name = output.name.toLowerCase();
        for (int stride : strides) {
            if (name.contains(String.valueOf(stride))) return stride;
        }
        int count = Math.max(1, output.count());
        for (int stride : strides) {
            int cells = (inputWidth / stride) * (inputHeight / stride);
            if (count == cells || count == cells * 2) return stride;
        }
        return 8;
    }

    private List<FaceCandidate> nms(List<FaceCandidate> input) {
        input.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        List<FaceCandidate> keep = new ArrayList<>();
        for (FaceCandidate candidate : input) {
            boolean suppressed = false;
            for (FaceCandidate kept : keep) {
                if (candidate.box.iou(kept.box) > NMS_IOU) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) keep.add(candidate);
        }
        keep.sort(Comparator.comparingInt(a -> a.box.left));
        return keep;
    }

    private long[] outputShape(OrtSession s, String name) {
        try {
            return ((TensorInfo) s.getOutputInfo().get(name).getInfo()).getShape();
        } catch (Exception e) {
            return new long[0];
        }
    }

    private static int dim(long[] shape, int index, int fallback) {
        return shape.length > index && shape[index] > 0 ? (int) shape[index] : fallback;
    }

    private static float[] flatten(Object value) {
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

    @Override
    public void close() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
    }

    private static final class Output {
        final String name;
        final float[] values;
        final long[] shape;

        Output(String name, float[] values, long[] shape) {
            this.name = name;
            this.values = values;
            this.shape = shape;
        }

        int count() {
            int last = lastDim();
            return last <= 0 ? values.length : values.length / last;
        }

        int lastDim() {
            return shape.length == 0 ? 0 : (int) shape[shape.length - 1];
        }
    }

    private static final class OutputSet {
        Output score;
        Output bbox;
        Output kps;
    }
}
