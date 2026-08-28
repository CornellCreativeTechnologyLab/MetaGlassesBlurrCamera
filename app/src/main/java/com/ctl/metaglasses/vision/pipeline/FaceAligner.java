package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/** Produces a normalized face crop for embedding/age models. */
public final class FaceAligner {
    public static final int DEFAULT_SIZE = 112;
    private static final float PAD = 0.25f;

    private FaceAligner() {}

    public static Bitmap align(Bitmap frame, FaceCandidate candidate) {
        if (candidate.landmarks != null && candidate.landmarks.length >= 10) {
            Bitmap aligned = Bitmap.createBitmap(DEFAULT_SIZE, DEFAULT_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(aligned);
            Matrix matrix = new Matrix();
            float[] src = new float[] {
                    candidate.landmarks[0], candidate.landmarks[1],
                    candidate.landmarks[2], candidate.landmarks[3],
                    candidate.landmarks[6], candidate.landmarks[7],
                    candidate.landmarks[8], candidate.landmarks[9]
            };
            float[] dst = new float[] {
                    38.2946f, 51.6963f,
                    73.5318f, 51.5014f,
                    41.5493f, 92.3655f,
                    70.7299f, 92.2041f
            };
            if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(frame, matrix, paint);
                return aligned;
            }
            aligned.recycle();
        }
        FaceBox crop = candidate.box.pad(PAD, frame.getWidth(), frame.getHeight());
        Bitmap raw = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height());
        Bitmap resized = Bitmap.createScaledBitmap(raw, DEFAULT_SIZE, DEFAULT_SIZE, true);
        if (raw != resized) raw.recycle();
        return resized;
    }
}
