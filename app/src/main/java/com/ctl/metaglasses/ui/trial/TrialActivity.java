package com.ctl.metaglasses.ui.trial;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.ctl.metaglasses.databinding.ActivityTrialBinding;
import com.ctl.metaglasses.vision.BlurMode;
import com.ctl.metaglasses.vision.FaceBlurProcessor;
import com.ctl.metaglasses.vision.TFLiteAgeClassifier;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone phone-camera trial for the face-blur pipeline — no glasses required.
 * Runs the phone camera through {@link FaceBlurProcessor} and shows the blurred result live.
 */
public class TrialActivity extends AppCompatActivity {

    private static final String TAG = "TrialActivity";

    private ActivityTrialBinding binding;
    private FaceBlurProcessor processor;
    private ExecutorService analysisExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;

    private volatile BlurMode mode = BlurMode.ALL_FACES;
    private boolean frontFacing = false;

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTrialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        processor = new FaceBlurProcessor(new TFLiteAgeClassifier(this));
        // Previous rollback ensemble:
        // processor = new FaceBlurProcessor(new EnsembleAgeClassifier(Arrays.asList(
        //         new TFLiteAgeClassifier(this),
        //         new OnnxAgeClassifier(this))));
        analysisExecutor = Executors.newSingleThreadExecutor();

        binding.buttonMode.setOnClickListener(v -> cycleMode());
        binding.buttonSwitch.setOnClickListener(v -> {
            frontFacing = !frontFacing;
            bindCamera();
            updateInfo();
        });
        binding.buttonClose.setOnClickListener(v -> finish());

        updateModeLabel();
        updateInfo();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void cycleMode() {
        switch (mode) {
            case ALL_FACES:
                mode = BlurMode.MINORS_ONLY;
                if (!processor.getAgeModelLoaded()) {
                    Toast.makeText(this,
                            "No age model loaded — blurring all faces (fail-safe)",
                            Toast.LENGTH_LONG).show();
                }
                break;
            case MINORS_ONLY:
                mode = BlurMode.OFF;
                break;
            default:
                mode = BlurMode.ALL_FACES;
                break;
        }
        updateModeLabel();
        updateInfo();
    }

    private void updateModeLabel() {
        switch (mode) {
            case ALL_FACES: binding.buttonMode.setText("Blur: ALL"); break;
            case MINORS_ONLY: binding.buttonMode.setText("Blur: MINORS"); break;
            default: binding.buttonMode.setText("Blur: OFF"); break;
        }
    }

    private void updateInfo() {
        String cam = frontFacing ? "Front camera" : "Back camera";
        String m;
        switch (mode) {
            case ALL_FACES: m = "Blurring all faces"; break;
            case MINORS_ONLY: m = "Blurring minors"; break;
            default: m = "Blur off"; break;
        }
        binding.info.setText("Face Blur Trial  •  " + m + "  •  " + cam);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                imageAnalysis.setAnalyzer(analysisExecutor, this::analyze);
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
                Toast.makeText(this, "Camera init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null || imageAnalysis == null) return;
        cameraProvider.unbindAll();
        CameraSelector selector = frontFacing
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            cameraProvider.bindToLifecycle(this, selector, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Camera bind failed", e);
        }
    }

    private void analyze(ImageProxy image) {
        try {
            Bitmap bmp = imageProxyToBitmap(image);
            processor.process(bmp, mode); // detects + pixelates faces in place
            runOnUiThread(() -> {
                if (!isFinishing() && binding != null) {
                    binding.preview.setImageBitmap(bmp);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Frame analysis failed: " + e.getMessage());
        } finally {
            image.close();
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + (pixelStride > 0 ? rowPadding / pixelStride : 0);

        Bitmap raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buffer);

        Matrix m = new Matrix();
        m.postRotate(image.getImageInfo().getRotationDegrees());
        if (frontFacing) {
            m.postScale(-1f, 1f); // mirror the selfie view
        }
        // Crop away row padding (use real width) while applying rotation/mirror.
        Bitmap result = Bitmap.createBitmap(raw, 0, 0, width, height, m, true);
        raw.recycle();

        if (!result.isMutable()) {
            Bitmap mutable = result.copy(Bitmap.Config.ARGB_8888, true);
            result.recycle();
            return mutable;
        }
        return result;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        if (processor != null) {
            processor.close();
        }
        binding = null;
    }
}
