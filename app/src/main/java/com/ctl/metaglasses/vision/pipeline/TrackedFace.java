package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;

import com.ctl.metaglasses.vision.AgeVerdict;

public final class TrackedFace {
    public final int personId;
    public final FaceBox box;
    public final Bitmap thumbnail;
    public final AgeVerdict ageVerdict;
    public final float qualityScore;
    public final float detectionConfidence;

    TrackedFace(
            int personId,
            FaceBox box,
            Bitmap thumbnail,
            AgeVerdict ageVerdict,
            float qualityScore,
            float detectionConfidence
    ) {
        this.personId = personId;
        this.box = box;
        this.thumbnail = thumbnail;
        this.ageVerdict = ageVerdict;
        this.qualityScore = qualityScore;
        this.detectionConfidence = detectionConfidence;
    }
}
