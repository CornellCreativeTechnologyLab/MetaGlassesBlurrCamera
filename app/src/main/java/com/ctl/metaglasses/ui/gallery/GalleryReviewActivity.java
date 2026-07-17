package com.ctl.metaglasses.ui.gallery;

import android.content.ContentUris;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.ctl.metaglasses.R;
import com.ctl.metaglasses.databinding.ActivityGalleryReviewBinding;
import com.ctl.metaglasses.recording.MediaSaver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen ② — "Blurr" review / gallery.
 *
 * Photos are displayed in the preview ImageView. Videos are prepared and played directly from
 * their MediaStore content Uri using the VideoView in the same player frame.
 */
public class GalleryReviewActivity extends AppCompatActivity {

    private ActivityGalleryReviewBinding binding;

    private boolean selecting = false;
    private boolean playing = false;
    private boolean videoPrepared = false;
    private boolean playWhenPrepared = false;
    private boolean unlocked = false;
    private final boolean[] blurred = {false, true, false};

    private FrameLayout[] faces;
    private ImageView[] checks;
    private MediaItem selectedItem;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<MediaItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGalleryReviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        faces = new FrameLayout[]{binding.face0, binding.face1, binding.face2};
        checks = new ImageView[]{binding.check0, binding.check1, binding.check2};

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnShare.setOnClickListener(v ->
                Toast.makeText(this, "Share — coming soon", Toast.LENGTH_SHORT).show());

        binding.badgeWatermark.setOnClickListener(v -> {
            unlocked = false;
            updateExport();
        });
        binding.badgeAccessible.setOnClickListener(v -> {
            unlocked = true;
            updateExport();
        });

        binding.btnPlay.setOnClickListener(v -> togglePlayback());
        configureVideoPlayer();

        binding.btnSelectFaces.setOnClickListener(v -> {
            selecting = !selecting;
            updateSelecting();
        });
        binding.btnSelectDone.setOnClickListener(v -> {
            selecting = false;
            updateSelecting();
        });
        binding.btnBlurAll.setOnClickListener(v -> {
            for (int i = 0; i < blurred.length; i++) blurred[i] = true;
            selecting = true;
            updateSelecting();
        });

        for (int i = 0; i < faces.length; i++) {
            final int idx = i;
            faces[i].setOnClickListener(v -> {
                blurred[idx] = !blurred[idx];
                updateFaces();
            });
        }

        updateExport();
        updateFaces();
        updateSelecting();
    }

    private void configureVideoPlayer() {
        binding.previewVideo.setOnPreparedListener(mediaPlayer -> {
            if (binding == null || selectedItem == null || !selectedItem.isVideo) return;

            videoPrepared = true;
            binding.btnPlay.setEnabled(true);
            mediaPlayer.setLooping(false);

            if (playWhenPrepared) {
                startPreparedVideo();
            }
        });

        binding.previewVideo.setOnCompletionListener(mediaPlayer -> {
            playing = false;
            playWhenPrepared = false;
            binding.btnPlay.setImageResource(R.drawable.ic_play);
            binding.btnPlay.setContentDescription("Play");

            // Leave the final frame visible, but make the next tap replay from the beginning.
            binding.previewVideo.seekTo(0);
        });

        binding.previewVideo.setOnErrorListener((mediaPlayer, what, extra) -> {
            playing = false;
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

        binding.previewImage.setImageDrawable(null);
        binding.previewImage.setVisibility(View.VISIBLE);
        binding.previewVideo.setVisibility(View.GONE);
        binding.btnPlay.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_play);
        binding.btnPlay.setContentDescription("Play");

        loadThumb(item.uri, new Size(1080, 1080), binding.previewImage);
    }

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
        playing = false;
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
        playing = true;
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_pause);
        binding.btnPlay.setContentDescription("Pause");
    }

    private void pausePlayback() {
        if (binding == null) return;

        playWhenPrepared = false;
        if (binding.previewVideo.isPlaying()) {
            binding.previewVideo.pause();
        }
        playing = false;
        binding.btnPlay.setEnabled(true);
        binding.btnPlay.setImageResource(R.drawable.ic_play);
        binding.btnPlay.setContentDescription("Play");
    }

    private void stopPlaybackAndReset() {
        if (binding == null) return;

        playWhenPrepared = false;
        playing = false;
        videoPrepared = false;
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
        String[] projection = {BaseColumns._ID, MediaStore.MediaColumns.DATE_ADDED};
        String selection = pathColumn + " LIKE ?";
        String[] args = {relPath + "/%"};
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, null)) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(BaseColumns._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            while (c.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(collection, c.getLong(idIdx));
                items.add(new MediaItem(uri, isVideo, c.getLong(dateIdx)));
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

        MediaItem(Uri uri, boolean isVideo, long dateAdded) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.dateAdded = dateAdded;
        }
    }

    private void updateSelecting() {
        int vis = selecting ? View.VISIBLE : View.GONE;
        for (FrameLayout f : faces) f.setVisibility(vis);
        binding.selectHeader.setVisibility(vis);
    }

    private void updateFaces() {
        int count = 0;
        for (int i = 0; i < faces.length; i++) {
            boolean b = blurred[i];
            faces[i].setBackgroundResource(b
                    ? R.drawable.bg_face_box_accent
                    : R.drawable.bg_face_box_white);
            checks[i].setVisibility(b ? View.VISIBLE : View.GONE);
            if (b) count++;
        }
        binding.selectCount.setText(count + " of " + faces.length + " faces blurred · tap to toggle");
    }

    private void updateExport() {
        int accent = ContextCompat.getColor(this, R.color.blurr_accent);
        int dark = Color.parseColor("#111111");
        int muted = Color.parseColor("#99FFFFFF");

        if (unlocked) {
            binding.badgeAccessible.setBackgroundResource(R.drawable.bg_pill_accent);
            binding.badgeAccessible.setTextColor(dark);
            binding.badgeAccessible.setCompoundDrawableTintList(ColorStateList.valueOf(dark));

            binding.badgeWatermark.setBackgroundResource(R.drawable.bg_pill);
            binding.badgeWatermark.setTextColor(muted);
            binding.badgeWatermark.setCompoundDrawableTintList(ColorStateList.valueOf(muted));

            binding.watermark.setVisibility(View.GONE);
        } else {
            binding.badgeWatermark.setBackgroundResource(R.drawable.bg_pill_white);
            binding.badgeWatermark.setTextColor(dark);
            binding.badgeWatermark.setCompoundDrawableTintList(ColorStateList.valueOf(dark));

            binding.badgeAccessible.setBackgroundResource(R.drawable.bg_pill);
            binding.badgeAccessible.setTextColor(muted);
            binding.badgeAccessible.setCompoundDrawableTintList(ColorStateList.valueOf(muted));

            binding.watermark.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        stopPlaybackAndReset();
        io.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}g