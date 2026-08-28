package com.ctl.metaglasses.ui.gallery;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ctl.metaglasses.R;
import com.ctl.metaglasses.databinding.ActivityGalleryReviewBinding;
import com.ctl.metaglasses.recording.MediaSaver;
import com.ctl.metaglasses.video.VideoBlurProcessor;
import com.ctl.metaglasses.vision.AgeDisplayFormatter;
import com.ctl.metaglasses.vision.BlurEditStore;
import com.ctl.metaglasses.vision.BlurRenderer;
import com.ctl.metaglasses.vision.FaceMap;
import com.ctl.metaglasses.vision.FaceRegion;
import com.ctl.metaglasses.vision.FaceScanner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen ② — "Blurr" review / gallery.
 *
 * Photos captured with this version are non-destructive: alongside the blurred JPEG we keep the
 * untouched original + a {@link FaceMap} (see {@link BlurEditStore}). For those, this screen runs a
 * real face editor — each detected face is a tappable box that toggles its blur layer on/off, and
 * the preview / export re-renders from the original. Videos and older photos still play/show and
 * can be saved to the phone camera roll or shared.
 */
public class GalleryReviewActivity extends AppCompatActivity {

    private ActivityGalleryReviewBinding binding;

    private boolean selecting = false;
    private boolean videoPrepared = false;
    private boolean playWhenPrepared = false;

    private MediaItem selectedItem;

    // Non-destructive editing state for the currently shown, editable photo.
    private boolean editable = false;
    private long editId = -1;
    private Bitmap editOriginal;   // pristine
    private FaceMap editMap;       // face layers
    private Bitmap editRendered;   // original with current blur layers applied (shown in preview)

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<MediaItem> items = new ArrayList<>();

    // Video face-blur editor (offline transcode).
    private static final int PEOPLE_GRID_RENDER_BATCH = 8;
    private VideoBlurProcessor videoProcessor;
    private AlertDialog progressDialog;
    private int peopleGridRenderGeneration = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable scrubTick = new Runnable() {
        @Override
        public void run() {
            if (binding == null) return;
            if (videoPrepared && binding.previewVideo.isPlaying()) {
                syncScrubberToVideo();
            }
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGalleryReviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Keep content clear of the status bar (notification panel) and navigation bar. The app
        // draws edge-to-edge on Android 15+ (targetSdk 36), so pad the root by the system-bar insets.
        final int rootPadH = binding.galleryRoot.getPaddingLeft();
        ViewCompat.setOnApplyWindowInsetsListener(binding.galleryRoot, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(rootPadH, bars.top, rootPadH, bars.bottom);
            return windowInsets;
        });

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSave.setOnClickListener(v -> confirmExportToPhoneGallery());
        binding.btnShare.setOnClickListener(v -> shareCurrent());

        binding.btnPlay.setOnClickListener(v -> togglePlayback());
        configureVideoPlayer();
        configureScrubber();
        videoProcessor = new VideoBlurProcessor(this);

        binding.btnSelectFaces.setOnClickListener(v -> {
            if (selectedItem != null && selectedItem.isVideo) {
                startVideoBlur(); // scan for people, then let the user choose who to blur
                return;
            }
            if (!editable) {
                Toast.makeText(this, "Preparing faces…", Toast.LENGTH_SHORT).show();
                return;
            }
            if (editMap != null && editMap.faces.isEmpty()) {
                Toast.makeText(this, "No faces detected in this photo.", Toast.LENGTH_SHORT).show();
                return;
            }
            selecting = !selecting;
            updateSelecting();
        });
        binding.btnSelectDone.setOnClickListener(v -> {
            selecting = false;
            updateSelecting();
        });
        binding.btnBlurAll.setOnClickListener(v -> {
            if (selectedItem != null && selectedItem.isVideo) {
                confirmBlurAllVideo(); // blur everyone in the video, no per-person picking
                return;
            }
            if (!editable || editMap == null || editMap.faces.isEmpty()) {
                Toast.makeText(this, "No faces detected in this photo.", Toast.LENGTH_SHORT).show();
                return;
            }
            for (FaceMap.Face f : editMap.faces) f.blurred = true;
            selecting = true;
            onFacesChanged();
            updateSelecting();
        });
        binding.btnUnblurAll.setOnClickListener(v -> {
            if (selectedItem != null && selectedItem.isVideo) {
                Toast.makeText(this,
                        "Videos are processed as new copies. Use Select faces to choose who gets blurred.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (!editable || editMap == null || editMap.faces.isEmpty()) {
                Toast.makeText(this, "No faces detected in this photo.", Toast.LENGTH_SHORT).show();
                return;
            }
            for (FaceMap.Face f : editMap.faces) f.blurred = false;
            selecting = true;
            onFacesChanged();
            updateSelecting();
        });

        updateSelecting();
    }

    private void configureVideoPlayer() {
        binding.previewVideo.setOnPreparedListener(mediaPlayer -> {
            if (binding == null || selectedItem == null || !selectedItem.isVideo) return;

            videoPrepared = true;
            binding.btnPlay.setEnabled(true);
            mediaPlayer.setLooping(false);

            binding.scrubberRow.setVisibility(View.VISIBLE);
            binding.videoSeek.setProgress(0);
            binding.timeCurrent.setText(formatTime(0));
            binding.timeDuration.setText(formatTime(binding.previewVideo.getDuration()));

            if (playWhenPrepared) {
                startPreparedVideo();
            }
        });

        binding.previewVideo.setOnCompletionListener(mediaPlayer -> {
            binding.btnPlay.setImageResource(R.drawable.ic_play);
            binding.btnPlay.setContentDescription("Play");
            ui.removeCallbacks(scrubTick);
            binding.videoSeek.setProgress(binding.videoSeek.getMax());
            binding.timeCurrent.setText(binding.timeDuration.getText());
            // Leave the final frame visible, but make the next tap replay from the beginning.
            binding.previewVideo.seekTo(0);
        });

        binding.previewVideo.setOnErrorListener((mediaPlayer, what, extra) -> {
            videoPrepared = false;
            playWhenPrepared = false;
            binding.btnPlay.setEnabled(true);
            binding.btnPlay.setImageResource(R.drawable.ic_play);
            binding.btnPlay.setContentDescription("Play");
            binding.previewVideo.setVisibility(View.GONE);
            binding.previewImage.setVisibility(View.VISIBLE);
            Toast.makeText(this, "This video could not be played.", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void configureScrubber() {
        binding.videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || !videoPrepared || binding == null) return;
                int duration = binding.previewVideo.getDuration();
                if (duration <= 0) return;
                int target = (int) ((long) progress * duration / seekBar.getMax());
                binding.previewVideo.seekTo(target);
                binding.timeCurrent.setText(formatTime(target));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void syncScrubberToVideo() {
        int duration = binding.previewVideo.getDuration();
        int pos = binding.previewVideo.getCurrentPosition();
        if (duration > 0) {
            binding.videoSeek.setProgress((int) ((long) pos * binding.videoSeek.getMax() / duration));
        }
        binding.timeCurrent.setText(formatTime(pos));
    }

    private static String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGallery();
    }

    @Override
    protected void onPause() {
        pausePlayback();
        super.onPause();
    }

    /** Loads the Blurr album; shows the empty state when there are no captures. */
    private void loadGallery() {
        stopPlaybackAndReset();
        items.clear();
        queryPrivateInto(MediaSaver.appPhotoDir(this), false);
        queryPrivateInto(MediaSaver.appVideoDir(this), true);
        // Keep older MediaStore captures visible; new captures are app-private.
        queryInto(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.RELATIVE_PATH, MediaSaver.PHOTO_PATH, false);
        queryInto(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.RELATIVE_PATH, MediaSaver.VIDEO_PATH, true);
        Collections.sort(items, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));

        boolean empty = items.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.reviewContent.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.gallerySubtitle.setText(empty
                ? "Empty"
                : items.size() + (items.size() == 1 ? " capture" : " captures"));
        if (empty) return;

        binding.stripContainer.removeAllViews();
        int size = dp(60);
        for (MediaItem item : items) {
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(dp(6));
            thumb.setLayoutParams(lp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.drawable.bg_glass_square);
            thumb.setClipToOutline(true);
            thumb.setContentDescription(item.isVideo ? "Video capture" : "Photo capture");
            thumb.setOnClickListener(v -> showItem(item));
            binding.stripContainer.addView(thumb);
            loadThumb(item.uri, new Size(160, 160), thumb);
        }
        showItem(items.get(0));
    }

    private void showItem(MediaItem item) {
        stopPlaybackAndReset();
        selectedItem = item;
        selecting = false;
        clearEditState();

        binding.previewImage.setImageDrawable(null);
        binding.previewImage.setVisibility(View.VISIBLE);
        binding.previewVideo.setVisibility(View.GONE);
        binding.scrubberRow.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
        binding.btnUnblurAll.setVisibility(item.isVideo ? View.GONE : View.VISIBLE);
        binding.videoSeek.setProgress(0);
        binding.timeCurrent.setText(formatTime(0));
        binding.timeDuration.setText(formatTime(0));
        binding.btnPlay.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_play);
        binding.btnPlay.setContentDescription("Play");

        if (!item.isVideo) {
            binding.previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (item.editId >= 0 && BlurEditStore.has(this, item.editId)) {
                // Non-destructive: pristine original + stored face map (faces start blurred).
                loadEditablePhoto(item.editId);
            } else {
                // Any other photo: detect faces on the image itself so editing still works.
                loadDetectablePhoto(item);
            }
        } else {
            binding.previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadThumb(item.uri, new Size(1080, 1080), binding.previewImage);
        }
        updateSelecting();
    }

    // ---------------------------------------------------------------- editing

    private void loadEditablePhoto(long id) {
        io.execute(() -> {
            Bitmap original = BlurEditStore.loadOriginal(this, id);
            FaceMap map = BlurEditStore.loadMap(this, id);
            if (original == null || map == null) {
                ui.post(() -> {
                    binding.previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    loadThumb(selectedItem.uri, new Size(1080, 1080), binding.previewImage);
                });
                return;
            }
            Bitmap rendered = BlurRenderer.render(original, map);
            ui.post(() -> {
                if (binding == null || selectedItem == null || selectedItem.editId != id) {
                    original.recycle();
                    rendered.recycle();
                    return;
                }
                editable = true;
                editId = id;
                editOriginal = original;
                editMap = map;
                editRendered = rendered;
                binding.previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
                binding.previewImage.setImageBitmap(rendered);
                updateSelecting();
            });
        });
    }

    /**
     * Loads a photo that has no stored edit record (older captures, or anything the glasses didn't
     * tag): decode the image, run face detection on it, and enable editing over that image. Faces
     * start un-blurred here, so the user chooses which to blur. Edits are destructive-forward
     * (export renders a new image) since there is no pristine original to reveal.
     */
    private void loadDetectablePhoto(MediaItem item) {
        final Uri uri = item.uri;
        io.execute(() -> {
            Bitmap bmp = decodeBitmap(uri, 1600);
            if (bmp == null) {
                ui.post(() -> {
                    if (binding == null || selectedItem != item) return;
                    binding.previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    loadThumb(uri, new Size(1080, 1080), binding.previewImage);
                });
                return;
            }
            java.util.List<FaceRegion> regions = FaceScanner.detect(bmp);
            FaceMap map = new FaceMap(bmp.getWidth(), bmp.getHeight());
            for (FaceRegion r : regions) {
                map.faces.add(new FaceMap.Face(
                        r.box.left, r.box.top, r.box.right, r.box.bottom, false, r.contour));
            }
            ui.post(() -> {
                if (binding == null || selectedItem != item) {
                    bmp.recycle();
                    return;
                }
                editable = true;
                editId = -1; // no stored record; edits are not persisted, only exported
                editOriginal = bmp;
                editMap = map;
                editRendered = bmp; // nothing blurred yet
                binding.previewImage.setImageBitmap(bmp);
                updateSelecting();
            });
        });
    }

    /** Decodes [uri] downsampled so its largest side is ~[maxDim] px (keeps detection fast). */
    private Bitmap decodeBitmap(Uri uri, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int larger = Math.max(bounds.outWidth, bounds.outHeight);
            while (larger / sample > maxDim) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Re-renders the preview and (for stored captures) persists the map after a toggle. */
    private void onFacesChanged() {
        if (!editable || editOriginal == null || editMap == null) return;
        Bitmap rendered = BlurRenderer.render(editOriginal, editMap);
        Bitmap old = editRendered;
        editRendered = rendered;
        binding.previewImage.setImageBitmap(rendered);
        if (old != null && old != editOriginal) old.recycle();
        rebuildFaceOverlay();
        if (editId >= 0) {
            final long id = editId;
            final FaceMap map = editMap;
            io.execute(() -> BlurEditStore.saveMap(this, id, map));
        }
    }

    private void clearEditState() {
        editable = false;
        editId = -1;
        editMap = null;
        binding.faceOverlay.removeAllViews();
        binding.faceOverlay.setVisibility(View.GONE);
        if (editRendered != null && editRendered != editOriginal) editRendered.recycle();
        if (editOriginal != null) editOriginal.recycle();
        editOriginal = null;
        editRendered = null;
    }

    private void updateSelecting() {
        boolean show = selecting && editable && editMap != null;
        binding.selectHeader.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.faceOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // Wait for layout so the player has real dimensions before mapping face boxes.
            binding.faceOverlay.post(this::rebuildFaceOverlay);
        } else {
            binding.faceOverlay.removeAllViews();
        }
    }

    /** Draws a tappable box per detected face at its position in the displayed (fit-center) image. */
    private void rebuildFaceOverlay() {
        if (binding == null || editMap == null) return;
        FrameLayout overlay = binding.faceOverlay;
        overlay.removeAllViews();

        int vw = overlay.getWidth();
        int vh = overlay.getHeight();
        if (vw == 0 || vh == 0 || editMap.width == 0 || editMap.height == 0) return;

        float scale = Math.min(vw / (float) editMap.width, vh / (float) editMap.height);
        float dx = (vw - editMap.width * scale) / 2f;
        float dy = (vh - editMap.height * scale) / 2f;

        int blurredCount = 0;
        for (final FaceMap.Face face : editMap.faces) {
            int left = Math.round(dx + face.left * scale);
            int top = Math.round(dy + face.top * scale);
            int w = Math.max(dp(16), Math.round((face.right - face.left) * scale));
            int h = Math.max(dp(16), Math.round((face.bottom - face.top) * scale));

            FrameLayout box = new FrameLayout(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = Math.max(0, left);
            lp.topMargin = Math.max(0, top);
            box.setLayoutParams(lp);
            box.setBackgroundResource(face.blurred
                    ? R.drawable.bg_face_box_accent
                    : R.drawable.bg_face_box_white);
            box.setContentDescription(face.blurred ? "Face blurred, tap to reveal"
                    : "Face revealed, tap to blur");

            if (face.blurred) {
                blurredCount++;
                ImageView check = new ImageView(this);
                int cs = dp(18);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cs, cs);
                clp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                clp.setMargins(0, -dp(6), -dp(6), 0);
                check.setLayoutParams(clp);
                check.setBackgroundResource(R.drawable.bg_check_badge);
                check.setPadding(dp(3), dp(3), dp(3), dp(3));
                check.setImageResource(R.drawable.ic_check);
                box.addView(check);
            }

            box.setOnClickListener(v -> {
                face.blurred = !face.blurred;
                onFacesChanged();
            });
            overlay.addView(box);
        }

        binding.selectCount.setText(blurredCount + " of " + editMap.faces.size()
                + " faces blurred · tap to toggle");
    }

    // ---------------------------------------------------------------- playback

    private void togglePlayback() {
        MediaItem item = selectedItem;
        if (item == null || !item.isVideo) return;

        if (videoPrepared) {
            if (binding.previewVideo.isPlaying()) {
                pausePlayback();
            } else {
                startPreparedVideo();
            }
            return;
        }

        // VideoView prepares asynchronously. Keep the thumbnail visible until playback is ready.
        playWhenPrepared = true;
        binding.btnPlay.setEnabled(false);
        binding.btnPlay.setImageResource(R.drawable.ic_pause);
        binding.btnPlay.setContentDescription("Loading video");
        binding.previewVideo.setVisibility(View.VISIBLE);
        binding.previewVideo.setVideoURI(item.uri);
        binding.previewVideo.requestFocus();
    }

    private void startPreparedVideo() {
        if (!videoPrepared || binding == null) return;

        playWhenPrepared = false;
        binding.previewVideo.setVisibility(View.VISIBLE);
        binding.previewImage.setVisibility(View.GONE);
        binding.previewVideo.start();
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_pause);
        binding.btnPlay.setContentDescription("Pause");
        ui.removeCallbacks(scrubTick);
        ui.post(scrubTick);
    }

    private void pausePlayback() {
        if (binding == null) return;

        playWhenPrepared = false;
        if (binding.previewVideo.isPlaying()) {
            binding.previewVideo.pause();
        }
        ui.removeCallbacks(scrubTick);
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_play);
        binding.btnPlay.setContentDescription("Play");
    }

    private void stopPlaybackAndReset() {
        if (binding == null) return;

        playWhenPrepared = false;
        videoPrepared = false;
        ui.removeCallbacks(scrubTick);
        try {
            binding.previewVideo.stopPlayback();
        } catch (Exception ignored) {
        }
        binding.previewVideo.setVisibility(View.GONE);
        binding.previewImage.setVisibility(View.VISIBLE);
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_play);
        binding.btnPlay.setContentDescription("Play");
    }

    // ---------------------------------------------------------------- save / share

    private void confirmExportToPhoneGallery() {
        MediaItem item = selectedItem;
        if (item == null) return;

        new AlertDialog.Builder(this)
                .setTitle("Export to phone gallery?")
                .setMessage("This will copy the current capture to DCIM/Blurr so it appears outside Blurr.")
                .setPositiveButton("Export", (dialog, which) -> exportToPhoneGallery())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Exports the current item into the phone camera roll (DCIM/Blurr). */
    private void exportToPhoneGallery() {
        MediaItem item = selectedItem;
        if (item == null) return;

        if (editable && editOriginal != null && editMap != null) {
            Bitmap rendered = BlurRenderer.render(editOriginal, editMap);
            MediaSaver.saveBitmapToDcim(this, rendered, () -> {
                rendered.recycle();
                Toast.makeText(this, "Exported to Photos (DCIM/Blurr)", Toast.LENGTH_SHORT).show();
            });
        } else {
            MediaSaver.copyToDcim(this, item.uri, item.isVideo, ok ->
                    Toast.makeText(this,
                            ok ? "Exported to Photos (DCIM/Blurr)" : "Could not export to Photos",
                            Toast.LENGTH_SHORT).show());
        }
    }

    private void shareCurrent() {
        MediaItem item = selectedItem;
        if (item == null) return;

        if (editable && editOriginal != null && editMap != null) {
            final Bitmap rendered = BlurRenderer.render(editOriginal, editMap);
            io.execute(() -> {
                try {
                    File dir = new File(getCacheDir(), "shared");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, "blurr_share_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        rendered.compress(Bitmap.CompressFormat.JPEG, 95, os);
                    }
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                    ui.post(() -> launchShare(uri, "image/jpeg"));
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(this, "Could not prepare share.", Toast.LENGTH_SHORT).show());
                } finally {
                    ui.post(rendered::recycle);
                }
            });
        } else {
            launchShare(shareableUri(item.uri), item.isVideo ? "video/mp4" : "image/jpeg");
        }
    }

    private Uri shareableUri(Uri uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) return uri;
        String path = uri.getPath();
        if (path == null) return uri;
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(path));
    }

    private void launchShare(Uri uri, String mime) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share"));
    }

    // ---------------------------------------------------------- video blur

    private Uri editableVideoUri(MediaItem item) {
        if (item == null || !item.isVideo) return item == null ? null : item.uri;
        if (item.editId < 0) return item.uri;
        File original = MediaSaver.appOriginalVideoFile(this, item.editId);
        return original.exists() ? Uri.fromFile(original) : item.uri;
    }

    /** Scans the selected video for distinct people, then lets the user pick who to blur. */
    private void startVideoBlur() {
        if (selectedItem == null || !selectedItem.isVideo) return;
        final Uri uri = editableVideoUri(selectedItem);
        progressDialog = showProgress("Scanning video for people…");
        videoProcessor.scan(uri, new VideoBlurProcessor.ScanCallback() {
            @Override
            public void onProgress(float f) {
                setProgressMessage("Scanning… " + Math.round(f * 100) + "%");
            }

            @Override
            public void onComplete(List<VideoBlurProcessor.Person> people) {
                dismissProgress();
                choosePeopleThenApply(uri, people);
            }

            @Override
            public void onError(String message) {
                dismissProgress();
                Toast.makeText(GalleryReviewActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void choosePeopleThenApply(Uri uri, List<VideoBlurProcessor.Person> people) {
        if (people.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No distinct people detected")
                    .setMessage("Blur every face in this video anyway?")
                    .setPositiveButton("Blur all", (d, w) -> applyVideoBlur(uri, Collections.emptySet()))
                    .setNeutralButton("Privacy blur", (d, w) -> applyPrivacyVideoBlur(uri))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), 0);

        TextView summary = new TextView(this);
        summary.setTextColor(Color.rgb(40, 40, 40));
        summary.setTextSize(13);
        root.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);
        quickActions.setPadding(0, dp(8), 0, dp(8));
        root.addView(quickActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));

        final List<List<PersonChoice>> holder = new ArrayList<>();
        summary.setText("Preparing face cards...");

        addSelectionAction(quickActions, "Suggested", () -> {
            if (holder.isEmpty()) return;
            selectSuggestedChoices(holder.get(0));
            renderPeopleGrid(grid, holder.get(0), summary);
        });
        addSelectionAction(quickActions, "All", () -> {
            if (holder.isEmpty()) return;
            setAllChoices(holder.get(0), true);
            renderPeopleGrid(grid, holder.get(0), summary);
        });
        addSelectionAction(quickActions, "Unblur all", () -> {
            if (holder.isEmpty()) return;
            setAllChoices(holder.get(0), false);
            renderPeopleGrid(grid, holder.get(0), summary);
        });
        addSelectionAction(quickActions, "Invert", () -> {
            if (holder.isEmpty()) return;
            invertChoices(holder.get(0));
            renderPeopleGrid(grid, holder.get(0), summary);
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Who to blur")
                .setView(root)
                .setPositiveButton("Apply selection", null)
                .setNeutralButton("Privacy blur", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (holder.isEmpty()) return;
                Set<Integer> ids = selectedPersonIds(holder.get(0));
                dialog.dismiss();
                applyVideoBlur(uri, ids.isEmpty() ? noTracksSelected() : ids);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                applyPrivacyVideoBlur(uri);
            });
        });
        dialog.show();

        io.execute(() -> {
            List<PersonChoice> choices = buildPersonChoices(people, true, null);
            ui.post(() -> {
                if (!dialog.isShowing()) return;
                holder.clear();
                holder.add(choices);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                renderPeopleGrid(grid, choices, summary);
            });
        });
    }

    private void renderPeopleGrid(GridLayout grid, List<PersonChoice> choices, TextView summary) {
        int generation = ++peopleGridRenderGeneration;
        grid.removeAllViews();
        grid.setColumnCount(2);
        updatePeopleSummary(summary, choices, 0);
        renderPeopleGridBatch(grid, choices, summary, generation, 0);
    }

    private void renderPeopleGridBatch(
            GridLayout grid,
            List<PersonChoice> choices,
            TextView summary,
            int generation,
            int start
    ) {
        if (generation != peopleGridRenderGeneration || binding == null) return;
        int end = Math.min(choices.size(), start + PEOPLE_GRID_RENDER_BATCH);
        for (int i = start; i < end; i++) {
            PersonChoice choice = choices.get(i);
            FrameLayout card = personChoiceCard(choice, i + 1, () ->
                    renderPeopleGrid(grid, choices, summary));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(8));
            card.setLayoutParams(lp);
            grid.addView(card);
        }
        updatePeopleSummary(summary, choices, end);
        if (end < choices.size()) {
            ui.post(() -> renderPeopleGridBatch(grid, choices, summary, generation, end));
        }
    }

    private void updatePeopleSummary(TextView summary, List<PersonChoice> choices, int rendered) {
        int selected = 0;
        int suggested = 0;
        int repeated = 0;
        for (PersonChoice choice : choices) {
            if (choice.selected) selected++;
            if (choice.privacySuggested) suggested++;
            repeated += Math.max(0, choice.people.size() - 1);
        }
        String suffix = repeated > 0 ? " - " + repeated + " repeated entries grouped" : "";
        String loading = rendered < choices.size() ? " - loading " + rendered + "/" + choices.size() : "";
        summary.setText(selected + " selected - " + suggested + " privacy suggested - "
                + choices.size() + " face cards" + suffix + loading);
    }

    private FrameLayout personChoiceCard(PersonChoice choice, int index, Runnable onChanged) {
        FrameLayout card = new FrameLayout(this);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(faceChoiceBackground(choice.selected));
        card.setOnClickListener(v -> {
            choice.selected = !choice.selected;
            onChanged.run();
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        ImageView face = new ImageView(this);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setImageBitmap(choice.people.get(0).thumbnail);
        content.addView(face, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(126)));

        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(choice.selected ? Color.BLACK : Color.WHITE);
        label.setTextSize(13);
        label.setText(choice.people.size() > 1
                ? "Person " + index + " (" + choice.people.size() + " matches)"
                : "Person " + index);
        label.setPadding(0, dp(8), 0, dp(2));
        content.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView suggestion = new TextView(this);
        suggestion.setGravity(Gravity.CENTER);
        suggestion.setText(choice.privacySuggested ? "PRIVACY SUGGESTED" : "MANUAL REVIEW");
        suggestion.setTextColor(choice.selected ? Color.rgb(35, 35, 35) : Color.rgb(210, 210, 210));
        suggestion.setTextSize(10);
        suggestion.setTypeface(null, android.graphics.Typeface.BOLD);
        suggestion.setPadding(0, 0, 0, dp(4));
        content.addView(suggestion, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView modelNote = new TextView(this);
        modelNote.setGravity(Gravity.CENTER);
        modelNote.setText(ageNoteForChoice(choice));
        modelNote.setTextColor(choice.selected ? Color.rgb(35, 35, 35) : Color.rgb(210, 210, 210));
        modelNote.setTextSize(10);
        modelNote.setPadding(dp(4), 0, dp(4), dp(6));
        content.addView(modelNote, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView badge = new TextView(this);
        badge.setText(choice.selected ? "BLUR" : "KEEP");
        badge.setTextColor(choice.selected ? Color.BLACK : Color.WHITE);
        badge.setTextSize(11);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        badge.setBackground(faceBadgeBackground(choice.selected));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.END;
        blp.setMargins(0, dp(10), dp(10), 0);
        card.addView(badge, blp);

        return card;
    }

    private List<PersonChoice> buildPersonChoices(
            List<VideoBlurProcessor.Person> people,
            boolean mergeSimilar,
            Set<Integer> selectedIds
    ) {
        List<PersonChoice> choices = new ArrayList<>();
        for (VideoBlurProcessor.Person person : people) {
            long hash = averageFaceHash(person.thumbnail);
            PersonChoice target = null;
            if (mergeSimilar) {
                for (PersonChoice choice : choices) {
                    if (Long.bitCount(choice.hash ^ hash) <= 10) {
                        target = choice;
                        break;
                    }
                }
            }
            if (target == null) {
                target = new PersonChoice(hash);
                choices.add(target);
            }
            target.people.add(person);
            target.privacySuggested = target.privacySuggested || person.privacyBlur;
        }
        for (PersonChoice choice : choices) {
            choice.selected = selectedIds == null ? choice.privacySuggested : containsAny(choice, selectedIds);
        }
        return choices;
    }

    private String ageNoteForChoice(PersonChoice choice) {
        float weightedPMinor = 0f;
        float pMinorWeight = 0f;
        Float displayAge = null;
        for (VideoBlurProcessor.Person person : choice.people) {
            float confidence = estimateConfidence(person);
            weightedPMinor += person.pMinor * confidence;
            pMinorWeight += confidence;
            if (person.estimatedAge != null && !person.estimatedAge.isNaN()) {
                displayAge = displayAge == null
                        ? person.estimatedAge
                        : Math.max(displayAge, person.estimatedAge);
            }
        }

        float pMinor = pMinorWeight > 0f ? weightedPMinor / pMinorWeight : 1f;
        return AgeDisplayFormatter.format(displayAge, pMinor);
    }

    private float estimateConfidence(VideoBlurProcessor.Person person) {
        if (person.estimatedAge != null && !person.estimatedAge.isNaN()) {
            return Math.max(0.20f, Math.min(1f, Math.abs(person.estimatedAge - 18f) / 7f));
        }
        return Math.max(0.20f, Math.min(1f, Math.abs(person.pMinor - 0.5f) * 2f));
    }

    private void addSelectionAction(LinearLayout row, String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(20, 20, 20));
        button.setTextSize(12);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(faceBadgeBackground(true));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, lp);
    }

    private void setAllChoices(List<PersonChoice> choices, boolean selected) {
        for (PersonChoice choice : choices) choice.selected = selected;
    }

    private void selectSuggestedChoices(List<PersonChoice> choices) {
        for (PersonChoice choice : choices) choice.selected = choice.privacySuggested;
    }

    private void invertChoices(List<PersonChoice> choices) {
        for (PersonChoice choice : choices) choice.selected = !choice.selected;
    }

    private boolean containsAny(PersonChoice choice, Set<Integer> ids) {
        for (VideoBlurProcessor.Person person : choice.people) {
            if (ids.contains(person.trackingId)) return true;
        }
        return false;
    }

    private Set<Integer> selectedPersonIds(List<PersonChoice> choices) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (PersonChoice choice : choices) {
            if (!choice.selected) continue;
            for (VideoBlurProcessor.Person person : choice.people) {
                ids.add(person.trackingId);
            }
        }
        return ids;
    }

    private Set<Integer> noTracksSelected() {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(VideoBlurProcessor.NO_TRACKS_SELECTED);
        return ids;
    }

    private long averageFaceHash(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0L;
        Bitmap tiny = Bitmap.createScaledBitmap(bitmap, 8, 8, true);
        int[] pixels = new int[64];
        tiny.getPixels(pixels, 0, 8, 0, 0, 8, 8);
        if (tiny != bitmap) tiny.recycle();
        int[] luma = new int[64];
        int sum = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int y = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            luma[i] = y;
            sum += y;
        }
        int avg = sum / luma.length;
        long hash = 0L;
        for (int i = 0; i < luma.length; i++) {
            if (luma[i] >= avg) hash |= (1L << i);
        }
        return hash;
    }

    private GradientDrawable faceChoiceBackground(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(8));
        bg.setColor(selected ? getColor(R.color.blurr_accent) : Color.rgb(24, 24, 27));
        bg.setStroke(dp(1), selected ? Color.TRANSPARENT : getColor(R.color.blurr_stroke));
        return bg;
    }

    private GradientDrawable faceBadgeBackground(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(100));
        bg.setColor(selected ? Color.WHITE : Color.argb(190, 0, 0, 0));
        return bg;
    }

    private void confirmBlurAllVideo() {
        if (selectedItem == null || !selectedItem.isVideo) return;
        final Uri uri = editableVideoUri(selectedItem);
        new AlertDialog.Builder(this)
                .setTitle("Blur all faces")
                .setMessage("Process this video and blur every detected face? This can take a while.")
                .setPositiveButton("Blur all", (d, w) -> applyVideoBlur(uri, Collections.emptySet()))
                .setNeutralButton("Privacy blur", (d, w) -> applyPrivacyVideoBlur(uri))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Transcodes the video with the chosen people blurred and saves a new copy to Photos. */
    private void applyVideoBlur(Uri uri, Set<Integer> blurIds) {
        progressDialog = showProgress("Blurring video… this can take a while");
        videoProcessor.apply(uri, blurIds, new VideoBlurProcessor.ApplyCallback() {
            @Override
            public void onProgress(float f) {
                setProgressMessage("Blurring… " + Math.round(f * 100) + "%");
            }

            @Override
            public void onComplete(Uri outputUri) {
                dismissProgress();
                Toast.makeText(GalleryReviewActivity.this,
                        "Saved blurred video to Blurr gallery", Toast.LENGTH_LONG).show();
                loadGallery();
            }

            @Override
            public void onError(String message) {
                dismissProgress();
                Toast.makeText(GalleryReviewActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Transcodes the video with fail-closed minor/unknown privacy blur. */
    private void applyPrivacyVideoBlur(Uri uri) {
        progressDialog = showProgress("Privacy blurring videoâ€¦ this can take a while");
        videoProcessor.applyPrivacy(uri, new VideoBlurProcessor.ApplyCallback() {
            @Override
            public void onProgress(float f) {
                setProgressMessage("Privacy blurringâ€¦ " + Math.round(f * 100) + "%");
            }

            @Override
            public void onComplete(Uri outputUri) {
                dismissProgress();
                Toast.makeText(GalleryReviewActivity.this,
                        "Saved privacy-blurred video to Blurr gallery", Toast.LENGTH_LONG).show();
                loadGallery();
            }

            @Override
            public void onError(String message) {
                dismissProgress();
                Toast.makeText(GalleryReviewActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private AlertDialog showProgress(String message) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .create();
        d.show();
        return d;
    }

    private void setProgressMessage(String message) {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.setMessage(message);
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    // ---------------------------------------------------------------- data

    private void loadThumb(Uri uri, Size size, ImageView target) {
        io.execute(() -> {
            try {
                Bitmap bmp;
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    String path = uri.getPath();
                    if (path == null) return;
                    File file = new File(path);
                    if (file.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                        bmp = ThumbnailUtils.createVideoThumbnail(file, size, null);
                    } else {
                        bmp = decodeFileThumbnail(file, Math.max(size.getWidth(), size.getHeight()));
                    }
                } else {
                    bmp = getContentResolver().loadThumbnail(uri, size, null);
                }
                if (bmp == null) return;
                runOnUiThread(() -> {
                    if (binding != null) target.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private Bitmap decodeFileThumbnail(File file, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        int larger = Math.max(bounds.outWidth, bounds.outHeight);
        while (larger / sample > maxDim) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private void queryPrivateInto(File dir, boolean isVideo) {
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return isVideo ? lower.endsWith(".mp4") : lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files == null) return;
        for (File file : files) {
            long editId = MediaSaver.idFromDisplayName(file.getName());
            items.add(new MediaItem(Uri.fromFile(file), isVideo, file.lastModified(), editId));
        }
    }

    private void queryInto(Uri collection, String pathColumn, String relPath, boolean isVideo) {
        String[] projection = {
                BaseColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
        };
        String selection = pathColumn + " LIKE ?";
        String[] args = {relPath + "/%"};
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, null)) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(BaseColumns._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            while (c.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(collection, c.getLong(idIdx));
                long editId = MediaSaver.idFromDisplayName(c.getString(nameIdx));
                items.add(new MediaItem(uri, isVideo, c.getLong(dateIdx), editId));
            }
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static class MediaItem {
        final Uri uri;
        final boolean isVideo;
        final long dateAdded;
        final long editId; // timestamp key into BlurEditStore, or -1

        MediaItem(Uri uri, boolean isVideo, long dateAdded, long editId) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.dateAdded = dateAdded;
            this.editId = editId;
        }
    }

    private static class PersonChoice {
        final long hash;
        final List<VideoBlurProcessor.Person> people = new ArrayList<>();
        boolean privacySuggested = false;
        boolean selected = true;

        PersonChoice(long hash) {
            this.hash = hash;
        }
    }

    @Override
    protected void onDestroy() {
        stopPlaybackAndReset();
        clearEditState();
        dismissProgress();
        if (videoProcessor != null) videoProcessor.release();
        io.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
