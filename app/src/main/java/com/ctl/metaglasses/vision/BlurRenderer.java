package com.ctl.metaglasses.vision;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Blurs the faces in a {@link FaceMap} onto a copy of the original bitmap.
 *
 * Inspired by mrousavy/FaceBlurApp: detect the face outline, then draw a Gaussian-blurred copy of
 * the frame clipped to that face shape. Instead of a blocky mosaic, we produce a smooth blur that
 * follows the face contour (or an oval when no contour is available) with soft, feathered edges that
 * fade into the sharp background — the same look as a Skia blur-mask.
 *
 * The blur is a fast downscale→bilinear-upscale (a cheap Gaussian approximation that stays smooth
 * because it filters, unlike the old nearest-neighbour mosaic), and the feather comes from a
 * {@link BlurMaskFilter} on the shape mask.
 */
public final class BlurRenderer {

    // Short side (px) of the downscaled crop — smaller = stronger, softer blur.
    private static final int TINY_MIN = 12;
    // Soft-edge width as a fraction of the face's short side.
    private static final float FEATHER_FRAC = 0.12f;
    // Grow the contour a bit so the hairline/edges are covered.
    private static final float EXPAND = 1.12f;

    private BlurRenderer() {}

    /** Returns a new ARGB_8888 bitmap: [original] with each blurred face in [map] softly blurred. */
    public static Bitmap render(Bitmap original, FaceMap map) {
        Bitmap out = original.copy(Bitmap.Config.ARGB_8888, true);
        if (map == null) return out;
        Canvas canvas = new Canvas(out);
        for (FaceMap.Face f : map.faces) {
            if (!f.blurred) continue;
            Rect box = clamp(f.rect(), out.getWidth(), out.getHeight());
            if (box != null) blurRegion(out, canvas, box, f.contour);
        }
        return out;
    }

    /**
     * Softly blurs [bitmap] inside the face shape (contour polygon, or oval in [box] if contour is
     * null), with feathered edges, and composites it over the sharp original on [canvas].
     *
     * Masking order matters: we draw the feathered shape into an offscreen layer FIRST (so alpha
     * exists only within the face), then draw the blurred crop with SRC_IN so it survives only where
     * the shape's alpha is — leaving everything outside untouched. (Drawing the shape last with
     * DST_IN would leave the area outside the path opaque, i.e. a square patch.)
     */
    public static void blurRegion(Bitmap bitmap, Canvas canvas, Rect box, float[] contour) {
        int w = box.width();
        int h = box.height();
        if (w <= 0 || h <= 0) return;

        Bitmap crop = Bitmap.createBitmap(bitmap, box.left, box.top, w, h);

        // Gaussian-ish blur: downscale hard, upscale with bilinear filtering (smooth, not blocky).
        int minSide = Math.min(w, h);
        int factor = Math.max(1, minSide / TINY_MIN);
        int tw = Math.max(1, w / factor);
        int th = Math.max(1, h / factor);
        Bitmap tiny = Bitmap.createScaledBitmap(crop, tw, th, true);
        Bitmap blurred = Bitmap.createScaledBitmap(tiny, w, h, true);

        Path shape = buildPath(box, contour); // absolute (canvas) coordinates
        float feather = Math.max(4f, minSide * FEATHER_FRAC);

        RectF bounds = new RectF(box.left, box.top, box.right, box.bottom);
        int save = canvas.saveLayer(bounds, null);
        // 1) feathered shape defines where (and how softly) the blur shows.
        Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shapePaint.setColor(Color.WHITE);
        shapePaint.setMaskFilter(new BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL));
        canvas.drawPath(shape, shapePaint);
        // 2) blurred crop kept only where the shape's alpha is (feathered edges).
        Paint imgPaint = new Paint();
        imgPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(blurred, box.left, box.top, imgPaint);
        canvas.restoreToCount(save);

        crop.recycle();
        tiny.recycle();
        blurred.recycle();
    }

    /** A closed path for the face: the (slightly expanded) contour if present, else an oval. */
    private static Path buildPath(Rect box, float[] contour) {
        Path path = new Path();
        if (contour != null && contour.length >= 6) {
            int n = contour.length / 2;
            float cx = 0, cy = 0;
            for (int i = 0; i < n; i++) {
                cx += contour[2 * i];
                cy += contour[2 * i + 1];
            }
            cx /= n;
            cy /= n;
            for (int i = 0; i < n; i++) {
                float x = cx + (contour[2 * i] - cx) * EXPAND;
                float y = cy + (contour[2 * i + 1] - cy) * EXPAND;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
        } else {
            path.addOval(new RectF(box.left, box.top, box.right, box.bottom), Path.Direction.CW);
        }
        return path;
    }

    private static Rect clamp(Rect r, int w, int h) {
        int left = Math.max(0, Math.min(r.left, w - 1));
        int top = Math.max(0, Math.min(r.top, h - 1));
        int right = Math.max(left + 1, Math.min(r.right, w));
        int bottom = Math.max(top + 1, Math.min(r.bottom, h));
        return (right > left && bottom > top) ? new Rect(left, top, right, bottom) : null;
    }
}
