package com.ctl.metaglasses.vision;

import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Blurr face map" — the custom sidecar format that makes blurring non-destructive.
 *
 * A capture is stored as three files keyed by its timestamp id (see {@link BlurEditStore}):
 *   - the shareable, already-blurred JPEG (in the MediaStore gallery),
 *   - the untouched original JPEG (kept privately on the device), and
 *   - a {@code faces.json} document described by this class.
 *
 * The map records, for the original's pixel space, each detected face box plus whether it is
 * currently blurred. Because the original is preserved, a face can be toggled on/off and the
 * blurred result re-rendered/exported at any time — the blur is a layer, not baked-in.
 *
 * Serialized shape:
 * <pre>
 * { "format": "blurr.facemap", "version": 1, "width": W, "height": H,
 *   "faces": [ { "left":.., "top":.., "right":.., "bottom":.., "blurred": true }, ... ] }
 * </pre>
 */
public class FaceMap {

    public static final String FORMAT = "blurr.facemap";
    public static final int VERSION = 1;

    public int width;
    public int height;
    public final List<Face> faces = new ArrayList<>();

    public static class Face {
        public int left, top, right, bottom;
        public boolean blurred;
        /** Face-outline polygon {x0,y0,...} in image px, or null (then blur uses an oval in the box). */
        public float[] contour;

        public Face(int left, int top, int right, int bottom, boolean blurred) {
            this(left, top, right, bottom, blurred, null);
        }

        public Face(int left, int top, int right, int bottom, boolean blurred, float[] contour) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.blurred = blurred;
            this.contour = contour;
        }

        public Rect rect() {
            return new Rect(left, top, right, bottom);
        }
    }

    public FaceMap(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** Builds a map from detected boxes; every face starts blurred (matches capture-time behavior). */
    public static FaceMap fromBoxes(int width, int height, Rect[] boxes) {
        FaceMap map = new FaceMap(width, height);
        if (boxes != null) {
            for (Rect r : boxes) {
                map.faces.add(new Face(r.left, r.top, r.right, r.bottom, true));
            }
        }
        return map;
    }

    /** Builds a map from detected regions (box + contour); every face starts blurred. */
    public static FaceMap fromRegions(int width, int height, FaceRegion[] regions) {
        FaceMap map = new FaceMap(width, height);
        if (regions != null) {
            for (FaceRegion r : regions) {
                map.faces.add(new Face(r.box.left, r.box.top, r.box.right, r.box.bottom, true,
                        r.contour));
            }
        }
        return map;
    }

    public int blurredCount() {
        int n = 0;
        for (Face f : faces) if (f.blurred) n++;
        return n;
    }

    public String toJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("width", width);
            root.put("height", height);
            JSONArray arr = new JSONArray();
            for (Face f : faces) {
                JSONObject o = new JSONObject();
                o.put("left", f.left);
                o.put("top", f.top);
                o.put("right", f.right);
                o.put("bottom", f.bottom);
                o.put("blurred", f.blurred);
                if (f.contour != null && f.contour.length >= 6) {
                    JSONArray c = new JSONArray();
                    for (float v : f.contour) c.put(Math.round(v));
                    o.put("contour", c);
                }
                arr.put(o);
            }
            root.put("faces", arr);
        } catch (JSONException e) {
            // JSONObject.put only throws on null keys, which we never pass.
        }
        return root.toString();
    }

    public static FaceMap fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        FaceMap map = new FaceMap(root.optInt("width"), root.optInt("height"));
        JSONArray arr = root.optJSONArray("faces");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                float[] contour = null;
                JSONArray c = o.optJSONArray("contour");
                if (c != null && c.length() >= 6) {
                    contour = new float[c.length()];
                    for (int k = 0; k < c.length(); k++) contour[k] = (float) c.optDouble(k);
                }
                map.faces.add(new Face(
                        o.optInt("left"), o.optInt("top"),
                        o.optInt("right"), o.optInt("bottom"),
                        o.optBoolean("blurred", true), contour));
            }
        }
        return map;
    }
}
