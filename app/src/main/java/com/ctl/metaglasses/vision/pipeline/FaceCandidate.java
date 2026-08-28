package com.ctl.metaglasses.vision.pipeline;

/** A detector output before identity assignment. Landmarks are x0,y0,... for 5 points when known. */
public final class FaceCandidate {
    public final FaceBox box;
    public final float[] landmarks;
    public final float confidence;

    public FaceCandidate(FaceBox box, float[] landmarks, float confidence) {
        this.box = box;
        this.landmarks = landmarks;
        this.confidence = confidence;
    }
}
