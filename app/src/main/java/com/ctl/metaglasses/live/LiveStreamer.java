package com.ctl.metaglasses.live;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.rtmp.rtmp.RtmpClient;

import java.nio.ByteBuffer;

/**
 * Streams the (already blurred) glasses frames + phone-mic audio to an RTMP endpoint (e.g. YouTube).
 *
 * Same encoding path as {@link com.ctl.metaglasses.recording.VideoRecorder} — H.264 video from
 * ARGB→YUV frames and AAC audio from the mic — but instead of muxing to an MP4 the encoder output is
 * pushed to a {@link RtmpClient}. The client is configured with SPS/PPS from the video encoder's
 * output format, then connected; frames are only sent once the RTMP connection succeeds.
 *
 * Lifecycle: {@link #start} (on the first frame, with the ingest URL) → {@link #encode} per frame →
 * {@link #stop}. Status is reported through {@link Listener} on the main thread.
 */
public class LiveStreamer {

    private static final String TAG = "LiveStreamer";
    private static final String VIDEO_MIME = "video/avc";
    private static final int FRAME_RATE = 24;

    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int AUDIO_BIT_RATE = 96_000;

    public interface Listener {
        void onLiveStatus(String status);
        void onLiveStarted();
        void onLiveStopped();
        void onLiveError(String message);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RtmpClient rtmp;

    private String url;
    private HandlerThread thread;
    private Handler handler;

    private MediaCodec encoder; // video
    private final MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
    private long startNs = -1;
    private int width, height;
    private int[] argb;
    private volatile boolean ready;
    private volatile boolean connected;
    private boolean rtmpConfigured;

    // Audio
    private boolean audioEnabled;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning;

    public LiveStreamer(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
        this.rtmp = new RtmpClient(new ConnectChecker() {
            @Override
            public void onConnectionStarted(String url) {
                status("Connecting to live server…");
            }

            @Override
            public void onConnectionSuccess() {
                connected = true;
                status("Live connection established.");
                main.post(listener::onLiveStarted);
            }

            @Override
            public void onConnectionFailed(String reason) {
                connected = false;
                main.post(() -> listener.onLiveError("Live connection failed: " + reason));
            }

            @Override
            public void onDisconnect() {
                connected = false;
                status("Live disconnected.");
            }

            @Override
            public void onAuthError() {
                main.post(() -> listener.onLiveError("Live auth error (check stream key)."));
            }

            @Override
            public void onAuthSuccess() {
                status("Live auth OK.");
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

    /** Initializes encoders and prepares the RTMP push to [ingestUrl] (full URL incl. stream key). */
    public void start(int frameWidth, int frameHeight, String ingestUrl) {
        if (thread != null) return;
        this.url = ingestUrl;
        width = frameWidth & ~1;
        height = frameHeight & ~1;
        thread = new HandlerThread("LiveStreamer");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            try {
                initVideoEncoder();
                initAudio();
                ready = true;
                if (audioEnabled) startAudioThread();
            } catch (Exception e) {
                Log.e(TAG, "start failed: " + e.getMessage(), e);
                main.post(() -> listener.onLiveError("Could not start live: " + e.getMessage()));
            }
        });
    }

    private void initVideoEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        // Streaming bitrate: keep it network-friendly (YouTube 720p sweet spot ~3-4 Mbps).
        int bitRate = (int) Math.min(4_000_000L, width * (long) height * 5);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        encoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }

    private void initAudio() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO not granted; live is video-only.");
            return;
        }
        try {
            MediaFormat af = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            audioEncoder.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = SAMPLE_RATE;
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            audioEnabled = true;
        } catch (Exception e) {
            Log.w(TAG, "Audio init failed; live is video-only. " + e.getMessage());
            releaseAudio();
            audioEnabled = false;
        }
    }

    private void startAudioThread() {
        audioRunning = true;
        audioThread = new Thread(this::audioLoop, "LiveStreamerAudio");
        audioThread.start();
    }

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
                                totalSamples += read / 2;
                            } else {
                                audioEncoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0);
                            }
                        }
                    }
                }
                int outIndex = audioEncoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    ByteBuffer outBuf = audioEncoder.getOutputBuffer(outIndex);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0 && outBuf != null && connected) {
                        rtmp.sendAudio(outBuf, info);
                    }
                    audioEncoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "audioLoop failed: " + e.getMessage());
        }
    }

    /** Queues a frame for encoding + streaming. Safe before ready (frames drop). */
    public void encode(Bitmap bitmap) {
        final Handler h = handler;
        if (h == null || bitmap == null) return;
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
        drainVideo();
    }

    private void drainVideo() {
        while (true) {
            int out = encoder.dequeueOutputBuffer(videoInfo, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                configureAndConnect(encoder.getOutputFormat());
            } else if (out >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(out);
                boolean config = (videoInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && videoInfo.size > 0 && encoded != null && connected) {
                    rtmp.sendVideo(encoded, videoInfo);
                }
                encoder.releaseOutputBuffer(out, false);
            }
        }
    }

    /** Feeds SPS/PPS to the RTMP client and opens the connection (once). */
    private void configureAndConnect(MediaFormat outputFormat) {
        if (rtmpConfigured) return;
        rtmpConfigured = true;
        ByteBuffer sps = outputFormat.getByteBuffer("csd-0");
        ByteBuffer pps = outputFormat.getByteBuffer("csd-1");
        if (sps == null || pps == null) {
            main.post(() -> listener.onLiveError("Missing H.264 config; cannot go live."));
            return;
        }
        // vps is only used for H.265; pass an empty buffer for H.264.
        rtmp.setVideoInfo(sps, pps, ByteBuffer.allocate(0));
        rtmp.setAudioInfo(SAMPLE_RATE, false);
        status("Opening RTMP connection…");
        rtmp.connect(url);
    }

    /** Stops streaming and tears everything down. onDone runs on the main thread. */
    public void stop(Runnable onDone) {
        final Handler h = handler;
        if (h == null) {
            if (onDone != null) onDone.run();
            return;
        }
        ready = false;
        h.post(() -> {
            try {
                stopAudioAndJoin();
            } catch (Exception ignored) {
            }
            try {
                rtmp.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "rtmp disconnect failed: " + e.getMessage());
            }
            connected = false;
            releaseAudio();
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Exception ignored) {
            }
            encoder = null;
            if (thread != null) thread.quitSafely();
            main.post(() -> {
                listener.onLiveStopped();
                if (onDone != null) onDone.run();
            });
        });
    }

    private void stopAudioAndJoin() {
        if (!audioEnabled) return;
        audioRunning = false;
        if (audioThread != null) {
            try {
                audioThread.join(1500);
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

    private void status(String s) {
        main.post(() -> listener.onLiveStatus(s));
    }

    /** ARGB pixels -> YUV420 (BT.601), respecting the encoder image's plane strides. */
    private void fillYuv(Image image, int[] pixels, int srcW, int srcH) {
        int w = width, h = height;
        if (argb == null || argb.length < w * h) argb = new int[w * h];
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
