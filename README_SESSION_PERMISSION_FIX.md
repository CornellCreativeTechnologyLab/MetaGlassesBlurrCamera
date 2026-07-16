# Session / Meta Camera Permission Fix

The app previously called `Wearables.createSession(...)` before checking the separate Meta Wearables camera permission. DAT can return **No eligible device** until that Meta-side permission is granted, even when `AutoDeviceSelector.activeDeviceFlow()` reports a device.

This revision:

- checks `Wearables.checkPermissionStatus(Permission.CAMERA)` before session creation;
- opens the Meta AI permission flow when needed;
- resumes session creation after permission is granted;
- creates the session with `SpecificDeviceSelector(deviceId)` to pin the exact device reported as active;
- initializes DAT from the foreground Activity, matching Meta's sample pattern;
- retains the earlier Android runtime Bluetooth permission and reconnection fixes.

## Test

Use Developer Mode credentials in `local.properties`:

```properties
mwdat_application_id=0
mwdat_client_token=0
```

Then reinstall:

```powershell
adb uninstall com.ctl.metaglasses
.\gradlew.bat clean installDebug
```

Open the app and connect. The expected sequence is:

1. `Eligible glasses detected. Checking Meta camera permission...`
2. Meta AI opens and asks for camera access, if not already granted.
3. `Camera permission granted. Creating session...`
4. `Session started...`

If Meta AI never shows the consent screen, remove the app under Meta AI > App connections / Developer mode apps, then register it again.
