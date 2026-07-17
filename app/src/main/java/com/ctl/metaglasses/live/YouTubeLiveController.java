package com.ctl.metaglasses.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sets up a YouTube live broadcast via the Data API v3, then hands back the RTMP ingest URL for
 * {@link LiveStreamer} to push to.
 *
 * Auth: Google Sign-In grants the {@code youtube} scope; {@link GoogleAuthUtil} exchanges the signed-in
 * account for an OAuth access token that authorizes the REST calls (done directly over HTTPS to keep
 * the dependency footprint small — no google-api-services-youtube client).
 *
 * goLive() performs: liveStreams.insert (get RTMP ingest URL) → liveBroadcasts.insert (auto start/stop)
 * → liveBroadcasts.bind. With enableAutoStart, YouTube flips the broadcast to "live" as soon as it
 * receives the stream, so no manual transition is needed. endLive() best-effort completes it.
 *
 * Requires a Google Cloud OAuth client registered to this app's package + signing SHA-1, YouTube Data
 * API v3 enabled, and a live-streaming-enabled channel.
 */
public class YouTubeLiveController {

    private static final String TAG = "YouTubeLive";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube";
    private static final String TOKEN_SCOPE = "oauth2:" + SCOPE;
    private static final String API = "https://www.googleapis.com/youtube/v3";

    public interface Callback {
        void onLiveReady(String rtmpUrl, String broadcastId, String watchUrl);
        void onError(String message);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public YouTubeLiveController(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** A sign-in client that requests the YouTube scope; launch its signInIntent from the Activity. */
    public GoogleSignInClient signInClient(Context activityContext) {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(SCOPE))
                .build();
        return GoogleSignIn.getClient(activityContext, options);
    }

    public GoogleSignInAccount lastSignedIn() {
        return GoogleSignIn.getLastSignedInAccount(ctx);
    }

    /** Creates the stream + broadcast and returns the RTMP URL. Network runs off the main thread. */
    public void goLive(GoogleSignInAccount account, String title, Callback cb) {
        if (account == null || account.getAccount() == null) {
            cb.onError("Not signed in to YouTube.");
            return;
        }
        io.execute(() -> {
            try {
                String token = GoogleAuthUtil.getToken(ctx, account.getAccount(), TOKEN_SCOPE);

                // 1) liveStreams.insert -> RTMP ingest address + stream key.
                JSONObject streamBody = new JSONObject()
                        .put("snippet", new JSONObject().put("title", title))
                        .put("cdn", new JSONObject()
                                .put("frameRate", "variable")
                                .put("ingestionType", "rtmp")
                                .put("resolution", "variable"));
                JSONObject stream = post(API + "/liveStreams?part=snippet%2Ccdn", token, streamBody);
                String streamId = stream.getString("id");
                JSONObject ingestion = stream.getJSONObject("cdn").getJSONObject("ingestionInfo");
                String rtmpUrl = ingestion.getString("ingestionAddress") + "/"
                        + ingestion.getString("streamName");

                // 2) liveBroadcasts.insert -> broadcast that auto-starts when the stream arrives.
                JSONObject broadcastBody = new JSONObject()
                        .put("snippet", new JSONObject()
                                .put("title", title)
                                .put("scheduledStartTime", Instant.now().plusSeconds(20).toString()))
                        .put("status", new JSONObject()
                                .put("privacyStatus", "unlisted")
                                .put("selfDeclaredMadeForKids", false))
                        .put("contentDetails", new JSONObject()
                                .put("enableAutoStart", true)
                                .put("enableAutoStop", true));
                JSONObject broadcast = post(
                        API + "/liveBroadcasts?part=snippet%2Cstatus%2CcontentDetails",
                        token, broadcastBody);
                String broadcastId = broadcast.getString("id");

                // 3) bind broadcast <-> stream.
                post(API + "/liveBroadcasts/bind?id=" + broadcastId + "&streamId=" + streamId
                        + "&part=id%2CcontentDetails", token, null);

                String watchUrl = "https://youtu.be/" + broadcastId;
                main.post(() -> cb.onLiveReady(rtmpUrl, broadcastId, watchUrl));
            } catch (Exception e) {
                Log.e(TAG, "goLive failed: " + e.getMessage(), e);
                main.post(() -> cb.onError(friendly(e)));
            }
        });
    }

    /** Best-effort: transition the broadcast to complete. */
    public void endLive(GoogleSignInAccount account, String broadcastId) {
        if (account == null || account.getAccount() == null || broadcastId == null) return;
        io.execute(() -> {
            try {
                String token = GoogleAuthUtil.getToken(ctx, account.getAccount(), TOKEN_SCOPE);
                post(API + "/liveBroadcasts/transition?broadcastStatus=complete&id=" + broadcastId
                        + "&part=status", token, null);
            } catch (Exception e) {
                Log.w(TAG, "endLive failed (may auto-stop): " + e.getMessage());
            }
        });
    }

    private JSONObject post(String urlStr, String token, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);
            byte[] payload = (body != null ? body.toString() : "{}").getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + text);
            }
            return new JSONObject(text);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name())) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.contains("liveStreamingNotEnabled") || m.contains("not enabled")) {
            return "This YouTube channel isn't enabled for live streaming yet.";
        }
        if (m.contains("insufficientPermissions") || m.contains("403")) {
            return "YouTube permission denied — check the OAuth scope/consent.";
        }
        return "YouTube live setup failed: " + m;
    }
}
