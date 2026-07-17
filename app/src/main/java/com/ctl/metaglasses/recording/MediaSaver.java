package com.ctl.metaglasses.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.ctl.metaglasses.vision.BlurEditStore;
import com.ctl.metaglasses.vision.FaceMap;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Saves captured stills into the app's own gallery album.
 *
 * Everything Blurr captures lives under a dedicated "Blurr" album:
 *   - Photos -> Pictures/Blurr
 *   - Videos -> Movies/Blurr (see {@link VideoRecorder})
 * so it shows up as its own album, separate from the phone's camera roll.
 */
public final class MediaSaver {

    private static final String TAG = "MediaSaver";
    public static final String ALBUM = "Blurr";
    public static final String PHOTO_PATH = "Pictures/" + ALBUM;
    public static final String VIDEO_PATH = "Movies/" + ALBUM;
    // Camera-roll album (what most people call "the phone gallery").
    public static final String DCIM_PATH = "DCIM/" + ALBUM;

    private MediaSaver() {}

    /** Compresses [bitmap] to JPEG in Pictures/Blurr. onDone runs on the main thread. */
    public static void savePhoto(Context context, Bitmap bitmap, Runnable onDone) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                ContentValues values = new ContentValues();
                String name = "Blurr_" + System.currentTimeMillis() + ".jpg";
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_PATH);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                ContentResolver resolver = app.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("insert returned null");
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } catch (Exception e) {
                Log.e(TAG, "savePhoto failed: " + e.getMessage());
            }
            if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
        }).start();
    }

    /**
     * Saves a photo non-destructively: the [blurred] JPEG goes to the shareable Pictures/Blurr
     * gallery, while the untouched [original] and a {@link FaceMap} built from [boxes] are stashed
     * privately (keyed by the same timestamp id) so the blur can be toggled/exported later.
     */
    public static void savePhotoNonDestructive(Context context, Bitmap blurred, Bitmap original,
                                               Rect[] boxes, Runnable onDone) {
        final Context app = context.getApplicationContext();
        final long id = System.currentTimeMillis();
        new Thread(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Blurr_" + id + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_PATH);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                ContentResolver resolver = app.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("insert returned null");
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    blurred.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);

                // The non-destructive editing record (original + face map).
                FaceMap map = FaceMap.fromBoxes(original.getWidth(), original.getHeight(), boxes);
                BlurEditStore.save(app, id, original, map);
            } catch (Exception e) {
                Log.e(TAG, "savePhotoNonDestructive failed: " + e.getMessage());
            }
            if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
        }).start();
    }

    /** Parses the timestamp id out of a "Blurr_<id>.jpg/.mp4" display name, or -1 if not ours. */
    public static long idFromDisplayName(String displayName) {
        if (displayName == null) return -1;
        int us = displayName.indexOf('_');
        int dot = displayName.lastIndexOf('.');
        if (us < 0 || dot <= us) return -1;
        try {
            return Long.parseLong(displayName.substring(us + 1, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Writes a JPEG bitmap into the phone camera roll (DCIM/Blurr). onDone runs on the main thread. */
    public static void saveBitmapToDcim(Context context, Bitmap bitmap, Runnable onDone) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Blurr_" + System.currentTimeMillis() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, DCIM_PATH);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                ContentResolver resolver = app.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("insert returned null");
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } catch (Exception e) {
                Log.e(TAG, "saveBitmapToDcim failed: " + e.getMessage());
            }
            if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
        }).start();
    }

    /**
     * Copies an existing gallery item (photo or video) into the phone camera roll (DCIM/Blurr) so
     * it appears alongside the phone's own camera captures. onResult(true/false) runs on main thread.
     */
    public static void copyToDcim(Context context, Uri src, boolean isVideo,
                                  java.util.function.Consumer<Boolean> onResult) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                ContentResolver resolver = app.getContentResolver();
                Uri collection = isVideo
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                ContentValues values = new ContentValues();
                String name = "Blurr_" + System.currentTimeMillis() + (isVideo ? ".mp4" : ".jpg");
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, DCIM_PATH);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri dst = resolver.insert(collection, values);
                if (dst == null) throw new IllegalStateException("insert returned null");
                try (InputStream in = resolver.openInputStream(src);
                     OutputStream out = resolver.openOutputStream(dst)) {
                    if (in == null || out == null) throw new IllegalStateException("stream null");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(dst, done, null, null);
                ok = true;
            } catch (Exception e) {
                Log.e(TAG, "copyToDcim failed: " + e.getMessage());
            }
            final boolean result = ok;
            if (onResult != null) new Handler(Looper.getMainLooper()).post(() -> onResult.accept(result));
        }).start();
    }
}
