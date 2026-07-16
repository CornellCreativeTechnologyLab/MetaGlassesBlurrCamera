package com.ctl.metaglasses.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.ctl.metaglasses.vision.BlurMode
import com.ctl.metaglasses.vision.FaceBlurProcessor
import com.ctl.metaglasses.vision.TFLiteAgeClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Glasses integration using the Meta Wearables DAT SDK (0.8).
 * Sends text to connected Ray-Ban Meta Display glasses.
 *
 * Flow (per the DAT display-access guide):
 *   initialize -> register -> createSession -> session.start()
 *   -> wait for DeviceSessionState.STARTED -> addDisplay()
 *   -> wait for DisplayState.STARTED -> sendContent { ... }
 *
 * Also mirrors output to the on-phone MockGlassesDisplay for testing.
 */
class GlassesManager(private val appContext: Context) {

    companion object {
        private const val TAG = "GlassesManager"
    }

    interface Listener {
        fun onStatusChanged(status: String)
        fun onFrameReceived(frameBytes: ByteArray, width: Int, height: Int, timestampMillis: Long)
        /** A decoded camera frame from the glasses, ready to show on the phone. */
        fun onCameraFrame(bitmap: Bitmap)
        fun onTranslationOutput(text: String)
        fun onError(message: String, throwable: Throwable?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var mockDisplay: MockGlassesDisplay? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var deviceSession: DeviceSession? = null
    private var display: Display? = null
    private var stream: Stream? = null
    private var cameraPermissionLauncher: ActivityResultLauncher<Permission>? = null
    // Selects the first available glasses. Reused for both device-availability waiting
    // and session creation so they refer to the same device. Lazy so it is not constructed
    // until after Wearables.initialize() runs — AutoDeviceSelector() touches Wearables internals
    // and throws if built too early (e.g. at GlassesManager construction time).
    private val deviceSelector by lazy { AutoDeviceSelector() }
    private var deviceWaitJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private var videoJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var connected = false
    private var streaming = false
    private var sessionRequested = false
    private var sessionStarted = false
    private var displayReady = false
    private var monitoringStarted = false
    private var cameraPermissionGranted = false
    private var cameraPermissionRequestInFlight = false
    // Device selected by DAT while we complete the Meta-side camera consent flow.
    private var pendingSessionDeviceId: DeviceIdentifier? = null
    // If the user asks to stream before the session is STARTED, remember it
    // and attach the camera stream as soon as the session is ready.
    private var streamRequested = false
    // If a letter arrives before the display is ready, remember the latest one
    // and send it as soon as DisplayState.STARTED fires.
    private var pendingText: String? = null

    // Real-time face blurring applied to camera frames before they reach the UI.
    private val faceBlurProcessor by lazy { FaceBlurProcessor(TFLiteAgeClassifier(appContext)) }
    @Volatile private var blurMode = BlurMode.OFF

    /** OFF, ALL_FACES, or MINORS_ONLY. Applied live to the camera preview. */
    fun setBlurMode(mode: BlurMode) {
        blurMode = mode
    }

    fun getBlurMode(): BlurMode = blurMode

    /** True once a fine-tuned age model (assets/age_classifier.tflite) is loaded. */
    fun isAgeModelLoaded(): Boolean = faceBlurProcessor.ageModelLoaded

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun setMockDisplay(display: MockGlassesDisplay) {
        this.mockDisplay = display
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner
    }

    fun isConnected(): Boolean = connected
    fun isStreaming(): Boolean = streaming

    fun connect(activity: Activity) {
        try {
            // Match Meta's samples: initialize from the foreground Activity, not a stored app context.
            Wearables.initialize(activity)
            connected = true

            val owner = lifecycleOwner
            if (owner == null) {
                postError("Lifecycle owner not set before connect().", null)
                return
            }

            postStatus("Wearables initialized. Checking registration...")

            startRegistrationMonitor()

            // Kick off the registration UI if we're not registered yet.
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                Wearables.startRegistration(activity)
            }
        } catch (e: Exception) {
            postError("Failed to initialize Wearables: ${e.message}", e)
        }
    }

    /**
     * Test mode without real hardware: pairs a simulated, always-eligible Ray-Ban Meta via
     * MockDeviceKit and streams the phone's own camera as the fake glasses feed. Lets the whole
     * flow (session -> stream) run when real glasses aren't eligible (e.g. Developer Mode off).
     */
    fun enableMockGlasses() {
        val owner = lifecycleOwner
        if (owner == null) {
            postError("Lifecycle owner not set before enableMockGlasses().", null)
            return
        }
        try {
            val kit = MockDeviceKit.getInstance(appContext)
            if (!kit.isEnabled) kit.enable() // auto-initializes Wearables and registers
            connected = true
            postStatus("Mock mode: pairing simulated glasses...")

            startRegistrationMonitor()

            owner.lifecycleScope.launch {
                kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                    onSuccess = { device ->
                        device.powerOn()
                        device.unfold()
                        device.don()
                        // Feed the phone's back camera as the mock glasses POV so the
                        // camera-stream preview shows a live image.
                        try {
                            device.services.camera.setCameraFeed(CameraFacing.BACK)
                        } catch (e: Exception) {
                            Log.w(TAG, "Mock camera feed not set: ${e.message}")
                        }
                        postStatus("Mock glasses paired and powered on.")
                    },
                    onFailure = { error, _ ->
                        postError("Failed to pair mock glasses: ${error.description}", null)
                    },
                )
            }
        } catch (e: Exception) {
            postError("Mock setup failed: ${e.message}", e)
        }
    }

    private fun startRegistrationMonitor() {
        if (monitoringStarted) return
        monitoringStarted = true
        val owner = lifecycleOwner ?: return
        // Observe registration; once REGISTERED, wait for a connected device before
        // creating the session (creating too early fails with "No eligible device").
        jobs += owner.lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                if (state == RegistrationState.REGISTERED) {
                    postStatus("Glasses registered. Waiting for glasses to connect...")
                    waitForDeviceThenCreateSession()
                } else {
                    postStatus("Registration: $state")
                }
            }
        }
        // Surface registration errors — otherwise UNAVAILABLE etc. give no reason.
        jobs += owner.lifecycleScope.launch {
            Wearables.registrationErrorStream.collect { error ->
                postError("Registration error: ${error.getLocalizedDescription(appContext)}", null)
            }
        }
    }

    private fun waitForDeviceThenCreateSession() {
        if (deviceWaitJob != null || sessionRequested) return
        val owner = lifecycleOwner ?: return

        deviceWaitJob = owner.lifecycleScope.launch {
            // AutoDeviceSelector filters out disconnected/incompatible devices. A non-empty
            // Wearables.devices set is not enough to prove createSession() can succeed.
            deviceSelector.activeDeviceFlow().collect { deviceId ->
                Log.d(TAG, "AutoDeviceSelector active device: $deviceId; all=${Wearables.devices.value}")
                if (deviceId != null && !sessionRequested) {
                    pendingSessionDeviceId = deviceId
                    postStatus("Eligible glasses detected. Checking Meta camera permission...")
                    ensureCameraPermissionBeforeSession(deviceId)
                } else if (deviceId == null && !sessionRequested) {
                    val discovered = Wearables.devices.value.size
                    postStatus(
                        if (discovered == 0) {
                            "Registered, but no glasses discovered. Check Nearby devices permission."
                        } else {
                            "Glasses discovered ($discovered), but none are eligible. Check Developer Mode and DAT update."
                        }
                    )
                }
            }
        }
    }

    private fun createSession(deviceId: DeviceIdentifier) {
        if (sessionRequested) return
        sessionRequested = true

        val owner = lifecycleOwner ?: return

        // Pin the exact device that activeDeviceFlow reported. This avoids AutoDeviceSelector
        // changing underneath createSession during a link-state update.
        Wearables.createSession(SpecificDeviceSelector(deviceId)).fold(
            onSuccess = { session ->
                deviceSession = session
                pendingSessionDeviceId = null

                jobs += owner.lifecycleScope.launch {
                    session.errors.collect { error ->
                        postError("Session error: ${error.description}", null)
                    }
                }

                jobs += owner.lifecycleScope.launch {
                    session.state.collect { state ->
                        when (state) {
                            DeviceSessionState.STARTED -> {
                                sessionStarted = true
                                postStatus("Session started. Attaching display...")
                                if (display == null) attachDisplay(session)
                                if (streamRequested && stream == null) ensureCameraThenAttach(session)
                            }
                            DeviceSessionState.STOPPED -> {
                                sessionStarted = false
                                displayReady = false
                                sessionRequested = false
                                stream?.stop()
                                stream = null
                                deviceSession = null
                                pendingSessionDeviceId = null
                                postStatus("Session stopped. Waiting for glasses to reconnect...")

                                // Re-subscribe so StateFlow immediately emits the current active
                                // device. Without this, folding/unfolding can leave the app stuck.
                                deviceWaitJob?.cancel()
                                deviceWaitJob = null
                                waitForDeviceThenCreateSession()
                            }
                            else -> {}
                        }
                    }
                }

                // Session starts in IDLE; this drives it toward STARTED.
                session.start()
            },
            onFailure = { error, _ ->
                sessionRequested = false
                postError("Failed to create session: ${error.description}", null)
            },
        )
    }

    private fun attachDisplay(session: DeviceSession) {
        val owner = lifecycleOwner ?: return

        session.addDisplay().fold(
            onSuccess = { newDisplay ->
                display = newDisplay

                jobs += owner.lifecycleScope.launch {
                    newDisplay.state.collect { state ->
                        if (state == DisplayState.STARTED) {
                            displayReady = true
                            postStatus("Display ready.")
                            pendingText?.let { queued ->
                                pendingText = null
                                renderText(queued)
                            }
                        } else {
                            displayReady = false
                        }
                    }
                }
            },
            onFailure = { error, _ ->
                postError("Failed to attach display: ${error.description}", null)
            },
        )
    }

    /**
     * Registers the DAT camera-permission launcher. Must be called before the caller (Fragment/
     * Activity) is STARTED — e.g. from Fragment.onCreate(). DatResult is a Kotlin value class, so
     * all of its handling stays on the Kotlin side and is never exposed to Java callers.
     */
    fun registerCameraPermission(caller: ActivityResultCaller) {
        cameraPermissionLauncher =
            caller.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
                cameraPermissionRequestInFlight = false
                result.onSuccess { status ->
                    if (status == PermissionStatus.Granted) {
                        cameraPermissionGranted = true

                        // Registration/device selection can complete before Meta camera consent.
                        // Once consent returns, create the session that was waiting on it.
                        val pendingDevice = pendingSessionDeviceId
                        if (deviceSession == null && !sessionRequested && pendingDevice != null) {
                            postStatus("Camera permission granted. Creating session...")
                            createSession(pendingDevice)
                            return@onSuccess
                        }

                        val session = deviceSession
                        if (streamRequested && session != null && sessionStarted && stream == null) {
                            attachStream(session)
                        }
                    } else {
                        postError("Camera permission denied on the glasses.", null)
                    }
                }.onFailure { error, _ ->
                    postError("Camera permission request failed: ${error.description}", null)
                }
            }
    }

    /**
     * DAT may report an active device while createSession still rejects it when the separate
     * Meta-wearables CAMERA permission has not been granted. Check/request that consent first.
     */
    private fun ensureCameraPermissionBeforeSession(deviceId: DeviceIdentifier) {
        if (sessionRequested || deviceSession != null) return
        pendingSessionDeviceId = deviceId
        val owner = lifecycleOwner ?: return

        owner.lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA)
                .onSuccess { status ->
                    cameraPermissionGranted = status == PermissionStatus.Granted
                    if (cameraPermissionGranted) {
                        createSession(deviceId)
                    } else {
                        postStatus("Allow camera access in Meta AI to continue...")
                        requestCameraPermission()
                    }
                }
                .onFailure { error, _ ->
                    postError("Meta camera permission check failed: ${error.description}", null)
                }
        }
    }

    /** Requests DAT camera access, then attaches the stream if the session is already up. */
    fun requestCameraPermission() {
        if (cameraPermissionRequestInFlight) return
        val launcher = cameraPermissionLauncher
        if (launcher == null) {
            postError("Camera permission launcher not registered.", null)
            return
        }
        cameraPermissionRequestInFlight = true
        launcher.launch(Permission.CAMERA)
    }

    /** Re-checks Meta camera consent before attaching the stream to an existing session. */
    private fun ensureCameraThenAttach(session: DeviceSession) {
        val owner = lifecycleOwner ?: return
        owner.lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA)
                .onSuccess { status ->
                    cameraPermissionGranted = status == PermissionStatus.Granted
                    if (cameraPermissionGranted) {
                        attachStream(session)
                    } else {
                        requestCameraPermission()
                    }
                }
                .onFailure { error, _ ->
                    postError("Camera permission check failed: ${error.description}", null)
                }
        }
    }

    fun startVideoStream() {
        if (!connected) {
            postError("Connect to glasses before starting stream.", null)
            return
        }
        streamRequested = true
        streaming = true

        val session = deviceSession
        if (session != null && sessionStarted && stream == null) {
            ensureCameraThenAttach(session)
        }
        // Otherwise deferred until the session reaches STARTED (see the session.state collector).
    }

    fun stopVideoStream() {
        streamRequested = false
        streaming = false

        videoJob?.cancel(); videoJob = null
        streamStateJob?.cancel(); streamStateJob = null
        streamErrorJob?.cancel(); streamErrorJob = null

        stream?.stop()
        stream = null

        postStatus("Camera stream stopped.")
    }

    private fun attachStream(session: DeviceSession) {
        val owner = lifecycleOwner ?: return

        session.addStream(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
        ).onSuccess { newStream ->
            stream = newStream
            postStatus("Camera stream starting...")

            // Convert frames off the main thread; deliver bitmaps to the UI.
            videoJob = owner.lifecycleScope.launch(Dispatchers.Default) {
                newStream.videoStream.collect { frame -> handleVideoFrame(frame) }
            }

            streamStateJob = owner.lifecycleScope.launch {
                newStream.state.collect { state ->
                    postStatus("Camera stream: $state")
                }
            }

            streamErrorJob = owner.lifecycleScope.launch {
                newStream.errorStream.collect { error ->
                    if (error == StreamError.STREAM_ERROR) {
                        Log.w(TAG, "Non-critical stream error: ${error.description}")
                    } else {
                        postError("Camera stream error: ${error.description}", null)
                        stopVideoStream()
                    }
                }
            }

            newStream.start().onFailure { error, _ ->
                streamRequested = false
                streaming = false
                postError("Failed to start camera stream: ${error.description}", null)
            }
        }.onFailure { error, _ ->
            streamRequested = false
            streaming = false
            postError("Failed to add camera stream: ${error.description}", null)
        }
    }

    private fun handleVideoFrame(frame: VideoFrame) {
        val bitmap = YuvToBitmapConverter.convert(frame.buffer, frame.width, frame.height) ?: return
        // Mutable copy so we can blur in place and the UI can hold it while the converter
        // reuses its cached bitmap for the next frame.
        val work = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return
        if (blurMode != BlurMode.OFF) {
            try {
                faceBlurProcessor.process(work, blurMode)
            } catch (e: Exception) {
                Log.e(TAG, "Face blur failed: ${e.message}")
            }
        }
        listener?.let { l -> mainHandler.post { l.onCameraFrame(work) } }
    }

    fun sendTextToGlasses(text: String) {
        // Always mirror to the phone-side UI (mock display + translation label).
        mockDisplay?.let { mock -> mainHandler.post { mock.displayLetter(text) } }
        listener?.let { l -> mainHandler.post { l.onTranslationOutput(text) } }

        // Send to the real glasses display when it's ready; otherwise queue it.
        if (display != null && displayReady) {
            renderText(text)
        } else {
            pendingText = text
        }

        Log.d(TAG, "Letter handled: $text")
    }

    private fun renderText(letter: String) {
        val currentDisplay = display ?: return
        val owner = lifecycleOwner ?: return

        jobs += owner.lifecycleScope.launch {
            currentDisplay.sendContent {
                flexBox(
                    padding = 24,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    text(letter, style = TextStyle.HEADING)
                }
            }.onFailure { error, _ ->
                Log.e(TAG, "sendContent failed: ${error.description}")
            }
        }
    }

    fun disconnect() {
        streaming = false
        connected = false
        displayReady = false
        sessionRequested = false
        sessionStarted = false
        streamRequested = false
        pendingText = null

        deviceWaitJob?.cancel(); deviceWaitJob = null
        videoJob?.cancel(); videoJob = null
        streamStateJob?.cancel(); streamStateJob = null
        streamErrorJob?.cancel(); streamErrorJob = null
        stream?.stop()
        stream = null

        deviceSession?.let { session ->
            session.removeDisplay().onFailure { error, _ ->
                Log.e(TAG, "removeDisplay failed: ${error.description}")
            }
            session.stop()
        }

        jobs.forEach { it.cancel() }
        jobs.clear()

        display = null
        deviceSession = null

        mockDisplay?.let { mock -> mainHandler.post { mock.clear() } }

        postStatus("Disconnected.")
    }

    private fun postStatus(status: String) {
        listener?.let { l ->
            mainHandler.post { l.onStatusChanged(status) }
        }
    }

    private fun postError(message: String, throwable: Throwable?) {
        listener?.let { l ->
            mainHandler.post { l.onError(message, throwable) }
        }
    }
}
