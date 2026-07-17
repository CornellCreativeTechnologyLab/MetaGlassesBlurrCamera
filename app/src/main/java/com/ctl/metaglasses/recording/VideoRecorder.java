package com.ctl.metaglasses.recording;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;

/**
 * Records the (already blurred) camera frames to an MP4 in the gallery, with sound from the phone
 * microphone.
 *
 * The glasses camera stream carries no audio (the DAT SDK exposes video frames only), so audio is
 * captured from the phone's mic via {@link AudioRecord}, encoded to AAC, and muxed as a second track
 * alongside the H.264 video. Video frames are ARGB bitmaps converted to YUV420 and fed to a
 * MediaCodec encoder. Both tracks are written to a single {@link MediaMuxer}; because the muxer must
 * have every track added before it starts, we only start it once both encoders have reported their
 * output format. If RECORD_AUDIO isn't granted (or mic init fails) we record video-only.
 *
 * All muxer access is guarded by {@link #muxerLock} since video drains on the recorder thread while
 * audio drains on its own thread.
 */
public class VideoRecorder {

    private static final String TAG = "VideoRecorder";
    private static final String VIDEO_MIME = "video/avc";
    private static final int FRAME_RATE = 24;

    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_COUNT = 1;
    private static final int AUDIO_BIT_RATE = 96_000;

    private final Context ctx;
    private HandlerThread thread;
    private Handler handler;

    private MediaCodec encoder;             // video
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private Uri outputUri;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private final Object muxerLock = new Object();
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean muxerStarted;

    private long startNs = -1;
    private int width, height;
    private int[] argb;
    private volatile boolean ready;

    // Audio
    private boolean audioEnabled;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning;

    public VideoRecorder(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public boolean isReady() {
        return ready;
    }

    /** Initializes the encoder(s) + output for the given frame size (call once, on the first frame). */
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
                initAudio(); // best-effort; leaves audioEnabled=false if unavailable
                ready = true;
                if (audioEnabled) startAudioThread();
            } catch (Exception e) {
                Log.e(TAG, "start failed: " + e.getMessage(), e);
            }
        });
    }

    private void initEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        // The frames arriving from the glasses are already lossy (Bluetooth-compressed), so keep the
        // re-encode bitrate generous — a low bitrate here adds a second layer of blocky grain on top.
        // ~8 bits/pixel (capped at 12 Mbps) is plenty for 720p and preserves the source detail.
        int bitRate = (int) Math.min(12_000_000L, width * (long) height * 8);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(VIDEO_MIME);
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

    /** Sets up the mic + AAC encoder. On any failure, leaves audioEnabled=false (video-only). */
    private void initAudio() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO not granted; recording video-only.");
            return;
        }
        try {
            MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            audioEncoder.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = SAMPLE_RATE; // sane fallback
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            audioEnabled = true;
        } catch (Exception e) {
            Log.w(TAG, "Audio init failed; recording video-only. " + e.getMessage());
            releaseAudio();
            audioEnabled = false;
        }
    }

    private void startAudioThread() {
        audioRunning = true;
        audioThread = new Thread(this::audioLoop, "VideoRecorderAudio");
        audioThread.start();
    }

    /** Captures PCM from the mic, encodes AAC, and writes it to the muxer until stopped. */
    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long totalSamples = 0;
        boolean inputDone = false;
        try {
            audioRecord.startRecording();
            while (true) {
                if (!inputDone) {
                    int inIndex = audioEncoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = audioEncoder.getInputBuffer(inIndex);
                        long ptsUs = 1_000_000L * totalSamples / SAMPLE_RATE;
                        if (!audioRunning) {
                            audioEncoder.queueInputBuffer(
                                    inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            inBuf.clear();
                            int read = audioRecord.read(inBuf, inBuf.capacity());
                            if (read > 0) {
                                audioEncoder.queueInputBuffer(inIndex, 0, read, ptsUs, 0);
                                totalSamples += read / 2; // 16-bit mono => 2 bytes/sample
                            } else {
                                audioEncoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0);
                            }
                        }
                    }
                }

                int outIndex = audioEncoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (muxerLock) {
                        audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                        maybeStartMuxer();
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer outBuf = audioEncoder.getOutputBuffer(outIndex);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && outBuf != null) {
                        synchronized (muxerLock) {
                            if (muxerStarted && audioTrackIndex >= 0) {
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                muxer.writeSampleData(audioTrackIndex, outBuf, info);
                            }
                        }
                    }
                    audioEncoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "audioLoop failed: " + e.getMessage());
        }
    }

    /** Starts the muxer once every expected track (video, and audio when enabled) has been added. */
    private void maybeStartMuxer() {
        if (muxerStarted) return;
        if (videoTrackIndex < 0) return;
        if (audioEnabled && audioTrackIndex < 0) return;
        muxer.start();
        muxerStarted = true;
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
                synchronized (muxerLock) {
                    videoTrackIndex = muxer.addTrack(encoder.getOutputFormat());
                    maybeStartMuxer();
                }
            } else if (out >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(out);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0 && encoded != null) {
                    synchronized (muxerLock) {
                        if (muxerStarted && videoTrackIndex >= 0) {
                            encoded.position(bufferInfo.offset);
                            encoded.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, encoded, bufferInfo);
                        }
                    }
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
                // Finish audio first so its final samples are written before the muxer stops.
                stopAudioAndJoin();
                if (encoder != null) drain(true);
                synchronized (muxerLock) {
                    if (muxerStarted) muxer.stop();
                    ok = muxerStarted;
                }
            } catch (Exception e) {
                Log.e(TAG, "stop/drain failed: " + e.getMessage());
            }
            releaseAudio();
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

    private void stopAudioAndJoin() {
        if (!audioEnabled) return;
        audioRunning = false; // audioLoop will send EOS, drain, and exit
        if (audioThread != null) {
            try {
                audioThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
    }

    private void releaseAudio() {
        try {
            if (audioRecord != null) {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) audioRecord.stop();
                audioRecord.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
            }
        } catch (Exception ignored) {
        }
        audioRecord = null;
        audioEncoder = null;
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
