package com.ctl.metaglasses.vision.pipeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.ctl.metaglasses.vision.AgeClassifier;
import com.ctl.metaglasses.vision.AgeVerdict;
import com.ctl.metaglasses.vision.FaceQuality;

import java.util.ArrayList;
import java.util.List;

/** Gallery/offline-first face identity + age pipeline. */
public final class GalleryFacePipeline implements AutoCloseable {
    private static final String TAG = "GalleryFacePipeline";
    private static final int OFFLINE_MIN_FACE_SHORT_SIDE = 128;
    private static final float THUMB_PADDING = 0.25f;

    private final FaceDetectorStage primaryDetector;
    private final FaceDetectorStage fallbackDetector;
    private final FaceEmbedder embedder;
    private final FaceEmbedder fallbackEmbedder;
    private final FaceEmbedder trackingEmbedder;
    private final AgeEstimatorStage ageEstimator;
    private final PersonTracker tracker;
    private int frameIndex = 0;

    public GalleryFacePipeline(Context context, AgeClassifier ageClassifier) {
        ModelManifest manifest = ModelManifest.load(context);
        this.primaryDetector = detector(context, manifest);
        this.fallbackDetector = new MlKitFaceDetectorStage();
        this.embedder = embedder(context, manifest);
        this.fallbackEmbedder = new HashFaceEmbedder();
        this.trackingEmbedder = trackingEmbedder(context, manifest);
        this.ageEstimator = ageEstimator(context, manifest, ageClassifier);
        this.tracker = new PersonTracker(manifest);
        Log.i(TAG, "detector=" + detectorName() + " embedder=" + embedderName()
                + " tracker=" + trackingEmbedderName() + " age=" + ageEstimator.modelId());
    }

    public List<TrackedFace> processFrame(Bitmap frame) {
        frameIndex++;
        List<FaceCandidate> candidates = detect(frame);
        List<TrackedFace> out = new ArrayList<>(candidates.size());
        for (FaceCandidate candidate : candidates) {
            Bitmap aligned = null;
            try {
                aligned = FaceAligner.align(frame, candidate);
                FaceQuality quality = FaceQuality.evaluate(aligned, OFFLINE_MIN_FACE_SHORT_SIDE);
                float[] embedding = trackingVector(frame, candidate, aligned);
                PersonTracker.Track track = tracker.assign(candidate.box, embedding, quality.getScore(), frameIndex);
                if (tracker.shouldRefreshAge(track, quality.getScore(), frameIndex)) {
                    AgeVerdict verdict = ageEstimator.estimate(aligned, embedding, quality);
                    track.updateAge(verdict, quality.getScore(), frameIndex);
                }
                track.updateThumbnail(thumbnail(frame, candidate.box), quality.getScore());
                out.add(track.snapshot(candidate.confidence));
            } catch (Exception e) {
                Log.w(TAG, "face pipeline failed for candidate: " + e.getMessage());
            } finally {
                if (aligned != null && !aligned.isRecycled()) aligned.recycle();
            }
        }
        return out;
    }

    public List<TrackedFace> knownPeople() {
        List<TrackedFace> out = new ArrayList<>();
        for (PersonTracker.Track track : tracker.tracks()) {
            out.add(track.snapshot(1f));
        }
        return out;
    }

    private List<FaceCandidate> detect(Bitmap frame) {
        if (primaryDetector != null && primaryDetector.isLoaded()) {
            try {
                List<FaceCandidate> result = primaryDetector.detect(frame);
                if (!result.isEmpty()) return result;
            } catch (Exception e) {
                Log.w(TAG, primaryDetector.modelId() + " failed; using fallback. " + e.getMessage());
            }
        }
        try {
            return fallbackDetector.detect(frame);
        } catch (Exception e) {
            Log.w(TAG, "fallback detector failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private float[] embed(Bitmap alignedFace) throws Exception {
        if (embedder != null && embedder.isLoaded()) {
            try {
                return embedder.embed(alignedFace);
            } catch (Exception e) {
                Log.w(TAG, embedder.modelId() + " failed; using fallback embedding. " + e.getMessage());
            }
        }
        return fallbackEmbedder.embed(alignedFace);
    }

    private float[] trackingVector(Bitmap frame, FaceCandidate candidate, Bitmap alignedFace) throws Exception {
        float[] faceEmbedding = embed(alignedFace);
        if (trackingEmbedder == null || !trackingEmbedder.isLoaded()) return faceEmbedding;

        Bitmap crop = null;
        try {
            FaceBox padded = candidate.box.pad(0.40f, frame.getWidth(), frame.getHeight());
            crop = Bitmap.createBitmap(frame, padded.left, padded.top, padded.width(), padded.height());
            float[] reid = trackingEmbedder.embed(crop);
            return concatenateNormalized(faceEmbedding, reid, 0.7f, 0.3f);
        } catch (Exception e) {
            Log.w(TAG, trackingEmbedder.modelId() + " failed; using face embedding only. " + e.getMessage());
            return faceEmbedding;
        } finally {
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    private static float[] concatenateNormalized(float[] a, float[] b, float wa, float wb) {
        if (a == null || a.length == 0) return b;
        if (b == null || b.length == 0) return a;
        float[] out = new float[a.length + b.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] * wa;
        for (int i = 0; i < b.length; i++) out[a.length + i] = b[i] * wb;
        OnnxFaceEmbedder.l2Normalize(out);
        return out;
    }

    private Bitmap thumbnail(Bitmap frame, FaceBox box) {
        FaceBox crop = box.pad(THUMB_PADDING, frame.getWidth(), frame.getHeight());
        return Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height());
    }

    private static FaceDetectorStage detector(Context context, ModelManifest manifest) {
        ModelManifest.ModelConfig scrfd = manifest.firstEnabled("detector", "scrfd");
        if (scrfd != null) {
            ScrfdOnnxFaceDetectorStage detector = new ScrfdOnnxFaceDetectorStage(context, scrfd);
            if (detector.isLoaded()) return detector;
            detector.close();
        }
        return null;
    }

    private static FaceEmbedder embedder(Context context, ModelManifest manifest) {
        ModelManifest.ModelConfig config = manifest.firstEnabledRole("embedder");
        if (config == null || "hash-fallback".equals(config.type)) return null;
        OnnxFaceEmbedder embedder = new OnnxFaceEmbedder(context, config);
        if (embedder.isLoaded()) return embedder;
        embedder.close();
        return null;
    }

    private static FaceEmbedder trackingEmbedder(Context context, ModelManifest manifest) {
        ModelManifest.ModelConfig config = manifest.firstEnabledRole("tracker");
        if (config == null) return null;
        OnnxFaceEmbedder embedder = new OnnxFaceEmbedder(context, config);
        if (embedder.isLoaded()) return embedder;
        embedder.close();
        return null;
    }

    private static AgeEstimatorStage ageEstimator(
            Context context,
            ModelManifest manifest,
            AgeClassifier ageClassifier
    ) {
        WeightedAgeEstimatorStage vote = new WeightedAgeEstimatorStage();
        vote.add(new ExistingAgeEstimatorStage(ageClassifier), 1f);
        for (ModelManifest.ModelConfig config : manifest.models) {
            if (!config.enabled || !"age".equals(config.role)) continue;
            vote.add(new OnnxAgeEstimatorStage(context, config), config.weight);
        }
        return vote;
    }

    private String detectorName() {
        return primaryDetector != null && primaryDetector.isLoaded()
                ? primaryDetector.modelId()
                : fallbackDetector.modelId();
    }

    private String embedderName() {
        return embedder != null && embedder.isLoaded()
                ? embedder.modelId()
                : fallbackEmbedder.modelId();
    }

    private String trackingEmbedderName() {
        return trackingEmbedder != null && trackingEmbedder.isLoaded()
                ? trackingEmbedder.modelId()
                : "none";
    }

    @Override
    public void close() {
        try { if (primaryDetector != null) primaryDetector.close(); } catch (Exception ignored) {}
        try { fallbackDetector.close(); } catch (Exception ignored) {}
        try { if (embedder != null) embedder.close(); } catch (Exception ignored) {}
        try { fallbackEmbedder.close(); } catch (Exception ignored) {}
        try { if (trackingEmbedder != null) trackingEmbedder.close(); } catch (Exception ignored) {}
        try { ageEstimator.close(); } catch (Exception ignored) {}
    }
}
