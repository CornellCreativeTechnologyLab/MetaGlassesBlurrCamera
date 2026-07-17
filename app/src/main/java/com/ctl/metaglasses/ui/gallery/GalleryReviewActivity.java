package com.ctl.metaglasses.ui.gallery;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ctl.metaglasses.R;
import com.ctl.metaglasses.databinding.ActivityGalleryReviewBinding;
import com.ctl.metaglasses.recording.MediaSaver;
import com.ctl.metaglasses.vision.BlurEditStore;
import com.ctl.metaglasses.vision.BlurRenderer;
import com.ctl.metaglasses.vision.FaceMap;
import com.ctl.metaglasses.vision.FaceScanner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
        binding.btnSave.setOnClickListener(v -> saveToPhoneGallery());
        binding.btnShare.setOnClickListener(v -> shareCurrent());

        binding.btnPlay.setOnClickListener(v -> togglePlayback());
        configureVideoPlayer();
        configureScrubber();

        binding.btnSelectFaces.setOnClickListener(v -> {
            if (selectedItem != null && selectedItem.isVideo) {
                Toast.makeText(this, "Face editing is available on photos, not videos.",
                        Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Face editing is available on photos, not videos.",
                        Toast.LENGTH_SHORT).show();
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
            java.util.List<Rect> boxes = FaceScanner.detect(bmp);
            FaceMap map = new FaceMap(bmp.getWidth(), bmp.getHeight());
            for (Rect r : boxes) {
                map.faces.add(new FaceMap.Face(r.left, r.top, r.right, r.bottom, false));
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

    /** Saves the current item into the phone camera roll (DCIM/Blurr). */
    private void saveToPhoneGallery() {
        MediaItem item = selectedItem;
        if (item == null) return;

        if (editable && editOriginal != null && editMap != null) {
            Bitmap rendered = BlurRenderer.render(editOriginal, editMap);
            MediaSaver.saveBitmapToDcim(this, rendered, () -> {
                rendered.recycle();
                Toast.makeText(this, "Saved to Photos (DCIM/Blurr)", Toast.LENGTH_SHORT).show();
            });
        } else {
            MediaSaver.copyToDcim(this, item.uri, item.isVideo, ok ->
                    Toast.makeText(this,
                            ok ? "Saved to Photos (DCIM/Blurr)" : "Could not save to Photos",
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
            launchShare(item.uri, item.isVideo ? "video/mp4" : "image/jpeg");
        }
    }

    private void launchShare(Uri uri, String mime) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share"));
    }

    // ---------------------------------------------------------------- data

    private void loadThumb(Uri uri, Size size, ImageView target) {
        io.execute(() -> {
            try {
                Bitmap bmp = getContentResolver().loadThumbnail(uri, size, null);
                runOnUiThread(() -> {
                    if (binding != null) target.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
            }
        });
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

    @Override
    protected void onDestroy() {
        stopPlaybackAndReset();
        clearEditState();
        io.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
