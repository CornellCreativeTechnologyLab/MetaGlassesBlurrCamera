package com.ctl.metaglasses.video;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import com.ctl.metaglasses.vision.BlurRenderer;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline face-blur editor for videos.
 *
 * Unlike the live pipeline, this decodes an existing video frame-by-frame, uses ML Kit face
 * detection with <b>tracking</b> (stable ids = individual people across the clip), and re-encodes a
 * new MP4 with a soft, feathered blur ({@link BlurRenderer}) applied only to the people the caller
 * selects. The audio track is copied through untouched.
 *
 * Two passes:
 *  1. {@link #scan} — walk the video, collect the set of tracked people (id + a thumbnail).
 *  2. {@link #apply} — transcode, blurring only the selected tracking ids, and save to DCIM/Blurr.
 *
 * Tracking ids are deterministic for the same input processed in the same order, so ids reported by
 * {@link #scan} match those used by {@link #apply}. This is CPU heavy and slow by design — it runs on
 * a background thread and reports progress on the main thread.
 *
 * Notes/limits: contour outlines aren't used here (ML Kit tracking is mutually exclusive with contour
 * mode), so the blur is a feathered oval per face. Rotation metadata is carried to the output via the
 * muxer orientation hint; detection assumes the decoded orientation.
 */
public class VideoBlurProcessor {

    private static final String TAG = "VideoBlurProcessor";
    private static final int TIMEOUT_US = 10_000;
    private static final float FACE_PADDING = 0.25f;
    private static final int SCAN_STRIDE = 4; // scan every Nth frame for speed

    public static class Person {
        public final int trackingId;
        public final Bitmap thumbnail;

        Person(int trackingId, Bitmap thumbnail) {
            this.trackingId = trackingId;
            this.thumbnail = thumbnail;
        }
    }

    public interface ScanCallback {
        void onProgress(float fraction);
        void onComplete(List<Person> people);
        void onError(String message);
    }

    public interface ApplyCallback {
        void onProgress(float fraction);
        void onComplete(Uri outputUri);
        void onError(String message);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FaceDetector detector;

    public VideoBlurProcessor(Context context) {
        this.ctx = context.getApplicationContext();
        this.detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking() // stable ids per person across frames
                .setMinFaceSize(0.1f)
                .build());
    }

    public void release() {
        io.shutdownNow();
        detector.close();
    }

    // ------------------------------------------------------------------ scan

    /** Walks the video and reports each distinct tracked person (id + first-seen thumbnail). */
    public void scan(Uri src, ScanCallback cb) {
        io.execute(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec decoder = null;
            Map<Integer, Person> people = new LinkedHashMap<>();
            try {
                extractor.setDataSource(ctx, src, null);
                int track = selectVideoTrack(extractor);
                if (track < 0) throw new IllegalStateException("No video track");
                extractor.selectTrack(track);
                MediaFormat format = extractor.getTrackFormat(track);
                long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) : 0;

                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                decoder.configure(format, null, null, 0);
                decoder.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false, outputDone = false;
                int frame = 0;
                int lastPct = -1;
                while (!outputDone) {
                    if (!inputDone) inputDone = feedDecoder(extractor, decoder);
                    int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    } else if (outIndex >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0 && (frame % SCAN_STRIDE == 0)) {
                            Image image = decoder.getOutputImage(outIndex);
                            if (image != null) {
                                Bitmap bmp = imageToBitmap(image);
                                collectPeople(bmp, people);
                                bmp.recycle();
                                lastPct = reportProgress(cb, info.presentationTimeUs, durationUs, lastPct);
                            }
                        }
                        frame++;
                        decoder.releaseOutputBuffer(outIndex, false);
                        if (eos) outputDone = true;
                    }
                }
                List<Person> result = new ArrayList<>(people.values());
                main.post(() -> cb.onComplete(result));
            } catch (Exception e) {
                Log.e(TAG, "scan failed: " + e.getMessage(), e);
                main.post(() -> cb.onError("Could not scan video: " + e.getMessage()));
            } finally {
                safeRelease(decoder);
                extractor.release();
            }
        });
    }

    private void collectPeople(Bitmap bmp, Map<Integer, Person> people) {
        try {
            List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)));
            for (Face face : faces) {
                Integer id = face.getTrackingId();
                if (id == null || people.containsKey(id)) continue;
                Rect box = pad(face.getBoundingBox(), bmp.getWidth(), bmp.getHeight());
                if (box == null) continue;
                Bitmap thumb = Bitmap.createBitmap(bmp, box.left, box.top, box.width(), box.height());
                people.put(id, new Person(id, thumb));
            }
        } catch (Exception e) {
            Log.w(TAG, "detect (scan) failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ apply

    /**
     * Transcodes [src] into a new MP4 in DCIM/Blurr, blurring faces whose tracking id is in
     * [blurIds]. Pass null/empty to blur everyone detected.
     */
    public void apply(Uri src, Set<Integer> blurIds, ApplyCallback cb) {
        io.execute(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec decoder = null, encoder = null;
            MediaMuxer muxer = null;
            ParcelFileDescriptor pfd = null;
            Uri outUri = null;
            try {
                extractor.setDataSource(ctx, src, null);
                int vTrack = selectVideoTrack(extractor);
                if (vTrack < 0) throw new IllegalStateException("No video track");
                extractor.selectTrack(vTrack);
                MediaFormat inFormat = extractor.getTrackFormat(vTrack);
                int width = inFormat.getInteger(MediaFormat.KEY_WIDTH) & ~1;
                int height = inFormat.getInteger(MediaFormat.KEY_HEIGHT) & ~1;
                long durationUs = inFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? inFormat.getLong(MediaFormat.KEY_DURATION) : 0;
                int rotation = inFormat.containsKey(MediaFormat.KEY_ROTATION)
                        ? inFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
                int frameRate = inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                        ? inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 24;

                decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME));
                decoder.configure(inFormat, null, null, 0);
                decoder.start();

                MediaFormat outFormat = MediaFormat.createVideoFormat("video/avc", width, height);
                outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                outFormat.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(12_000_000L, width * (long) height * 6));
                outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder = MediaCodec.createEncoderByType("video/avc");
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();

                // Output MediaStore entry.
                ContentValues values = new ContentValues();
                String name = "Blurr_" + System.currentTimeMillis() + ".mp4";
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Blurr");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                ContentResolver resolver = ctx.getContentResolver();
                outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outUri == null) throw new IllegalStateException("Could not create output");
                pfd = resolver.openFileDescriptor(outUri, "rw");
                muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                muxer.setOrientationHint(rotation);

                int videoTrackOut = -1;
                boolean muxerStarted = false;
                MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
                boolean inputDone = false, decodeDone = false, encodeDone = false;
                int[] argb = new int[width * height];
                int lastPct = -1;

                while (!encodeDone) {
                    if (!inputDone) inputDone = feedDecoder(extractor, decoder);

                    // Drain decoder -> process -> feed encoder.
                    if (!decodeDone) {
                        int outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                        if (outIndex >= 0) {
                            boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            if (decInfo.size > 0) {
                                Image image = decoder.getOutputImage(outIndex);
                                Bitmap bmp = image != null ? imageToBitmap(image) : null;
                                if (bmp != null) {
                                    blurSelected(bmp, blurIds);
                                    feedEncoderFrame(encoder, bmp, argb, width, height, decInfo.presentationTimeUs);
                                    bmp.recycle();
                                    lastPct = reportProgress(cb, decInfo.presentationTimeUs, durationUs, lastPct);
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false);
                            if (eos) {
                                decodeDone = true;
                                signalEncoderEnd(encoder);
                            }
                        }
                    }

                    // Drain encoder -> muxer.
                    int encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackOut = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } else if (encIndex >= 0) {
                        ByteBuffer encoded = encoder.getOutputBuffer(encIndex);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0;
                        if (encInfo.size > 0 && muxerStarted && encoded != null) {
                            encoded.position(encInfo.offset);
                            encoded.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(videoTrackOut, encoded, encInfo);
                        }
                        encoder.releaseOutputBuffer(encIndex, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encodeDone = true;
                    }
                }

                // Copy the audio track through, untouched.
                copyAudio(src, muxer);

                muxer.stop();
                resolver.update(outUri, pendingDone(), null, null);
                final Uri done = outUri;
                main.post(() -> cb.onComplete(done));
            } catch (Exception e) {
                Log.e(TAG, "apply failed: " + e.getMessage(), e);
                if (outUri != null) {
                    try { ctx.getContentResolver().delete(outUri, null, null); } catch (Exception ignored) {}
                }
                main.post(() -> cb.onError("Could not process video: " + e.getMessage()));
            } finally {
                safeRelease(decoder);
                try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
                try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
                extractor.release();
            }
        });
    }

    private void blurSelected(Bitmap bmp, Set<Integer> blurIds) {
        try {
            List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)));
            if (faces.isEmpty()) return;
            Canvas canvas = new Canvas(bmp);
            for (Face face : faces) {
                Integer id = face.getTrackingId();
                boolean blur = blurIds == null || blurIds.isEmpty() || (id != null && blurIds.contains(id));
                if (!blur) continue;
                Rect box = pad(face.getBoundingBox(), bmp.getWidth(), bmp.getHeight());
                if (box != null) BlurRenderer.blurRegion(bmp, canvas, box, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "detect (apply) failed: " + e.getMessage());
        }
    }

    private static ContentValues pendingDone() {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        return v;
    }

    // ------------------------------------------------------------- codec I/O

    private boolean feedDecoder(MediaExtractor extractor, MediaCodec decoder) {
        int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
        if (inIndex < 0) return false;
        ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
        int size = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
        if (size < 0) {
            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        long pts = extractor.getSampleTime();
        decoder.queueInputBuffer(inIndex, 0, size, pts, 0);
        extractor.advance();
        return false;
    }

    private void feedEncoderFrame(MediaCodec encoder, Bitmap bmp, int[] argb, int w, int h, long ptsUs) {
        int index = encoder.dequeueInputBuffer(TIMEOUT_US);
        if (index < 0) return;
        Image image = encoder.getInputImage(index);
        if (image == null) {
            encoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
            return;
        }
        fillYuv(image, bmp, argb, w, h);
        encoder.queueInputBuffer(index, 0, w * h * 3 / 2, ptsUs, 0);
    }

    private void signalEncoderEnd(MediaCodec encoder) {
        int index = encoder.dequeueInputBuffer(TIMEOUT_US);
        if (index >= 0) {
            encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    private void copyAudio(Uri src, MediaMuxer muxer) {
        MediaExtractor audio = new MediaExtractor();
        try {
            audio.setDataSource(ctx, src, null);
            int aTrack = -1;
            for (int i = 0; i < audio.getTrackCount(); i++) {
                String mime = audio.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { aTrack = i; break; }
            }
            if (aTrack < 0) return; // no audio, that's fine
            audio.selectTrack(aTrack);
            MediaFormat aFormat = audio.getTrackFormat(aTrack);
            int outTrack = muxer.addTrack(aFormat);

            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int size = audio.readSampleData(buffer, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = audio.getSampleTime();
                info.flags = audio.getSampleFlags();
                muxer.writeSampleData(outTrack, buffer, info);
                audio.advance();
            }
        } catch (Exception e) {
            Log.w(TAG, "audio copy failed (output will be silent): " + e.getMessage());
        } finally {
            audio.release();
        }
    }

    // ---------------------------------------------------------- YUV helpers

    /** YUV_420_888 Image -> ARGB_8888 Bitmap (BT.601), respecting plane strides. */
    private static Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] p = image.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yRow = p[0].getRowStride();
        int uRow = p[1].getRowStride();
        int uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride();
        int vPix = p[2].getPixelStride();
        int[] argb = new int[w * h];
        for (int j = 0; j < h; j++) {
            int uvRow = j >> 1;
            for (int i = 0; i < w; i++) {
                int uvCol = i >> 1;
                int y = (yb.get(j * yRow + i) & 0xFF) - 16;
                int u = (ub.get(uvRow * uRow + uvCol * uPix) & 0xFF) - 128;
                int v = (vb.get(uvRow * vRow + uvCol * vPix) & 0xFF) - 128;
                int r = (298 * y + 409 * v + 128) >> 8;
                int g = (298 * y - 100 * u - 208 * v + 128) >> 8;
                int b = (298 * y + 516 * u + 128) >> 8;
                argb[j * w + i] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
        return bmp;
    }

    /** ARGB Bitmap -> encoder YUV420 Image (BT.601), respecting plane strides. */
    private static void fillYuv(Image image, Bitmap bmp, int[] argb, int w, int h) {
        bmp.getPixels(argb, 0, w, 0, 0, w, h);
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = argb[j * w + i];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int y = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                yBuf.put(j * yRow + i, (byte) y);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                    int row = j / 2, col = i / 2;
                    uBuf.put(row * uRow + col * uPix, (byte) u);
                    vBuf.put(row * vRow + col * vPix, (byte) v);
                }
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static Rect pad(Rect r, int w, int h) {
        int padX = (int) (r.width() * FACE_PADDING);
        int padY = (int) (r.height() * FACE_PADDING);
        int left = Math.max(0, Math.min(r.left - padX, w - 1));
        int top = Math.max(0, Math.min(r.top - padY, h - 1));
        int right = Math.max(left + 1, Math.min(r.right + padX, w));
        int bottom = Math.max(top + 1, Math.min(r.bottom + padY, h));
        return (right > left && bottom > top) ? new Rect(left, top, right, bottom) : null;
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private static void safeRelease(MediaCodec codec) {
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }
    }

    // Progress helpers: post to the main thread only when the whole-percent changes.
    private int reportProgress(ScanCallback cb, long ptsUs, long durationUs, int lastPct) {
        if (durationUs <= 0) return lastPct;
        int pct = (int) Math.min(100, 100 * ptsUs / durationUs);
        if (pct != lastPct) {
            final float p = pct / 100f;
            main.post(() -> cb.onProgress(p));
        }
        return pct;
    }

    private int reportProgress(ApplyCallback cb, long ptsUs, long durationUs, int lastPct) {
        if (durationUs <= 0) return lastPct;
        int pct = (int) Math.min(100, 100 * ptsUs / durationUs);
        if (pct != lastPct) {
            final float p = pct / 100f;
            main.post(() -> cb.onProgress(p));
        }
        return pct;
    }
}
