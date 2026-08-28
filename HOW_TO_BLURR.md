# How to Use Blurr

Blurr lets you record video from Meta Ray-Ban glasses with automatic face blur, then selectively unblur people after recording.

## Getting Started

### 1. Pair Your Glasses

- Make sure your **Meta Ray-Ban glasses are powered on** and nearby (within Bluetooth range, ~30 feet)
- On the Blurr app, tap the **shutter button** (large circle at the bottom)
- The app will search for available glasses and display `PAIR` in the top-left status pill
- Once paired, the status changes to `Connected`

**Note:** The glasses appear on the Blurr display (the glasses' tiny screen) once paired. If you don't see a response, check that the glasses are powered on and Bluetooth is enabled.

---

## Privacy by Default

**Everyone is blurred automatically.** When you record:

- Every detected face is blurred in real-time on your phone
- No unblurred frame ever leaves your device
- The blur is **non-destructive** — stored as metadata, not baked into the video

---

## Recording

### Photo Mode
1. Tap **PHOTO** at the bottom to switch to photo mode
2. Tap the **shutter button** to capture one frame
3. The photo appears in your gallery with all faces blurred

### Video Mode
1. **VIDEO** is the default mode
2. Press and **hold** the shutter button to start recording
3. Release to stop
4. The video is saved with audio and all faces blurred

**Tip:** While recording, you'll see a **red timer badge** (top-right) showing elapsed time.

---

## Gallery & Face Editing

Open your recordings and photos in the **Gallery** (bottom-left icon):

### Editing a Photo
1. Select a photo from the gallery
2. Tap **Select Faces** — the app detects all faces in the image
3. **Tap faces to toggle the blur on/off** — unblurred faces appear with a green box
4. Tap **Done** when finished
5. Tap **Save** to export to your phone's camera roll

### Editing a Video
1. Select a video from the gallery
2. Tap **Select Faces**
3. The app **scans the video and detects individual people** (tracking them across frames)
4. A checklist appears: "Person 1", "Person 2", etc. — all checked by default
5. **Uncheck people you don't want to blur**
6. Tap **Blur** — the app re-encodes the video with selective blur (this takes time)
7. The blurred video is saved to your gallery

**Or** tap **Blur em all** to blur every face in the video without picking individuals.

---

## Face Blur Types

### Photos
- Uses facial contours (nose, eyes, jaw outline) for pixel-perfect blur shapes
- Very accurate per-person blur

### Videos
- Uses face tracking (stable person IDs across frames)
- Oval-shaped blur for speed, applied per-person
- Allows selective blur (only blur people you choose)

---

## Audio Recording



- When you first record, grant the **RECORD_AUDIO** permission when prompted
- Audio is synced to video automatically
- Test by tapping on a table or speaking — you should hear it in playback

---

## Exporting & Sharing

### Export to Camera Roll
1. Open a photo/video in the gallery
2. Tap the **Export button** (top-right, down arrow icon)
3. The file is copied to your phone's camera roll (DCIM/Blurr folder)

### Share
1. Open a photo/video in the gallery
2. Tap the **Share button** (top-right, share icon)
3. Choose an app (Messages, email, social media, etc.)

---

## Testing Without Glasses

### Mock Camera Mode
- Open the menu (top-right, three-line icon)
- Tap **Test with mock camera**
- Use your phone's front camera instead of glasses
- Fully functional: capture photos, record video, blur, export — all without glasses

### Glasses Preview Lab (for testing purposes to be used for Meta Glasses with screen)
- Open the menu
- Tap **Glasses Preview Lab**
- Stream live blurred frames to the glasses over HTTPS
- Test display refresh rates and frame latency
- **Advanced feature** — optional

---

## Troubleshooting

### Glasses Won't Pair
- Confirm glasses are **powered on** (button press on frame until LED blinks)
- Check Bluetooth is **enabled** on your phone
- Move closer (within 30 feet)
- Restart the app

### No Audio in Video
- When you first record, grant **RECORD_AUDIO** permission in the system prompt
- The app uses your **phone's microphone**, not the glasses

### Video Stutters or Plays Out of Sync
- This is usually a codec issue
- Try recording shorter videos
- If persistent, restart the app

### Blur Looks Wrong on Video
- Video blur uses oval shapes (faster) instead of exact face contours
- If it misses faces, try **Blur em all** to catch everyone
- For pixel-perfect blur, edit the photo version instead

---

## Tips & Best Practices

1. **Pair once** — glasses stay paired until you manually disconnect
2. **Blur everyone, then unblur selectively** — safer than the opposite
3. **Test mock camera first** — get comfortable before using glasses
4. **Use video mode for events** — photos are quick, video captures context
5. **Export immediately** — don't rely on the app gallery as permanent storage
6. **Check audio** — record 10 seconds and play back to verify sound works

---

## Feedback & Support

- **Not working?** Check the top status pill — it shows connection state
- **Questions?** Tap the menu → **How To** (this guide)
- **Found a bug?** Note the exact steps to reproduce and share

Enjoy recording with privacy! 🎥
