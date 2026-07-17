package com.ctl.metaglasses.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Private, on-device storage for the non-destructive blur data of a capture.
 *
 * Everything is keyed by the capture's timestamp id (the same id used in the gallery file name
 * {@code Blurr_<id>.jpg}). For each id we keep, under the app's private external files dir:
 *   edits/&lt;id&gt;/original.jpg  — the untouched frame (never leaves the device unless exported)
 *   edits/&lt;id&gt;/faces.json    — the {@link FaceMap}
 *
 * The blurred, shareable JPEG lives separately in the MediaStore gallery. Only captures that were
 * taken after this feature shipped will have an edit record; {@link #has} lets callers fall back
 * gracefully for older items.
 */
public final class BlurEditStore {

    private static final String TAG = "BlurEditStore";
    private static final int ORIGINAL_QUALITY = 100; // lossless-ish; this is the editing master

    private BlurEditStore() {}

    private static File dirFor(Context ctx, long id) {
        File base = new File(ctx.getExternalFilesDir(null), "edits/" + id);
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        return base;
    }

    public static File originalFile(Context ctx, long id) {
        return new File(dirFor(ctx, id), "original.jpg");
    }

    public static File mapFile(Context ctx, long id) {
        return new File(dirFor(ctx, id), "faces.json");
    }

    /** True when both the original and the face map exist for [id] (i.e. the item is editable). */
    public static boolean has(Context ctx, long id) {
        return originalFile(ctx, id).exists() && mapFile(ctx, id).exists();
    }

    /** Persists the untouched original + face map. Call once at capture time. */
    public static void save(Context ctx, long id, Bitmap original, FaceMap map) {
        try (OutputStream os = new FileOutputStream(originalFile(ctx, id))) {
            original.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_QUALITY, os);
        } catch (Exception e) {
            Log.e(TAG, "save original failed: " + e.getMessage());
        }
        saveMap(ctx, id, map);
    }

    /** Overwrites just the face map (after the user toggles which faces are blurred). */
    public static void saveMap(Context ctx, long id, FaceMap map) {
        try (OutputStream os = new FileOutputStream(mapFile(ctx, id))) {
            os.write(map.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "save map failed: " + e.getMessage());
        }
    }

    public static Bitmap loadOriginal(Context ctx, long id) {
        File f = originalFile(ctx, id);
        if (!f.exists()) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    public static FaceMap loadMap(Context ctx, long id) {
        File f = mapFile(ctx, id);
        if (!f.exists()) return null;
        try (Scanner s = new Scanner(f, StandardCharsets.UTF_8.name())) {
            s.useDelimiter("\\A");
            String json = s.hasNext() ? s.next() : "";
            return FaceMap.fromJson(json);
        } catch (Exception e) {
            Log.e(TAG, "load map failed: " + e.getMessage());
            return null;
        }
    }
}
