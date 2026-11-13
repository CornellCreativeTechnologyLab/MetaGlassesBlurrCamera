package com.ctl.metaglasses.glasses

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bare-metal DAT streaming test surface.
 *
 * Deliberately contains nothing but the documented path: register -> session -> addCamera ->
 * collect videoStream -> draw. No blur, no recording, no display capability, no face detection.
 * Its only job is to answer one question: can a frame reach the phone at all?
 *
 * Launch directly:
 *   adb shell am start -n com.ctl.metaglasses/.glasses.RawStreamTestActivity
 * Watch:
 *   adb logcat -s RAWSTREAM
 */
class RawStreamTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RAWSTREAM"
    }

    private lateinit var image: ImageView
    private lateinit var label: TextView
    private var frames = 0L
    private var photoTried = false
    private var session: DeviceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 48, 24, 16)
            text = "starting…"
        }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(label)
        root.addView(image)
        root.gravity = Gravity.CENTER_HORIZONTAL
        setContentView(root)

        start()
    }

    private fun status(text: String) {
        Log.i(TAG, text)
        runOnUiThread { label.text = text }
    }

    private fun start() {
        Wearables.initialize(this)
        status("initialized; registration=${Wearables.registrationState.value}")

        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            Wearables.startRegistration(this)
        }

        // Device.compatibility answers the firmware-vs-SDK question directly:
        // COMPATIBLE / DEVICE_UPDATE_REQUIRED / SDK_UPDATE_REQUIRED.
        lifecycleScope.launch {
            Wearables.devices.collect { ids ->
                // devices is a set of identifiers; devicesMetadata maps each to a Device StateFlow.
                // Collect that flow so linkState transitions are visible over time, not sampled once.
                ids.forEach { id ->
                    // Thermal state matters: StreamError has THERMAL_HOT / PEAK_POWER_LIMIT /
                    // BATTERY_LOW cases, and we have been cycling the camera for hours.
                    lifecycleScope.launch {
                        Wearables.getDeviceState(id).collect { ds ->
                            Log.i(TAG, "DEVICE_STATE thermal=${ds.thermalLevel}")
                        }
                    }
                    lifecycleScope.launch {
                        Wearables.devicesMetadata[id]?.collect { d ->
                            Log.i(
                                TAG,
                                "DEVICE name=${d.name} type=${d.deviceType} " +
                                    "link=${d.linkState} firmware=${d.firmwareInfo} " +
                                    "compatibility=${d.compatibility}",
                            )
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            Wearables.registrationState.collect { reg ->
                status("registration=$reg")
                if (reg == RegistrationState.REGISTERED && session == null) createSession()
            }
        }
    }

    private fun createSession() {
        lifecycleScope.launch {
            // AutoDeviceSelector needs a moment to surface an *eligible* device; creating the
            // session before that fails with "No eligible device available".
            val selector = AutoDeviceSelector()
            selector.activeDeviceFlow().collect { deviceId ->
                status("active device=$deviceId")
                if (deviceId == null || session != null) return@collect
                createSessionFor(selector)
            }
        }
    }

    private fun createSessionFor(selector: AutoDeviceSelector) {
        lifecycleScope.launch {
            Wearables.createSession(selector).onSuccess { created ->
                session = created
                status("session created")
                lifecycleScope.launch {
                    created.state.collect { st ->
                        status("session state=$st")
                        if (st == DeviceSessionState.STARTED) attachCamera(created)
                    }
                }
                created.start()
            }.onFailure { e, _ -> status("createSession FAILED: ${e.description}") }
        }
    }

    private fun attachCamera(s: DeviceSession) {
        lifecycleScope.launch {
            val perm = Wearables.checkPermissionStatus(Permission.CAMERA)
            var granted = false
            perm.onSuccess { granted = it == PermissionStatus.Granted }
            status("camera permission granted=$granted")

            s.addCamera(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
            ).onSuccess { cam ->
                status("camera added")
                lifecycleScope.launch { cam.state.collect { status("CameraState=$it") } }

                val stream = cam.stream
                lifecycleScope.launch(Dispatchers.Default) {
                    Log.i(TAG, "collecting videoStream")
                    stream.videoStream.collect { frame ->
                        val n = ++frames
                        if (n == 1L) {
                            Log.i(TAG, "FIRST FRAME ${frame.width}x${frame.height} " +
                                "buffer=${frame.buffer.remaining()}")
                        }
                        val bmp = YuvToBitmapConverter.convert(
                            frame.buffer, frame.width, frame.height,
                        )
                        if (bmp == null) {
                            if (n % 30L == 1L) Log.e(TAG, "convert failed on frame $n")
                            return@collect
                        }
                        val copy = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        runOnUiThread {
                            label.text = "frames=$n  ${frame.width}x${frame.height}"
                            image.setImageBitmap(copy)
                        }
                    }
                    Log.w(TAG, "videoStream collection ENDED at $frames frames")
                }
                lifecycleScope.launch {
                    stream.state.collect { st ->
                        status("StreamState=$st")
                        // capturePhoto() only succeeds while actively streaming, and it moves image
                        // data over a different path than videoStream. If a photo arrives while the
                        // video Flow stays silent, the fault is the video channel specifically
                        // rather than the whole camera pipeline.
                        if (st == StreamState.STREAMING && !photoTried) {
                            photoTried = true
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(3000)
                                Log.i(TAG, "capturePhoto() attempt…")
                                stream.capturePhoto()
                                    .onSuccess { photo ->
                                        // PhotoData is a sealed type: Bitmap or HEIC.
                                        val desc = when (photo) {
                                            is com.meta.wearable.dat.camera.types.PhotoData.Bitmap ->
                                                "Bitmap ${photo.bitmap.width}x${photo.bitmap.height}"
                                            is com.meta.wearable.dat.camera.types.PhotoData.HEIC ->
                                                "HEIC ${photo.data.remaining()} bytes"
                                            else -> photo.toString()
                                        }
                                        Log.i(TAG, "PHOTO OK: $desc")
                                        status("PHOTO OK: $desc")
                                    }
                                    .onFailure { e, _ ->
                                        Log.e(TAG, "PHOTO FAILED: ${e.description}")
                                        status("PHOTO FAILED: ${e.description}")
                                    }
                            }
                        }
                    }
                }
                lifecycleScope.launch {
                    stream.errorStream.collect { Log.e(TAG, "stream error: ${it.description}") }
                }
                stream.start()
                    .onSuccess { status("stream.start() ok — waiting for frames") }
                    .onFailure { e, _ -> status("stream.start FAILED: ${e.description}") }
            }.onFailure { e, _ -> status("addCamera FAILED: ${e.description}") }
        }
    }
}
