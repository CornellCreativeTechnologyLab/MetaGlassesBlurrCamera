package com.ctl.metaglasses.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.ctl.metaglasses.R;
import com.ctl.metaglasses.databinding.ActivityCameraWindowBinding;
import com.ctl.metaglasses.glasses.GlassesManager;
import com.ctl.metaglasses.recording.MediaSaver;
import com.ctl.metaglasses.recording.VideoRecorder;
import com.ctl.metaglasses.ui.gallery.GalleryReviewActivity;
import com.ctl.metaglasses.vision.BlurMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Screen ① — "Blurr" live camera window (app entry point).
 * Shows the live glasses stream, blurs everyone by default, and drives the pair/record shutter.
 */
public class CameraWindowActivity extends AppCompatActivity {

    private static final String PREVIEW_RELAY_PREFS = "preview_relay";
    private static final String PREVIEW_RELAY_URL = "relay_url";
    private static final String PREVIEW_RELAY_TOKEN = "relay_token";

    private ActivityCameraWindowBinding binding;
    private GlassesManager glassesManager;

    private boolean videoMode = true;
    private boolean streaming = false;   // frames are flowing (live preview)
    private boolean recording = false;   // UI recording flag
    private boolean live = false;        // UI "Go Live" broadcast flag
    private float previewRotation = 0f;
    private VideoRecorder videoRecorder;
    private Bitmap lastFrame;   // latest blurred frame, for photo capture
    private boolean connecting = false;   // pairing in progress
    private String pairingStatus = "";    // latest pipeline status while connecting

    private final Handler timer = new Handler(Looper.getMainLooper());
    private int seconds = 0;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            seconds++;
            binding.textTimer.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
            timer.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionsResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCameraWindowBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        glassesManager = new GlassesManager(getApplicationContext());
        glassesManager.setLifecycleOwner(this);
        glassesManager.registerCameraPermission(this);
        glassesManager.setListener(new GlassesManager.Listener() {
            @Override
            public void onStatusChanged(String status) {
                runOnUiThread(() -> {
                    pairingStatus = status;
                    updateState();
                    updatePreviewTestStatus(status);
                });
            }

            @Override
            public void onFrameReceived(byte[] frameBytes, int width, int height, long timestampMillis) {
            }

            @Override
            public void onCameraFrame(Bitmap bitmap) {
                lastFrame = bitmap; // keep the latest blurred frame for photo capture
                // Feed the blurred frame to the recorder (off the UI thread, drops until ready).
                if (recording && videoRecorder != null) {
                    if (!videoRecorder.isReady()) {
                        videoRecorder.start(bitmap.getWidth(), bitmap.getHeight());
                    }
                    videoRecorder.encode(bitmap);
                }
                runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.preview.setImageBitmap(bitmap);
                    if (!streaming) {
                        streaming = true;
                        connecting = false;
                        updateState();
                    }
                });
            }

            @Override
            public void onTranslationOutput(String text) {
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> {
                    pairingStatus = message;
                    updateState();
                    updatePreviewTestStatus(message);
                });
            }
        });

        // Blur everyone at runtime (whiteboard item 1).
        glassesManager.setBlurMode(BlurMode.ALL_FACES);

        binding.shutter.setOnClickListener(v -> onShutter());
        binding.shutterInner.setOnClickListener(v -> onShutter());
        binding.btnRotate.setOnClickListener(v -> {
            previewRotation = (previewRotation + 90f) % 360f;
            binding.preview.setRotation(previewRotation);
            binding.preview.setScaleX(previewRotation % 180f == 0f ? 1f : 1.6f);
            binding.preview.setScaleY(previewRotation % 180f == 0f ? 1f : 1.6f);
        });
        binding.btnGallery.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryReviewActivity.class)));
        binding.pillGoLive.setOnClickListener(v -> toggleLive());
        binding.tabPhoto.setOnClickListener(v -> setVideoMode(false));
        binding.tabVideo.setOnClickListener(v -> setVideoMode(true));

        // Menu drawer + How-To sheet
        binding.btnMenu.setOnClickListener(v -> binding.menuScrim.setVisibility(View.VISIBLE));
        binding.menuScrim.setOnClickListener(v -> binding.menuScrim.setVisibility(View.GONE));
        binding.btnMenuClose.setOnClickListener(v -> binding.menuScrim.setVisibility(View.GONE));
        binding.rowHowto.setOnClickListener(v -> {
            binding.menuScrim.setVisibility(View.GONE);
            binding.howtoScrim.setVisibility(View.VISIBLE);
        });
        binding.howtoScrim.setOnClickListener(v -> binding.howtoScrim.setVisibility(View.GONE));
        binding.btnHowtoClose.setOnClickListener(v -> binding.howtoScrim.setVisibility(View.GONE));
        binding.rowPreviewLab.setOnClickListener(v -> openPreviewTestPage());
        binding.btnPreviewClose.setOnClickListener(v -> closePreviewTestPage(true));
        binding.btnPreviewStart.setOnClickListener(v -> startPreviewTest());
        binding.btnPreviewStop.setOnClickListener(v -> stopPreviewTest());
        binding.previewRelayUrl.setText(
                getSharedPreferences(PREVIEW_RELAY_PREFS, MODE_PRIVATE)
                        .getString(PREVIEW_RELAY_URL, "")
        );
        binding.previewRelayToken.setText(
                getSharedPreferences(PREVIEW_RELAY_PREFS, MODE_PRIVATE)
                        .getString(PREVIEW_RELAY_TOKEN, "")
        );
        binding.rowMock.setOnClickListener(v -> {
            binding.menuScrim.setVisibility(View.GONE);
            binding.textStatus.setText("Mock camera…");
            glassesManager.enableMockGlasses();
            glassesManager.startVideoStream();
        });

        setVideoMode(true);
        updateState();
    }

    private void onShutter() {
        if (!streaming) {
            // Pair: connect + start the live view.
            if (hasPermissions()) {
                connect();
            } else {
                permissionLauncher.launch(requiredPermissions());
            }
        } else if (!recording) {
            if (videoMode) {
                recording = true;
                seconds = 0;
                binding.textTimer.setText("00:00");
                timer.postDelayed(tick, 1000);
                // Recorder is initialized on the first frame (needs the frame size).
                videoRecorder = new VideoRecorder(this);
            } else {
                Bitmap frame = lastFrame;
                if (frame != null) {
                    MediaSaver.savePhoto(this, frame,
                            () -> Toast.makeText(this, "Photo saved to Blurr album", Toast.LENGTH_SHORT).show());
                } else {
                    Toast.makeText(this, "No frame yet — start the stream first", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            recording = false;
            timer.removeCallbacks(tick);
            stopRecording();
        }
        updateState();
    }

    private void stopRecording() {
        final VideoRecorder r = videoRecorder;
        videoRecorder = null;
        if (r == null) return;
        r.stop(() -> Toast.makeText(this, "Saved to Blurr album", Toast.LENGTH_SHORT).show());
    }

    private void connect() {
        connecting = true;
        pairingStatus = "Pairing…";
        glassesManager.connect(this);
        // Mark that we want the stream; the camera-permission dialog is requested later (once the
        // session is STARTED) so it doesn't collide with the registration dialog.
        glassesManager.startVideoStream();
        updateState();
    }

    private void onPermissionsResult(Map<String, Boolean> results) {
        if (hasPermissions()) {
            connect();
            return;
        }

        List<String> denied = new ArrayList<>();
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                denied.add(permission.substring(permission.lastIndexOf('.') + 1));
            }
        }
        String message = "Missing permission: " + String.join(", ", denied);
        pairingStatus = message;
        connecting = false;
        binding.textStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
            };
        }
        return new String[]{Manifest.permission.CAMERA};
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void openPreviewTestPage() {
        binding.menuScrim.setVisibility(View.GONE);
        binding.previewTestScrim.setVisibility(View.VISIBLE);
        binding.previewTestStatus.setText(
                glassesManager.isDisplayReady()
                        ? "Display ready. Choose a mode and frame rate."
                        : "Pair display-capable glasses and wait for Display ready."
        );
        binding.btnPreviewStart.setEnabled(!glassesManager.isDisplayPreviewTestRunning());
        binding.btnPreviewStop.setEnabled(glassesManager.isDisplayPreviewTestRunning());
    }

    private void closePreviewTestPage(boolean stopTest) {
        if (stopTest && glassesManager != null && glassesManager.isDisplayPreviewTestRunning()) {
            glassesManager.stopDisplayPreviewTest();
        }
        binding.previewTestScrim.setVisibility(View.GONE);
    }

    private void startPreviewTest() {
        if (!glassesManager.isDisplayReady()) {
            updatePreviewTestStatus("Display is not ready. Pair the display glasses first.");
            Toast.makeText(this, "Glasses display is not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean useLiveFrames = binding.modeLiveFrames.isChecked();
        if (useLiveFrames && !streaming) {
            updatePreviewTestStatus("Start the blurred camera stream before using live-frame mode.");
            Toast.makeText(this, "Start the camera stream first", Toast.LENGTH_SHORT).show();
            return;
        }

        String relayUrl = binding.previewRelayUrl.getText().toString().trim();
        String relayToken = binding.previewRelayToken.getText().toString().trim();
        if (useLiveFrames && !relayUrl.toLowerCase(Locale.US).startsWith("https://")) {
            updatePreviewTestStatus("Live mode needs a public HTTPS relay URL.");
            Toast.makeText(this, "Enter an HTTPS frame relay URL", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(PREVIEW_RELAY_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREVIEW_RELAY_URL, relayUrl)
                .putString(PREVIEW_RELAY_TOKEN, relayToken)
                .apply();

        int fps = selectedPreviewFps();
        int maxDimension = selectedPreviewDimension();
        updatePreviewTestStatus(
                "Starting " + (useLiveFrames ? "live HTTPS relay" : "HTTPS control")
                        + " test at " + fps + " fps…"
        );
        binding.btnPreviewStart.setEnabled(false);
        binding.btnPreviewStop.setEnabled(true);
        glassesManager.startDisplayPreviewTest(
                useLiveFrames,
                fps,
                maxDimension,
                45,
                relayUrl,
                relayToken
        );
    }

    private void stopPreviewTest() {
        glassesManager.stopDisplayPreviewTest();
        binding.btnPreviewStart.setEnabled(true);
        binding.btnPreviewStop.setEnabled(false);
    }

    private int selectedPreviewFps() {
        int checked = binding.previewFpsGroup.getCheckedRadioButtonId();
        if (checked == R.id.preview_fps_1) return 1;
        if (checked == R.id.preview_fps_4) return 4;
        if (checked == R.id.preview_fps_6) return 6;
        if (checked == R.id.preview_fps_8) return 8;
        return 2;
    }

    private int selectedPreviewDimension() {
        int checked = binding.previewSizeGroup.getCheckedRadioButtonId();
        if (checked == R.id.preview_size_96) return 96;
        if (checked == R.id.preview_size_240) return 240;
        return 160;
    }

    private void updatePreviewTestStatus(String status) {
        if (binding == null || binding.previewTestScrim.getVisibility() != View.VISIBLE) return;
        binding.previewTestStatus.setText(status);
        boolean running = glassesManager != null && glassesManager.isDisplayPreviewTestRunning();
        binding.btnPreviewStart.setEnabled(!running);
        binding.btnPreviewStop.setEnabled(running);
    }

    @Override
    public void onBackPressed() {
        if (binding != null && binding.previewTestScrim.getVisibility() == View.VISIBLE) {
            closePreviewTestPage(true);
            return;
        }
        if (binding != null && binding.howtoScrim.getVisibility() == View.VISIBLE) {
            binding.howtoScrim.setVisibility(View.GONE);
            return;
        }
        if (binding != null && binding.menuScrim.getVisibility() == View.VISIBLE) {
            binding.menuScrim.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    private void toggleLive() {
        if (!streaming) return;
        live = !live;
        if (live) {
            binding.pillGoLive.setBackgroundResource(R.drawable.bg_pill_red);
            binding.textGoLive.setText("On Air");
        } else {
            binding.pillGoLive.setBackgroundResource(R.drawable.bg_pill);
            binding.textGoLive.setText("Go Live");
        }
    }

    private void setVideoMode(boolean video) {
        videoMode = video;
        int accent = ContextCompat.getColor(this, R.color.blurr_accent);
        int muted = Color.parseColor("#8CFFFFFF");
        binding.tabVideo.setTextColor(video ? accent : muted);
        binding.tabPhoto.setTextColor(video ? muted : accent);
        if (streaming && !recording) updateState(); // shutter color follows mode
    }

    /** Applies the disconnected / idle / recording visuals to the shutter + status pill. */
    private void updateState() {
        if (binding == null) return;

        // Shutter
        if (!streaming) {
            binding.shutterInner.setText("PAIR");
            binding.shutterInner.setBackgroundResource(R.drawable.bg_dot);
            binding.shutterInner.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            binding.shutterInner.setScaleX(1f);
            binding.shutterInner.setScaleY(1f);
        } else if (recording) {
            binding.shutterInner.setText("");
            binding.shutterInner.setBackgroundResource(R.drawable.bg_stop_square);
            binding.shutterInner.setBackgroundTintList(null);
            binding.shutterInner.setScaleX(0.45f);
            binding.shutterInner.setScaleY(0.45f);
        } else {
            binding.shutterInner.setText("");
            binding.shutterInner.setBackgroundResource(R.drawable.bg_dot);
            binding.shutterInner.setBackgroundTintList(ColorStateList.valueOf(
                    videoMode ? ContextCompat.getColor(this, R.color.blurr_red) : Color.WHITE));
            binding.shutterInner.setScaleX(1f);
            binding.shutterInner.setScaleY(1f);
        }

        // Status pill — derived purely from state (no leftover default text).
        int dotColor;
        String label;
        int btIcon;
        if (recording) {
            dotColor = ContextCompat.getColor(this, R.color.blurr_red);
            label = "Live";
            btIcon = R.drawable.ic_bluetooth_connected;
        } else if (streaming) {
            dotColor = ContextCompat.getColor(this, R.color.blurr_green);
            label = "Connected";
            btIcon = R.drawable.ic_bluetooth_connected;
        } else if (connecting) {
            dotColor = ContextCompat.getColor(this, R.color.blurr_accent);
            label = pairingStatus.isEmpty() ? "Pairing…" : pairingStatus;
            btIcon = R.drawable.ic_bluetooth;
        } else {
            dotColor = ContextCompat.getColor(this, R.color.blurr_zinc);
            label = "Not connected";
            btIcon = R.drawable.ic_bluetooth;
        }
        binding.dotStatus.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        binding.textStatus.setText(label);
        binding.iconBt.setImageResource(btIcon);

        // Recording badge
        binding.badgeRec.setVisibility(recording ? View.VISIBLE : View.GONE);

        // Go Live availability
        binding.pillGoLive.setAlpha(streaming ? 1f : 0.4f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timer.removeCallbacks(tick);
        if (recording) {
            recording = false;
            stopRecording();
        }
        if (glassesManager != null) {
            glassesManager.disconnect();
        }
        binding = null;
    }
}
