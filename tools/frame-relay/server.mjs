import http from "node:http";
import { URL } from "node:url";

const port = Number(process.env.PORT || 8787);
const requiredToken = process.env.FRAME_RELAY_TOKEN || "";
const maxFrameBytes = Number(process.env.MAX_FRAME_BYTES || 2_000_000);
const frames = new Map();

function sendText(res, status, text) {
  const body = Buffer.from(text, "utf8");
  res.writeHead(status, {
    "content-type": "text/plain; charset=utf-8",
    "content-length": body.length,
    "cache-control": "no-store",
  });
  res.end(body);
}

function frameKey(pathname) {
  const match = pathname.match(/^\/frame\/([A-Za-z0-9._~-]{8,160})$/);
  return match ? match[1] : null;
}

const server = http.createServer((req, res) => {
  const requestUrl = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

  if (requestUrl.pathname === "/health") {
    sendText(res, 200, `ok frames=${frames.size}`);
    return;
  }

  const key = frameKey(requestUrl.pathname);
  if (!key) {
    sendText(res, 404, "Use GET or PUT /frame/<unguessable-key>");
    return;
  }

  if (req.method === "GET" || req.method === "HEAD") {
    const frame = frames.get(key);
    if (!frame) {
      sendText(res, 404, "No frame uploaded yet");
      return;
    }
    res.writeHead(200, {
      "content-type": "image/jpeg",
      "content-length": frame.bytes.length,
      "cache-control": "no-store, no-cache, max-age=0, must-revalidate",
      pragma: "no-cache",
      expires: "0",
      "x-frame-updated-at": String(frame.updatedAt),
    });
    if (req.method === "HEAD") res.end();
    else res.end(frame.bytes);
    return;
  }

  if (req.method !== "PUT") {
    res.setHeader("allow", "GET, HEAD, PUT");
    sendText(res, 405, "Method not allowed");
    return;
  }

  if (requiredToken && req.headers["x-preview-token"] !== requiredToken) {
    sendText(res, 401, "Invalid X-Preview-Token");
    return;
  }

  const contentType = String(req.headers["content-type"] || "").toLowerCase();
  if (!contentType.startsWith("image/jpeg")) {
    sendText(res, 415, "Content-Type must be image/jpeg");
    return;
  }

  const chunks = [];
  let received = 0;
  req.on("data", chunk => {
    received += chunk.length;
    if (received > maxFrameBytes) {
      req.destroy(new Error("Frame too large"));
      return;
    }
    chunks.push(chunk);
  });
  req.on("end", () => {
    if (received === 0) {
      sendText(res, 400, "Empty frame");
      return;
    }
    frames.set(key, { bytes: Buffer.concat(chunks), updatedAt: Date.now() });
    res.writeHead(204, { "cache-control": "no-store" });
    res.end();
  });
  req.on("error", error => {
    if (!res.headersSent) sendText(res, 413, error.message || "Upload failed");
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Blurr frame relay listening on :${port}`);
  console.log(`PUT/GET /frame/<unguessable-key>`);
});
