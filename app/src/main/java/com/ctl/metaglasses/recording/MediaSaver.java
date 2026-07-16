package com.ctl.metaglasses.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

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
}
