package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Bitmap;
import android.graphics.PointF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.ArrayList;
import java.util.List;

/** Fallback detector until SCRFD/RetinaFace ONNX post-processing is validated. */
public final class MlKitFaceDetectorStage implements FaceDetectorStage {
    private final FaceDetector detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build());

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public String modelId() {
        return "mlkit-face-fallback";
    }

    @Override
    public List<FaceCandidate> detect(Bitmap frame) throws Exception {
        List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)));
        List<FaceCandidate> out = new ArrayList<>(faces.size());
        for (Face face : faces) {
            FaceBox box = FaceBox.fromRect(face.getBoundingBox()).clamp(frame.getWidth(), frame.getHeight());
            out.add(new FaceCandidate(box, landmarks(face), 1f));
        }
        return out;
    }

    private static float[] landmarks(Face face) {
        PointF leftEye = point(face, FaceLandmark.LEFT_EYE);
        PointF rightEye = point(face, FaceLandmark.RIGHT_EYE);
        PointF nose = point(face, FaceLandmark.NOSE_BASE);
        PointF leftMouth = point(face, FaceLandmark.MOUTH_LEFT);
        PointF rightMouth = point(face, FaceLandmark.MOUTH_RIGHT);
        if (leftEye == null || rightEye == null || nose == null || leftMouth == null || rightMouth == null) {
            return null;
        }
        return new float[] {
                leftEye.x, leftEye.y,
                rightEye.x, rightEye.y,
                nose.x, nose.y,
                leftMouth.x, leftMouth.y,
                rightMouth.x, rightMouth.y
        };
    }

    private static PointF point(Face face, int landmark) {
        FaceLandmark value = face.getLandmark(landmark);
        return value == null ? null : value.getPosition();
    }

    @Override
    public void close() {
        detector.close();
    }
}
