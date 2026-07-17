# YouTube Live setup (Go Live button)

The app can create a YouTube live broadcast and push the (already blurred) glasses feed + phone-mic
audio to it over RTMP. This requires a one-time setup in **your** Google account — the app cannot do
it for you, and it can't be tested without it.

## What the app does at runtime
1. Tap **Go Live** (only works while the glasses stream is running).
2. Google Sign-In prompts for the YouTube scope.
3. The app calls the YouTube Data API v3: `liveStreams.insert` → `liveBroadcasts.insert`
   (auto start/stop) → `liveBroadcasts.bind`, and gets an RTMP ingest URL.
4. It encodes H.264 video (the blurred frames) + AAC audio (phone mic) and pushes them over RTMP.
5. YouTube auto-transitions the broadcast to **live** once it receives the stream. The broadcast is
   created **unlisted**; change `privacyStatus` in `YouTubeLiveController` if you want public.
6. Tap **Go Live** again to stop; the broadcast is transitioned to complete.

## One-time setup you must do

### 1. Enable live streaming on the channel
Go to <https://youtube.com/live_dashboard>, verify your phone number, and enable live streaming.
New accounts can wait up to 24h before live is available.

### 2. Google Cloud project
1. <https://console.cloud.google.com/> → create or pick a project.
2. **APIs & Services → Library →** enable **"YouTube Data API v3"**.
3. **OAuth consent screen:** configure it, add the scope
   `https://www.googleapis.com/auth/youtube`, and (while the app is in "Testing") add your Google
   account as a **Test user**.
4. **Credentials → Create credentials → OAuth client ID → Android:**
   - Package name: `com.ctl.metaglasses`
   - SHA-1: the fingerprint of the signing key. This app signs both debug and release with the
     **debug keystore**, so use the debug SHA-1:
     ```
     keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" \
       -alias androiddebugkey -storepass android -keypass android
     ```
     Copy the `SHA1:` line into the OAuth client.

No `google-services.json` or client-ID string is needed in the app — Google matches the OAuth client
by package name + SHA-1 automatically.

### 3. Run it
Reinstall, pair, start the stream, grant the **microphone** prompt (for live audio), then tap
**Go Live** and sign in. The broadcast link is shown in a toast and appears in YouTube Studio.

## Code touchpoints
- `live/YouTubeLiveController.java` — Sign-In + Data API REST (create/bind/transition). Change
  `privacyStatus` here (`unlisted` → `public`/`private`).
- `live/LiveStreamer.java` — H.264 + AAC encoders → RTMP push (RootEncoder `RtmpClient`).
- `ui/camera/CameraWindowActivity.java` — `startLive()/beginLive()/stopLive()` and the Go Live pill.

## Notes / limits
- Requires a data/Wi-Fi connection with enough upload bandwidth (~3–4 Mbps for 720p).
- If sign-in fails with a scope/permission error, re-check the consent screen scope and test-user
  list. If it says "not enabled for live streaming", finish step 1 and wait for it to activate.
- Streaming bitrate/resolution are conservative defaults in `LiveStreamer` — tune if needed.
