package com.ctl.metaglasses.vision.pipeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ModelManifestTest {
    @Test
    public void parsesManifestThresholdsAndModels() throws Exception {
        String json = "{"
                + "\"tracking\":{\"embeddingCosineThreshold\":0.38,\"iouThreshold\":0.30,\"staleTrackFrames\":45},"
                + "\"ageCache\":{\"qualityRefreshDelta\":0.10,\"refreshFrames\":60},"
                + "\"models\":[{\"id\":\"scrfd\",\"role\":\"detector\",\"type\":\"scrfd\","
                + "\"assetPath\":\"models/detection/scrfd.onnx\",\"enabled\":true,"
                + "\"inputWidth\":640,\"inputHeight\":640,\"threshold\":0.5}]"
                + "}";

        ModelManifest manifest = ModelManifest.parse(json);

        assertEquals(0.38f, manifest.embeddingCosineThreshold, 0.001f);
        assertEquals(0.30f, manifest.iouThreshold, 0.001f);
        assertEquals(45, manifest.staleTrackFrames);
        assertEquals(60, manifest.ageRefreshFrames);
        assertNotNull(manifest.firstEnabled("detector", "scrfd"));
        assertEquals("models/detection/scrfd.onnx", manifest.firstEnabled("detector", "scrfd").assetPath);
    }
}
