package com.ctl.metaglasses.vision;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * On-device face detection for the gallery editor (ML Kit, bundled model). Mirrors the padding used
 * by the live {@code FaceBlurProcessor} so a box drawn here lines up with what the glasses blurred.
 * Blocking — call off the main thread.
 */
public final class FaceScanner {

    private static final String TAG = "FaceScanner";
    private static final float FACE_PADDING = 0.25f;

    private static FaceDetector detector;

    private FaceScanner() {}

    private static synchronized FaceDetector detector() {
        if (detector == null) {
            detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.08f)
                    .build());
        }
        return detector;
    }

    /** Detects faces in [bitmap] and returns padded, clamped boxes in bitmap pixel space. */
    public static List<Rect> detect(Bitmap bitmap) {
        List<Rect> out = new ArrayList<>();
        if (bitmap == null) return out;
        try {
            List<Face> faces = Tasks.await(detector().process(InputImage.fromBitmap(bitmap, 0)));
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            for (Face face : faces) {
                Rect box = pad(face.getBoundingBox(), w, h);
                if (box != null) out.add(box);
            }
        } catch (Exception e) {
            Log.e(TAG, "detect failed: " + e.getMessage());
        }
        return out;
    }

    private static Rect pad(Rect r, int w, int h) {
        int padX = (int) (r.width() * FACE_PADDING);
        int padY = (int) (r.height() * FACE_PADDING);
        int left = Math.max(0, Math.min(r.left - padX, w - 1));
        int top = Math.max(0, Math.min(r.top - padY, h - 1));
        int right = Math.max(left + 1, Math.min(r.right + padX, w));
        int bottom = Math.max(top + 1, Math.min(r.bottom + padY, h));
        return (right > left && bottom > top) ? new Rect(left, top, right, bottom) : null;
    }
}
