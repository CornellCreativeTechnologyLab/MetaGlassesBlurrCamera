package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

import com.ctl.metaglasses.vision.AgeLabel;
import com.ctl.metaglasses.vision.AgeVerdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DeepSORT-style identity cache: cosine embedding match first, IoU motion match second. */
public final class PersonTracker {
    private final float embeddingThreshold;
    private final float iouThreshold;
    private final int staleTrackFrames;
    private final float ageQualityRefreshDelta;
    private final int ageRefreshFrames;
    private final Map<Integer, Track> tracks = new LinkedHashMap<>();
    private int nextId = 1;

    public PersonTracker(ModelManifest manifest) {
        this(
                manifest.embeddingCosineThreshold,
                manifest.iouThreshold,
                manifest.staleTrackFrames,
                manifest.ageQualityRefreshDelta,
                manifest.ageRefreshFrames
        );
    }

    public PersonTracker(
            float embeddingThreshold,
            float iouThreshold,
            int staleTrackFrames,
            float ageQualityRefreshDelta,
            int ageRefreshFrames
    ) {
        this.embeddingThreshold = embeddingThreshold;
        this.iouThreshold = iouThreshold;
        this.staleTrackFrames = staleTrackFrames;
        this.ageQualityRefreshDelta = ageQualityRefreshDelta;
        this.ageRefreshFrames = ageRefreshFrames;
    }

    public Track assign(FaceBox box, float[] embedding, float qualityScore, int frameIndex) {
        Track best = null;
        float bestScore = -1f;
        for (Track track : tracks.values()) {
            float cosine = cosine(embedding, track.embedding);
            boolean active = frameIndex - track.lastSeenFrame <= staleTrackFrames;
            float iou = active ? box.iou(track.box) : 0f;
            if (cosine >= embeddingThreshold && cosine > bestScore) {
                bestScore = cosine;
                best = track;
            } else if (best == null && active && iou >= iouThreshold && iou > bestScore) {
                bestScore = iou;
                best = track;
            }
        }
        if (best == null) {
            best = new Track(nextId++, box, embedding, qualityScore, frameIndex);
            tracks.put(best.personId, best);
        } else {
            best.update(box, embedding, qualityScore, frameIndex);
        }
        return best;
    }

    public boolean shouldRefreshAge(Track track, float qualityScore, int frameIndex) {
        return track.ageVerdict == null
                || qualityScore >= track.ageQualityScore + ageQualityRefreshDelta
                || frameIndex - track.lastAgeFrame >= ageRefreshFrames;
    }

    public List<Track> tracks() {
        return new ArrayList<>(tracks.values());
    }

    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return -1f;
        int n = Math.min(a.length, b.length);
        float dot = 0f;
        float an = 0f;
        float bn = 0f;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            an += a[i] * a[i];
            bn += b[i] * b[i];
        }
        if (an <= 0f || bn <= 0f) return -1f;
        return (float) (dot / Math.sqrt(an * bn));
    }

    public static final class Track {
        public final int personId;
        FaceBox box;
        float[] embedding;
        int lastSeenFrame;
        float bestQualityScore;
        float ageQualityScore;
        int lastAgeFrame = Integer.MIN_VALUE / 2;
        AgeVerdict ageVerdict;
        Bitmap thumbnail;

        Track(int personId, FaceBox box, float[] embedding, float qualityScore, int frameIndex) {
            this.personId = personId;
            this.box = box;
            this.embedding = embedding;
            this.bestQualityScore = qualityScore;
            this.lastSeenFrame = frameIndex;
        }

        void update(FaceBox box, float[] embedding, float qualityScore, int frameIndex) {
            this.box = box;
            this.lastSeenFrame = frameIndex;
            this.embedding = blend(this.embedding, embedding);
            if (qualityScore > bestQualityScore) bestQualityScore = qualityScore;
        }

        public void updateAge(AgeVerdict verdict, float qualityScore, int frameIndex) {
            this.ageVerdict = verdict;
            this.ageQualityScore = qualityScore;
            this.lastAgeFrame = frameIndex;
        }

        public void updateThumbnail(Bitmap bitmap, float qualityScore) {
            if (thumbnail == null || qualityScore >= bestQualityScore) {
                if (thumbnail != null && thumbnail != bitmap && !thumbnail.isRecycled()) thumbnail.recycle();
                thumbnail = bitmap;
                bestQualityScore = qualityScore;
            } else if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }

        public TrackedFace snapshot(float detectionConfidence) {
            AgeVerdict verdict = ageVerdict != null
                    ? ageVerdict
                    : AgeVerdict.unknown("age-not-yet-estimated", null, "person-tracker");
            return new TrackedFace(
                    personId,
                    box,
                    thumbnail,
                    verdict,
                    ageQualityScore,
                    detectionConfidence
            );
        }

        private static float[] blend(float[] oldValue, float[] newValue) {
            if (oldValue == null || oldValue.length == 0) return newValue;
            if (newValue == null || newValue.length == 0) return oldValue;
            int n = Math.min(oldValue.length, newValue.length);
            float[] out = new float[n];
            float norm = 0f;
            for (int i = 0; i < n; i++) {
                out[i] = oldValue[i] * 0.8f + newValue[i] * 0.2f;
                norm += out[i] * out[i];
            }
            norm = (float) Math.sqrt(Math.max(norm, 1e-6f));
            for (int i = 0; i < n; i++) out[i] /= norm;
            return out;
        }
    }
}
