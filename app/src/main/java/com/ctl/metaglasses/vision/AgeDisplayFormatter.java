package com.ctl.metaglasses.vision;

/** Keeps gallery age tags literal and compact. */
public final class AgeDisplayFormatter {
    private AgeDisplayFormatter() {}

    public static String format(Float estimatedAge, float pMinor) {
        boolean predictedMinor = estimatedAge != null && estimatedAge < 18f;
        int confidence = estimatedAge != null
                ? Math.round(Math.min(1f, Math.abs(estimatedAge - 18f) / 7f) * 100f)
                : Math.round((predictedMinor ? pMinor : 1f - pMinor) * 100f);
        return new StringBuilder("Minor: ")
                .append(predictedMinor ? "Yes" : "No")
                .append("\nAge: ")
                .append(estimatedAge == null ? "Unknown" : Math.round(estimatedAge))
                .append("\nConfidence score: ")
                .append(Math.max(0, Math.min(100, confidence)))
                .append("%")
                .toString();
    }
}
