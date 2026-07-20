package com.ctl.metaglasses.vision;

import android.graphics.Rect;

/**
 * A detected face to blur: its padded bounding box plus, when available, the real face-outline
 * contour so the blur can hug the face shape instead of filling a square.
 *
 * [contour] is a flattened list of polygon points {x0, y0, x1, y1, ...} in the image's pixel space
 * (same space as [box]), or null when the detector didn't return a contour — in which case callers
 * fall back to an oval inscribed in [box].
 */
public class FaceRegion {

    public final Rect box;
    public final float[] contour;

    public FaceRegion(Rect box, float[] contour) {
        this.box = box;
        this.contour = contour;
    }
}
