package com.ctl.metaglasses.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ctl.metaglasses.R;
import com.ctl.metaglasses.databinding.ActivityCameraWindowBinding;
import com.ctl.metaglasses.glasses.GlassesManager;
import com.ctl.metaglasses.live.LiveStreamer;
import com.ctl.metaglasses.live.YouTubeLiveController;
import com.ctl.metaglasses.recording.MediaSaver;
import com.ctl.metaglasses.recording.VideoRecorder;
import com.ctl.metaglasses.ui.gallery.GalleryReviewActivity;
import com.ctl.metaglasses.vision.BlurMode;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

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

    // YouTube live streaming.
    private YouTubeLiveController liveController;
    private LiveStreamer liveStreamer;
    private String pendingRtmpUrl;        // set once YouTube gives us the ingest URL
    private String liveBroadcastId;       // for ending the broadcast
    private boolean startingLive = false; // between "Go Live" tap and the RTMP handshake

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

    private final ActivityResultLauncher<Intent> signInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        try {
                            GoogleSignInAccount account = GoogleSignIn
                                    .getSignedInAccountFromIntent(result.getData())
                                    .getResult(com.google.android.gms.common.api.ApiException.class);
                            if (account != null) {
                                beginLive(account);
                                return;
                            }
                        } catch (Exception e) {
                            // fall through to the cancelled/failed path
                        }
                        startingLive = false;
                        setLivePill(false, "Go Live");
                        Toast.makeText(this, "YouTube sign-in was cancelled or failed.",
                                Toast.LENGTH_SHORT).show();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCameraWindowBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Push the top bar below the status bar (notification panel) — the camera preview stays
        // full-bleed behind it, but the controls no longer overlap the clock/icons.
        final int topBarMargin = binding.topBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), topBarMargin + bars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return windowInsets;
        });

        liveController = new YouTubeLiveController(this);

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
                // Feed the same blurred frame to the YouTube live pusher once the RTMP URL is ready.
                if (liveStreamer != null && pendingRtmpUrl != null) {
                    if (!liveStreamer.isReady()) {
                        liveStreamer.start(bitmap.getWidth(), bitmap.getHeight(), pendingRtmpUrl);
                    }
                    liveStreamer.encode(bitmap);
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
        updateFpsValueLabel(binding.previewFpsSlider.getProgress());
        binding.previewFpsSlider.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(android.widget.SeekBar seekBar, int progress,
                            boolean fromUser) {
                        updateFpsValueLabel(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    }
                });
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
                // Show the blinking "REC + elapsed time" card on the glasses display.
                glassesManager.startRecordingIndicator();
            } else if (streaming) {
                // Grab the next frame with its pristine original + detected faces, so the saved
                // photo stays non-destructively editable in the gallery.
                Toast.makeText(this, "Capturing…", Toast.LENGTH_SHORT).show();
                glassesManager.capturePhoto((original, blurred, regions) ->
                        MediaSaver.savePhotoNonDestructive(this, blurred, original, regions,
                                () -> Toast.makeText(this, "Photo saved to Blurr album",
                                        Toast.LENGTH_SHORT).show()));
            } else {
                Toast.makeText(this, "No frame yet — start the stream first", Toast.LENGTH_SHORT).show();
            }
        } else {
            recording = false;
            timer.removeCallbacks(tick);
            stopRecording();
        }
        updateState();
    }

    private void stopRecording() {
        glassesManager.stopRecordingIndicator();
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
        // RECORD_AUDIO is requested here so video recordings can include sound, but it is optional —
        // see hasPermissions(): denying it still lets pairing/streaming proceed (video-only).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
            };
        }
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            // Microphone is optional — recordings just lose audio without it.
            if (Manifest.permission.RECORD_AUDIO.equals(permission)) continue;
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
        return binding.previewFpsSlider.getProgress();
    }

    private void updateFpsValueLabel(int fps) {
        binding.previewFpsValue.setText(fps == 0 ? "Max" : fps + " fps");
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
        if (!streaming) {
            Toast.makeText(this, "Pair and start the stream before going live.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (live || startingLive) {
            stopLive();
        } else {
            startLive();
        }
    }

    /** Kicks off YouTube live: sign in if needed, then create the broadcast. */
    private void startLive() {
        startingLive = true;
        setLivePill(true, "Starting…");
        GoogleSignInAccount account = liveController.lastSignedIn();
        if (account != null && account.getAccount() != null) {
            beginLive(account);
        } else {
            signInLauncher.launch(liveController.signInClient(this).getSignInIntent());
        }
    }

    /** Creates the YouTube broadcast + stream, then arms the RTMP pusher for the next frame. */
    private void beginLive(GoogleSignInAccount account) {
        setLivePill(true, "Preparing…");
        String title = "Blurr live " + new java.text.SimpleDateFormat("MMM d, HH:mm", Locale.US)
                .format(new java.util.Date());
        liveController.goLive(account, title, new YouTubeLiveController.Callback() {
            @Override
            public void onLiveReady(String rtmpUrl, String broadcastId, String watchUrl) {
                liveBroadcastId = broadcastId;
                pendingRtmpUrl = rtmpUrl;
                startingLive = false;
                live = true;
                liveStreamer = new LiveStreamer(CameraWindowActivity.this, liveListener);
                setLivePill(true, "On Air");
                Toast.makeText(CameraWindowActivity.this, "Going live: " + watchUrl,
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                startingLive = false;
                live = false;
                setLivePill(false, "Go Live");
                Toast.makeText(CameraWindowActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private final LiveStreamer.Listener liveListener = new LiveStreamer.Listener() {
        @Override
        public void onLiveStatus(String status) {
            runOnUiThread(() -> { if (binding != null) binding.textStatus.setText(status); });
        }

        @Override
        public void onLiveStarted() {
            runOnUiThread(() -> Toast.makeText(CameraWindowActivity.this,
                    "You're live on YouTube.", Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onLiveStopped() {
        }

        @Override
        public void onLiveError(String message) {
            runOnUiThread(() -> {
                Toast.makeText(CameraWindowActivity.this, message, Toast.LENGTH_LONG).show();
                stopLive();
            });
        }
    };

    private void stopLive() {
        boolean wasLive = live || startingLive;
        live = false;
        startingLive = false;
        pendingRtmpUrl = null;
        setLivePill(false, "Go Live");

        if (liveBroadcastId != null) {
            liveController.endLive(liveController.lastSignedIn(), liveBroadcastId);
            liveBroadcastId = null;
        }
        final LiveStreamer s = liveStreamer;
        liveStreamer = null;
        if (s != null) s.stop(null);
        if (wasLive) Toast.makeText(this, "Live ended.", Toast.LENGTH_SHORT).show();
    }

    private void setLivePill(boolean on, String label) {
        if (binding == null) return;
        binding.pillGoLive.setBackgroundResource(on ? R.drawable.bg_pill_red : R.drawable.bg_pill);
        binding.textGoLive.setText(label);
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
        if (live || startingLive) {
            stopLive();
        }
        if (recording) {
            recording = false;
            stopRecording();
        } else if (glassesManager != null) {
            glassesManager.stopRecordingIndicator();
        }
        if (glassesManager != null) {
            glassesManager.disconnect();
        }
        binding = null;
    }
}
