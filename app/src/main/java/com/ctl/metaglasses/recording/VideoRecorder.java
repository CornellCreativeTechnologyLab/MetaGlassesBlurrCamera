package com.ctl.metaglasses.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Records the (already blurred) camera frames to an MP4 in the gallery.
 *
 * Frames are ARGB bitmaps; they're converted to YUV420 and fed to an H.264 MediaCodec encoder,
 * muxed into an MP4 written to MediaStore (Movies/Blurr). All encoding runs on a private thread so
 * the camera/UI thread is never blocked.
 */
public class VideoRecorder {

    private static final String TAG = "VideoRecorder";
    private static final String MIME = "video/avc";
    private static final int FRAME_RATE = 24;

    private final Context ctx;
    private HandlerThread thread;
    private Handler handler;

    private MediaCodec encoder;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private Uri outputUri;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private int trackIndex = -1;
    private boolean muxerStarted;
    private long startNs = -1;
    private int width, height;
    private int[] argb;
    private volatile boolean ready;

    public VideoRecorder(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public boolean isReady() {
        return ready;
    }

    /** Initializes the encoder + output for the given frame size (call once, on the first frame). */
    public void start(int frameWidth, int frameHeight) {
        if (thread != null) return; // already starting/started
        width = frameWidth & ~1;   // encoders require even dimensions
        height = frameHeight & ~1;
        thread = new HandlerThread("VideoRecorder");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            try {
                initEncoder();
                ready = true;
            } catch (Exception e) {
                Log.e(TAG, "start failed: " + e.getMessage(), e);
            }
        });
    }

    private void initEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, (int) (width * (long) height * 4));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        ContentValues values = new ContentValues();
        String name = "Blurr_" + System.currentTimeMillis() + ".mp4";
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Blurr");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver resolver = ctx.getContentResolver();
        outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new IllegalStateException("Could not create MediaStore entry");
        pfd = resolver.openFileDescriptor(outputUri, "rw");
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** Queues a frame for encoding. Safe to call before/after the recorder is ready (frames drop). */
    public void encode(Bitmap bitmap) {
        final Handler h = handler;
        if (h == null || bitmap == null) return;
        // Snapshot the pixels now so the recorder thread doesn't race the next frame.
        final int w = bitmap.getWidth();
        final int hgt = bitmap.getHeight();
        final int[] pixels = new int[w * hgt];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, hgt);
        h.post(() -> {
            try {
                doEncode(pixels, w, hgt);
            } catch (Exception e) {
                Log.e(TAG, "encode failed: " + e.getMessage());
            }
        });
    }

    private void doEncode(int[] pixels, int srcW, int srcH) {
        if (encoder == null || !ready) return;
        long now = System.nanoTime();
        if (startNs < 0) startNs = now;
        long ptsUs = (now - startNs) / 1000;

        int index = encoder.dequeueInputBuffer(10_000);
        if (index >= 0) {
            Image image = encoder.getInputImage(index);
            if (image != null) {
                fillYuv(image, pixels, srcW, srcH);
                encoder.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0);
            } else {
                encoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
            }
        }
        drain(false);
    }

    private void drain(boolean endOfStream) {
        if (endOfStream) {
            int index = encoder.dequeueInputBuffer(10_000);
            if (index >= 0) {
                long ptsUs = startNs < 0 ? 0 : (System.nanoTime() - startNs) / 1000;
                encoder.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }
        while (true) {
            int out = encoder.dequeueOutputBuffer(bufferInfo, endOfStream ? 10_000 : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (out >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(out);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0 && muxerStarted && encoded != null) {
                    encoded.position(bufferInfo.offset);
                    encoded.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, encoded, bufferInfo);
                }
                encoder.releaseOutputBuffer(out, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    /** Finishes encoding, finalizes the MP4, and publishes it. onSaved runs on the main thread. */
    public void stop(Runnable onSaved) {
        final Handler h = handler;
        if (h == null) {
            if (onSaved != null) onSaved.run();
            return;
        }
        ready = false;
        h.post(() -> {
            boolean ok = false;
            try {
                if (encoder != null) drain(true);
                if (muxerStarted) muxer.stop();
                ok = muxerStarted;
            } catch (Exception e) {
                Log.e(TAG, "stop/drain failed: " + e.getMessage());
            }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}

            try {
                if (outputUri != null) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    ctx.getContentResolver().update(outputUri, done, null, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "publish failed: " + e.getMessage());
            }

            encoder = null;
            muxer = null;
            if (thread != null) thread.quitSafely();
            if (onSaved != null) new Handler(Looper.getMainLooper()).post(onSaved);
        });
    }

    /** ARGB pixels -> YUV420 (BT.601), respecting the encoder image's plane strides. */
    private void fillYuv(Image image, int[] pixels, int srcW, int srcH) {
        int w = width, h = height;
        if (argb == null || argb.length < w * h) argb = new int[w * h];
        // Nearest-neighbour scale into the encoder size.
        if (srcW == w && srcH == h) {
            System.arraycopy(pixels, 0, argb, 0, w * h);
        } else {
            for (int j = 0; j < h; j++) {
                int sy = j * srcH / h;
                for (int i = 0; i < w; i++) {
                    int sx = i * srcW / w;
                    argb[j * w + i] = pixels[sy * srcW + sx];
                }
            }
        }

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
                    int row = j / 2;
                    int col = i / 2;
                    uBuf.put(row * uRow + col * uPix, (byte) u);
                    vBuf.put(row * vRow + col * vPix, (byte) v);
                }
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
