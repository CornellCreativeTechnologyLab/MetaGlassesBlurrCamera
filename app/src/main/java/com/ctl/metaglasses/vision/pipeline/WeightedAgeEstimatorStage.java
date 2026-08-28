package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

import com.ctl.metaglasses.vision.AgeLabel;
import com.ctl.metaglasses.vision.AgeVerdict;
import com.ctl.metaglasses.vision.FaceQuality;

import java.util.ArrayList;
import java.util.List;

/** Weighted age-voting layer across existing and ONNX age estimators. */
public final class WeightedAgeEstimatorStage implements AgeEstimatorStage {
    private final List<Entry> entries = new ArrayList<>();

    public void add(AgeEstimatorStage estimator, float weight) {
        if (estimator != null && estimator.isLoaded()) {
            entries.add(new Entry(estimator, weight > 0f ? weight : 1f));
        } else if (estimator != null) {
            estimator.close();
        }
    }

    @Override
    public boolean isLoaded() {
        return !entries.isEmpty();
    }

    @Override
    public String modelId() {
        StringBuilder sb = new StringBuilder("age-vote:");
        for (Entry entry : entries) {
            if (sb.length() > "age-vote:".length()) sb.append("+");
            sb.append(entry.estimator.modelId());
        }
        return sb.toString();
    }

    @Override
    public AgeVerdict estimate(Bitmap alignedFace, float[] embedding, FaceQuality quality) {
        if (entries.isEmpty()) return AgeVerdict.unknown("no-age-estimator-loaded", quality, "age-vote");

        List<AgeVerdict> verdicts = new ArrayList<>();
        float totalWeight = 0f;
        float weightedMinorRisk = 0f;
        for (Entry entry : entries) {
            AgeVerdict verdict = entry.estimator.estimate(alignedFace, embedding, quality);
            verdicts.add(verdict);
            totalWeight += entry.weight;
            weightedMinorRisk += entry.weight * minorRisk(verdict);
        }
        weightedMinorRisk = totalWeight > 0f ? clamp(weightedMinorRisk / totalWeight) : 1f;

        int minor = 0;
        int unknown = 0;
        int adult = 0;
        Float displayAge = null;
        List<String> reasons = new ArrayList<>();
        for (AgeVerdict verdict : verdicts) {
            if (verdict.getLabel() == AgeLabel.MINOR) minor++;
            else if (verdict.getLabel() == AgeLabel.ADULT_CONFIRMED) adult++;
            else unknown++;
            Float age = verdict.getEstimatedAge();
            if (age != null && !age.isNaN()) {
                displayAge = displayAge == null ? age : Math.max(displayAge, age);
            }
            reasons.addAll(verdict.getReasons());
        }

        AgeLabel label;
        if (minor > 0) label = AgeLabel.MINOR;
        else if (unknown > 0) label = AgeLabel.UNKNOWN;
        else if (adult == entries.size() && weightedMinorRisk <= 0.01f) label = AgeLabel.ADULT_CONFIRMED;
        else label = AgeLabel.UNKNOWN;

        reasons.add("age-votes:" + minor + " minor/" + unknown + " unknown/" + adult + " adult");
        return new AgeVerdict(label, weightedMinorRisk, displayAge, quality.getScore(), reasons, modelId());
    }

    private static float minorRisk(AgeVerdict verdict) {
        float p = verdict.getPMinor();
        if (verdict.getLabel() == AgeLabel.ADULT_CONFIRMED) return clamp(p);
        return clamp(Math.max(0.5f, p));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        for (Entry entry : entries) entry.estimator.close();
        entries.clear();
    }

    private static final class Entry {
        final AgeEstimatorStage estimator;
        final float weight;

        Entry(AgeEstimatorStage estimator, float weight) {
            this.estimator = estimator;
            this.weight = weight;
        }
    }
}
