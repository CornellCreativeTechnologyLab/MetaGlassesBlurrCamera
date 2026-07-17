package com.ctl.metaglasses.vision;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * Renders a {@link FaceMap} onto a copy of the original bitmap by pixelating every face that is
 * currently flagged as blurred. Mirrors the mosaic style of the live {@code FaceBlurProcessor} so
 * gallery edits look identical to what the glasses produced.
 */
public final class BlurRenderer {

    private static final int MOSAIC = 30; // larger = coarser blocks (matches FaceBlurProcessor)

    private BlurRenderer() {}

    /** Returns a new ARGB_8888 bitmap: [original] with each blurred face in [map] pixelated. */
    public static Bitmap render(Bitmap original, FaceMap map) {
        Bitmap out = original.copy(Bitmap.Config.ARGB_8888, true);
        if (map == null) return out;
        Canvas canvas = new Canvas(out);
        for (FaceMap.Face f : map.faces) {
            if (!f.blurred) continue;
            Rect box = clamp(f.rect(), out.getWidth(), out.getHeight());
            if (box != null) pixelate(out, canvas, box);
        }
        return out;
    }

    private static Rect clamp(Rect r, int w, int h) {
        int left = Math.max(0, Math.min(r.left, w - 1));
        int top = Math.max(0, Math.min(r.top, h - 1));
        int right = Math.max(left + 1, Math.min(r.right, w));
        int bottom = Math.max(top + 1, Math.min(r.bottom, h));
        return (right > left && bottom > top) ? new Rect(left, top, right, bottom) : null;
    }

    private static void pixelate(Bitmap bitmap, Canvas canvas, Rect box) {
        int w = box.width();
        int h = box.height();
        Bitmap crop = Bitmap.createBitmap(bitmap, box.left, box.top, w, h);
        Bitmap small = Bitmap.createScaledBitmap(
                crop, Math.max(1, w / MOSAIC), Math.max(1, h / MOSAIC), false);
        Bitmap mosaic = Bitmap.createScaledBitmap(small, w, h, false);
        canvas.drawBitmap(mosaic, box.left, box.top, null);
        crop.recycle();
        small.recycle();
        mosaic.recycle();
    }
}
