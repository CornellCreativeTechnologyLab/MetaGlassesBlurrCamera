package com.ctl.metaglasses.vision.pipeline;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Manifest-driven model metadata. Missing assets are expected during staged rollout. */
public final class ModelManifest {
    private static final String TAG = "ModelManifest";
    private static final String ASSET_PATH = "models/model_manifest.json";

    public final List<ModelConfig> models;
    public final float embeddingCosineThreshold;
    public final float iouThreshold;
    public final int staleTrackFrames;
    public final float ageQualityRefreshDelta;
    public final int ageRefreshFrames;

    private ModelManifest(
            List<ModelConfig> models,
            float embeddingCosineThreshold,
            float iouThreshold,
            int staleTrackFrames,
            float ageQualityRefreshDelta,
            int ageRefreshFrames
    ) {
        this.models = Collections.unmodifiableList(models);
        this.embeddingCosineThreshold = embeddingCosineThreshold;
        this.iouThreshold = iouThreshold;
        this.staleTrackFrames = staleTrackFrames;
        this.ageQualityRefreshDelta = ageQualityRefreshDelta;
        this.ageRefreshFrames = ageRefreshFrames;
    }

    public static ModelManifest load(Context context) {
        try (InputStream in = context.getAssets().open(ASSET_PATH)) {
            byte[] bytes = new byte[in.available()];
            int read = in.read(bytes);
            return parse(new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "No model manifest; using fallback config. " + e.getMessage());
            return fallback();
        }
    }

    public static ModelManifest parse(String json) throws Exception {
        List<ModelConfig> models = new ArrayList<>();
        String modelsArray = findArray(json, "models");
        for (String object : objectsInArray(modelsArray)) {
            models.add(ModelConfig.fromJson(object));
        }
        String tracking = findObject(json, "tracking");
        String ageCache = findObject(json, "ageCache");
        return new ModelManifest(
                models,
                optFloat(tracking, "embeddingCosineThreshold", 0.38f),
                optFloat(tracking, "iouThreshold", 0.30f),
                optInt(tracking, "staleTrackFrames", 45),
                optFloat(ageCache, "qualityRefreshDelta", 0.10f),
                optInt(ageCache, "refreshFrames", 60)
        );
    }

    public static ModelManifest fallback() {
        return new ModelManifest(new ArrayList<>(), 0.38f, 0.30f, 45, 0.10f, 60);
    }

    public ModelConfig firstEnabled(String role, String type) {
        for (ModelConfig config : models) {
            if (config.enabled && role.equals(config.role) && type.equals(config.type)) return config;
        }
        return null;
    }

    public ModelConfig firstEnabledRole(String role) {
        for (ModelConfig config : models) {
            if (config.enabled && role.equals(config.role)) return config;
        }
        return null;
    }

    private static float optFloat(String json, String key, float fallback) {
        if (json == null) return fallback;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Float.parseFloat(m.group(1)) : fallback;
    }

    private static int optInt(String json, String key, int fallback) {
        if (json == null) return fallback;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static boolean optBoolean(String json, String key, boolean fallback) {
        if (json == null) return fallback;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : fallback;
    }

    private static String optString(String json, String key, String fallback) {
        if (json == null) return fallback;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : fallback;
    }

    private static String findObject(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) return null;
        int start = json.indexOf('{', keyIndex);
        if (start < 0) return null;
        int end = matching(json, start, '{', '}');
        return end > start ? json.substring(start, end + 1) : null;
    }

    private static String findArray(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) return "";
        int start = json.indexOf('[', keyIndex);
        if (start < 0) return "";
        int end = matching(json, start, '[', ']');
        return end > start ? json.substring(start + 1, end) : "";
    }

    private static List<String> objectsInArray(String array) {
        List<String> objects = new ArrayList<>();
        int i = 0;
        while (i < array.length()) {
            int start = array.indexOf('{', i);
            if (start < 0) break;
            int end = matching(array, start, '{', '}');
            if (end <= start) break;
            objects.add(array.substring(start, end + 1));
            i = end + 1;
        }
        return objects;
    }

    private static int matching(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == open) depth++;
            if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public static final class ModelConfig {
        public final String id;
        public final String role;
        public final String type;
        public final String assetPath;
        public final boolean enabled;
        public final int inputWidth;
        public final int inputHeight;
        public final String inputLayout;
        public final String normalization;
        public final int embeddingDim;
        public final float threshold;
        public final float weight;

        private ModelConfig(
                String id,
                String role,
                String type,
                String assetPath,
                boolean enabled,
                int inputWidth,
                int inputHeight,
                String inputLayout,
                String normalization,
                int embeddingDim,
                float threshold,
                float weight
        ) {
            this.id = id;
            this.role = role;
            this.type = type;
            this.assetPath = assetPath;
            this.enabled = enabled;
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.inputLayout = inputLayout;
            this.normalization = normalization;
            this.embeddingDim = embeddingDim;
            this.threshold = threshold;
            this.weight = weight;
        }

        static ModelConfig fromJson(String o) {
            return new ModelConfig(
                    optString(o, "id", ""),
                    optString(o, "role", ""),
                    optString(o, "type", ""),
                    optString(o, "assetPath", ""),
                    optBoolean(o, "enabled", false),
                    optInt(o, "inputWidth", 112),
                    optInt(o, "inputHeight", 112),
                    optString(o, "inputLayout", "NCHW"),
                    optString(o, "normalization", "zero_to_one"),
                    optInt(o, "embeddingDim", 0),
                    optFloat(o, "threshold", 0.5f),
                    optFloat(o, "weight", 1.0f)
            );
        }
    }
}
