# Meta Glasses Sign Translator Scaffold

This project has been updated from a plain Android template into a glasses-ready scaffold.

## What was added

- Runtime permissions for camera, audio, and Bluetooth.
- `GlassesManager.java` as the single boundary for Meta Wearables DAT SDK calls.
- `SignLanguagePipeline.java` as the single boundary for MediaPipe/model/rule-based translation.
- Home screen buttons for:
  - Connect glasses
  - Start/stop camera stream
  - Run demo translation
- Gradle comments showing where to add:
  - Meta Wearables Device Access Toolkit dependency
  - MediaPipe Tasks Vision
  - ONNX Runtime Mobile

## What still needs Meta developer access

The app does **not** yet include the actual Meta DAT SDK dependency because the exact package/version and repository require Meta Wearables Device Access Toolkit access.

After you get access, update:

1. `settings.gradle.kts`
   - Add the Maven repository Meta gives you.
   - Add GitHub Packages credentials if required.

2. `app/build.gradle.kts`
   - Replace the placeholder Meta dependency with the exact SDK artifact.

3. `GlassesManager.java`
   - Replace TODOs in:
     - `connect()`
     - `startVideoStream()`
     - `stopVideoStream()`
     - `sendTextToGlasses()`
     - `disconnect()`

## Intended final pipeline

```text
Meta glasses camera
-> Android app receives frames
-> MediaPipe Android extracts hand/body/face landmarks
-> local model/rules translate signs
-> translation appears in the app
-> translation is sent back to glasses display/audio
```

## Android-friendly model choices

Start with this order:

1. Rule/dictionary-based translation logic in Java/Kotlin.
2. MediaPipe Android landmarks.
3. ONNX Runtime Mobile or TFLite model for sign-to-text classification.
4. Later: ExecuTorch if you keep PyTorch-style models.

## Important note

This scaffold should build as a normal Android project, but the real glasses stream will only work after the Meta DAT SDK calls are added.
