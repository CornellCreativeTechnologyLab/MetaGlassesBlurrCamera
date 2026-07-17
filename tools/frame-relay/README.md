# Blurr HTTPS frame relay

The DAT Display image API fetches HTTPS image URLs. This relay accepts the newest blurred JPEG
from the phone with `PUT` and serves it publicly with `GET` from the same path.

## Run locally

```powershell
cd tools\frame-relay
$env:FRAME_RELAY_TOKEN="choose-a-long-random-token"
npm start
```

Expose it with any HTTPS tunnel or deploy it as a small Node web service. For example, after an
HTTPS tunnel gives you `https://example-tunnel.example`, choose an unguessable path such as:

```text
https://example-tunnel.example/frame/9f1c2d83a6d04c6eb77b0a5c
```

Paste that complete URL into **Glasses Preview Lab** and paste the same value used for
`FRAME_RELAY_TOKEN` into the token field. The phone uses `PUT`; the glasses use public `GET`.

Start at **96 px / 1 fps**. This is a test bridge, not a production streaming architecture. The
relay stores only the latest frame in memory and loses it when restarted.
