package com.farside.workspace

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.farside.workspace.data.CameraResolution
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class RdpCameraVerification(private val test: RdpResizeInstrumentation) {
    private val automation: UiAutomation = test.uiAutomation

    private fun find(text: String): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun await(text: String, timeoutMs: Long = 10000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(text) != null) return
            Thread.sleep(100)
        }
        error("Timed out waiting for '$text'")
    }

    private fun awaitDismissal(text: String, timeoutMs: Long = 5000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(text) == null) return
            Thread.sleep(100)
        }
        error("Timed out waiting for dismissal of '$text'")
    }

    private fun click(text: String) {
        val node = checkNotNull(find(text)) { "Cannot click '$text'" }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        checkNotNull(target) { "No clickable ancestor for '$text'" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun run(mode: String, target: String? = null, resolutionOverride: String? = null) {
        val service = automation.serviceInfo
        service.flags = service.flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = service

        when (mode) {
            "blocked-ui" -> verifyBlockedUi()
            "recording-ui" -> verifyRecordingUi()
            "muted-ui" -> verifyMutedUi()
            "capabilities" -> verifyCapabilities()
            "live-laptop" -> verifyLiveLaptop()
            "live-nucbox" -> verifyLiveNucbox(target ?: "virtual", resolutionOverride)
            "hardware-probe" -> verifyHardwareProbe()
            else -> error("Unknown cameraMode: $mode")
        }
    }

    private fun verifyBlockedUi() {
        val activity = test.startActivitySync(
            Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val toggles = AtomicInteger(0)
        try {
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = Color.Black) {
                            RdpCameraControl(
                                muted = false,
                                recording = false,
                                hostUnsupported = true,
                                onToggleMute = { toggles.incrementAndGet() },
                                onInteraction = {},
                            )
                        }
                    }
                }
            }
            await("Camera redirection unsupported")
            val icon = checkNotNull(find("Camera redirection unsupported"))
            val iconBounds = Rect().also { icon.getBoundsInScreen(it) }
            val capture = Bitmap.createBitmap(
                activity.window.decorView.width,
                activity.window.decorView.height,
                Bitmap.Config.ARGB_8888
            )
            val copied = CountDownLatch(1)
            var copyResult = -1
            test.runOnMainSync {
                PixelCopy.request(
                    activity.window,
                    capture,
                    { result -> copyResult = result; copied.countDown() },
                    Handler(Looper.getMainLooper())
                )
            }
            check(copied.await(5, TimeUnit.SECONDS) && copyResult == PixelCopy.SUCCESS)
            File(test.targetContext.cacheDir, "rdp-camera-blocked.png").outputStream().use {
                capture.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            var redPixels = 0
            var whitePixels = 0
            for (y in iconBounds.top.coerceAtLeast(0) until iconBounds.bottom.coerceAtMost(capture.height)) {
                for (x in iconBounds.left.coerceAtLeast(0) until iconBounds.right.coerceAtMost(capture.width)) {
                    val c = capture.getPixel(x, y)
                    if (AndroidColor.red(c) > 180 && AndroidColor.green(c) < 80) redPixels++
                    if (AndroidColor.red(c) > 180 && AndroidColor.green(c) > 180) whitePixels++
                }
            }
            check(redPixels > 10 && whitePixels > 2) {
                "Missing red prohibition overlay or white camera icon: red=$redPixels, white=$whitePixels"
            }
            capture.recycle()

            click("Camera redirection unsupported")
            await("This host does not support camera redirection.")
            check(toggles.get() == 0) { "Blocked camera button changed mute state" }
            click("OK")
            awaitDismissal("This host does not support camera redirection.")
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyRecordingUi() {
        val activity = test.startActivitySync(
            Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = Color.Black) {
                            RdpCameraControl(
                                muted = false,
                                recording = true,
                                hostUnsupported = false,
                                onToggleMute = {},
                                onInteraction = {},
                            )
                        }
                    }
                }
            }
            await("Mute camera (recording)")
            val icon = checkNotNull(find("Mute camera (recording)"))
            val iconBounds = Rect().also { icon.getBoundsInScreen(it) }
            val capture = Bitmap.createBitmap(
                activity.window.decorView.width,
                activity.window.decorView.height,
                Bitmap.Config.ARGB_8888
            )
            val copied = CountDownLatch(1)
            var copyResult = -1
            test.runOnMainSync {
                PixelCopy.request(
                    activity.window,
                    capture,
                    { result -> copyResult = result; copied.countDown() },
                    Handler(Looper.getMainLooper())
                )
            }
            check(copied.await(5, TimeUnit.SECONDS) && copyResult == PixelCopy.SUCCESS)
            File(test.targetContext.cacheDir, "rdp-camera-recording.png").outputStream().use {
                capture.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            var redPixels = 0
            for (y in iconBounds.top.coerceAtLeast(0) until iconBounds.bottom.coerceAtMost(capture.height)) {
                for (x in iconBounds.left.coerceAtLeast(0) until iconBounds.right.coerceAtMost(capture.width)) {
                    val c = capture.getPixel(x, y)
                    if (AndroidColor.red(c) > 180 && AndroidColor.green(c) < 80) redPixels++
                }
            }
            check(redPixels > 10) { "Camera recording icon should be red: red=$redPixels" }
            capture.recycle()
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyMutedUi() {
        val activity = test.startActivitySync(
            Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val toggles = AtomicInteger(0)
        try {
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = Color.Black) {
                            RdpCameraControl(
                                muted = true,
                                recording = false,
                                hostUnsupported = false,
                                onToggleMute = { toggles.incrementAndGet() },
                                onInteraction = {},
                            )
                        }
                    }
                }
            }
            await("Unmute camera")
            click("Unmute camera")
            check(toggles.get() == 1) { "Clicking unmute should toggle mute" }
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyCapabilities() {
        val caps = RdpHostCapabilities(test.targetContext)
        check(caps.cameraUnsupported("LAPTOP-BBJV33M6")) { "LAPTOP-BBJV33M6 should report camera unsupported" }
        check(caps.cameraUnsupported("172.16.0.54")) { "172.16.0.54 should report camera unsupported" }
        check(!caps.cameraUnsupported("unknown-host-123")) { "Unknown host should report false (not unsupported)" }
        check(REQUIRED_CAMERA_PERMISSIONS.contains(android.Manifest.permission.CAMERA)) { "Missing CAMERA permission" }
        check(REQUIRED_CAMERA_PERMISSIONS.contains("horizonos.permission.HEADSET_CAMERA")) { "Missing HEADSET_CAMERA permission" }
        check(REQUIRED_CAMERA_PERMISSIONS.contains("horizonos.permission.AVATAR_CAMERA")) { "Missing AVATAR_CAMERA permission" }
        val defaultProfile = ConnectionProfile("temp", "temp", ConnectionType.RDP, "host", 3389, "u", "p")
        check(!defaultProfile.enableCamera) { "New profiles must default to camera disabled" }
        check(defaultProfile.cameraResolution == CameraResolution.HD_720) { "Default cameraResolution should be HD_720" }
        val customProfile = defaultProfile.copy(cameraResolution = CameraResolution.FHD_1080)
        val json = customProfile.toJson()
        val restored = ConnectionProfile.fromJson(json)
        check(restored.cameraResolution == CameraResolution.FHD_1080) { "cameraResolution must round-trip via JSON" }
    }

    private fun verifyLiveLaptop() {
        val profiles = ProfileManager(test.targetContext).getProfiles()
        val laptop = checkNotNull(profiles.firstOrNull { it.host.contains("172.16.0.54") || it.name.contains("LAPTOP", ignoreCase = true) }) {
            "LAPTOP profile not found in saved profiles"
        }
        val bridge = FreeRDPBridge()
        val verifier = RdpCertificateVerifier(test.targetContext, laptop.host, laptop.port, { request -> request?.respond(true) })
        val instance = bridge.connect(
            laptop.host, laptop.port, laptop.user, laptop.pass, null,
            enableCamera = true, enableMicrophone = false, enableStorage = false,
            surface = android.view.Surface(android.graphics.SurfaceTexture(0)),
            certificateHost = laptop.host, certificateVerifier = verifier
        )
        check(instance != 0L) { "Failed to connect to laptop" }
        try {
            val deadline = SystemClock.uptimeMillis() + 6000
            var unsupported = false
            while (SystemClock.uptimeMillis() < deadline) {
                if (bridge.isCameraUnsupported(instance)) {
                    unsupported = true
                    break
                }
                Thread.sleep(200)
            }
            check(unsupported) { "LAPTOP should report camera unsupported after connect" }
        } finally {
            bridge.disconnect(instance)
        }
    }

    private fun verifyLiveNucbox(targetCamera: String = "virtual", resolutionOverride: String? = null) {
        val profileManager = ProfileManager(test.targetContext)
        val profiles = profileManager.getProfiles()
        var nucbox = checkNotNull(profiles.firstOrNull { it.host.contains("172.16.0.32") || it.name.contains("nucbox", ignoreCase = true) }) {
            "nucbox profile not found in saved profiles"
        }
        if (resolutionOverride != null) {
            val resEnum = try { CameraResolution.valueOf(resolutionOverride) } catch (e: Exception) { CameraResolution.HD_720 }
            nucbox = nucbox.copy(cameraResolution = resEnum)
            profileManager.updateProfile(nucbox)
        }
        val activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
            putExtra("profileId", nucbox.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as SessionActivity
        try {
            val connectDeadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < connectDeadline && activity.rdpInstance == 0L) {
                Thread.sleep(200)
            }
            check(activity.rdpInstance != 0L) { "Failed to connect to nucbox in SessionActivity" }

            // Allow initial desktop render and device enumeration
            var desktopImage: Bitmap? = null
            fun hasContent(candidate: Bitmap?): Boolean {
                if (candidate == null) return false
                val colors = mutableSetOf<Int>()
                for (y in 0 until candidate.height step 17)
                    for (x in 0 until candidate.width step 17) colors.add(candidate.getPixel(x, y))
                return colors.size > 16
            }

            fun grabTexture(): Bitmap? {
                var bmp: Bitmap? = null
                test.runOnMainSync {
                    fun findTexture(v: android.view.View): android.view.TextureView? {
                        if (v is android.view.TextureView) return v
                        if (v is android.view.ViewGroup) {
                            for (i in 0 until v.childCount) {
                                findTexture(v.getChildAt(i))?.let { return it }
                            }
                        }
                        return null
                    }
                    bmp = findTexture(activity.window.decorView)?.bitmap
                }
                return bmp
            }

            for (attempt in 0 until 16) {
                desktopImage?.recycle()
                desktopImage = grabTexture()
                if (hasContent(desktopImage)) {
                    android.util.Log.i("RdpCameraVerification", "Desktop content painted after attempt $attempt")
                    break
                }
                Thread.sleep(500)
            }
            desktopImage?.let { bmp ->
                File(test.targetContext.cacheDir, "nucbox-desktop.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                android.util.Log.i("RdpCameraVerification", "Saved nucbox-desktop.png (${bmp.width}x${bmp.height})")
            }

            val bridge = FreeRDPBridge()
            // Wait 2 seconds for initial frame
            Thread.sleep(2000)
            val readyImage = grabTexture()
            readyImage?.let { bmp ->
                File(test.targetContext.cacheDir, "nucbox-detected.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                android.util.Log.i("RdpCameraVerification", "Saved nucbox-detected.png (${bmp.width}x${bmp.height})")
            }

            fun findButtonCenter(bmp: Bitmap?): Pair<Int, Int>? {
                if (bmp == null) return null
                for (y in 200 until minOf(bmp.height - 50, 650)) {
                    val top = bmp.getPixel(632, y)
                    val tr = (top shr 16) and 0xFF
                    val tg = (top shr 8) and 0xFF
                    val tb = top and 0xFF
                    if (tr < 160 && tg < 160 && tb < 160) {
                        val inP = bmp.getPixel(632, y + 5)
                        val ir = (inP shr 16) and 0xFF
                        val ig = (inP shr 8) and 0xFF
                        val ib = inP and 0xFF
                        // Neutral light gray (not green notice box)
                        if (ir in 228..250 && Math.abs(ir - ig) <= 5 && Math.abs(ig - ib) <= 5) {
                            for (by in (y + 30) until minOf(bmp.height, y + 55)) {
                                val bot = bmp.getPixel(632, by)
                                val br = (bot shr 16) and 0xFF
                                val bg = (bot shr 8) and 0xFF
                                val bb = bot and 0xFF
                                if (br < 160 && bg < 160 && bb < 160) {
                                    return Pair(632, (y + by) / 2)
                                }
                            }
                        }
                    }
                }
                return Pair(632, 448)
            }

            // Ensure browser is open and focused on https://webcamtests.com via Win+R run dialog
            android.util.Log.i("RdpCameraVerification", "Launching Edge to https://webcamtests.com via Win+R...")
            // Win key (0x15B) + R (0x13)
            bridge.sendKeyEvent(activity.rdpInstance, 0x15B, true)
            Thread.sleep(50)
            bridge.sendKeyEvent(activity.rdpInstance, 0x13, true)
            Thread.sleep(50)
            bridge.sendKeyEvent(activity.rdpInstance, 0x13, false)
            Thread.sleep(50)
            bridge.sendKeyEvent(activity.rdpInstance, 0x15B, false)
            Thread.sleep(1200)

            val runCmd = "msedge https://webcamtests.com"
            for (ch in runCmd) {
                bridge.sendUnicodeKeyEvent(activity.rdpInstance, ch.code, true)
                Thread.sleep(20)
                bridge.sendUnicodeKeyEvent(activity.rdpInstance, ch.code, false)
                Thread.sleep(20)
            }
            Thread.sleep(100)
            bridge.sendKeyEvent(activity.rdpInstance, 0x1C, true) // Enter
            Thread.sleep(50)
            bridge.sendKeyEvent(activity.rdpInstance, 0x1C, false)
            Thread.sleep(4000) // Wait for Edge to load page

            // Focus page content area and refresh page with F5 (scancode 0x3F) as requested
            android.util.Log.i("RdpCameraVerification", "Refreshing page with F5...")
            bridge.sendKeyEvent(activity.rdpInstance, 0x3F, true)
            Thread.sleep(100)
            bridge.sendKeyEvent(activity.rdpInstance, 0x3F, false)
            Thread.sleep(4500) // Wait for reload and device enumeration

            // Ensure scrolled to top
            bridge.sendKeyEvent(activity.rdpInstance, 0x147, true)
            Thread.sleep(50)
            bridge.sendKeyEvent(activity.rdpInstance, 0x147, false)
            Thread.sleep(500)

            val refreshedImage = grabTexture()
            val buttonPos = findButtonCenter(refreshedImage) ?: Pair(632, 448)
            refreshedImage?.recycle()
            val ddY = buttonPos.second - 44
            val ddX = 650
            android.util.Log.i("RdpCameraVerification", "Button detected at $buttonPos, dropdown at ($ddX, $ddY)")

            // Pick redirected camera from the drop-down:
            val cameraTarget = targetCamera
            android.util.Log.i("RdpCameraVerification", "Target redirected camera: $cameraTarget")
            // 1. Click dropdown at (ddX, ddY) to focus / open it
            android.util.Log.i("RdpCameraVerification", "Clicking dropdown at ($ddX, $ddY)...")
            bridge.sendMouseEvent(activity.rdpInstance, ddX, ddY, FreeRDPBridge.PTR_FLAGS_MOVE)
            Thread.sleep(100)
            bridge.sendMouseEvent(activity.rdpInstance, ddX, ddY, FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN)
            Thread.sleep(100)
            bridge.sendMouseEvent(activity.rdpInstance, ddX, ddY, FreeRDPBridge.PTR_FLAGS_BUTTON1)
            Thread.sleep(300)

            // 2. Select camera: Lenovo is item 1.
            // "Passthrough Camera (redirected)" starts with 'P', or is 1 Down Arrow.
            // "Virtual Camera (redirected)" starts with 'V', or is 2 Down Arrows.
            if (cameraTarget == "passthrough") {
                android.util.Log.i("RdpCameraVerification", "Selecting Passthrough Camera via 'p'...")
                bridge.sendUnicodeKeyEvent(activity.rdpInstance, 'p'.code, true)
                Thread.sleep(50)
                bridge.sendUnicodeKeyEvent(activity.rdpInstance, 'p'.code, false)
                Thread.sleep(150)
            } else {
                android.util.Log.i("RdpCameraVerification", "Selecting Virtual Camera via 'v'...")
                bridge.sendUnicodeKeyEvent(activity.rdpInstance, 'v'.code, true)
                Thread.sleep(50)
                bridge.sendUnicodeKeyEvent(activity.rdpInstance, 'v'.code, false)
                Thread.sleep(150)
            }

            // 3. Send Enter (0x1C) to commit the selection and close the menu
            android.util.Log.i("RdpCameraVerification", "Sending Enter to commit camera selection...")
            bridge.sendKeyEvent(activity.rdpInstance, 0x1C, true)
            Thread.sleep(100)
            bridge.sendKeyEvent(activity.rdpInstance, 0x1C, false)
            Thread.sleep(300)

            // NOTE: Do NOT send Escape key here! Escape cancels dropdown selection in Windows.

            val selectImage = grabTexture()
            selectImage?.let { bmp ->
                File(test.targetContext.cacheDir, "nucbox-selected.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                android.util.Log.i("RdpCameraVerification", "Saved nucbox-selected.png (${bmp.width}x${bmp.height})")
            }

            // Click the button to test my cam
            val finalButtonPos = findButtonCenter(selectImage) ?: buttonPos
            val (bx, by) = finalButtonPos
            android.util.Log.i("RdpCameraVerification", "Clicking 'Test my cam' button at ($bx, $by)")
            bridge.sendMouseEvent(activity.rdpInstance, bx, by, FreeRDPBridge.PTR_FLAGS_MOVE)
            Thread.sleep(100)
            bridge.sendMouseEvent(activity.rdpInstance, bx, by, FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN)
            Thread.sleep(150)
            bridge.sendMouseEvent(activity.rdpInstance, bx, by, FreeRDPBridge.PTR_FLAGS_BUTTON1)

            // Monitor camera recording for up to 15 seconds, dynamically re-clicking if needed
            val deadline = SystemClock.uptimeMillis() + 15000
            var recordingSeen = false
            var lastClickTime = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() < deadline) {
                if (bridge.isCameraRecording(activity.rdpInstance)) {
                    recordingSeen = true
                    android.util.Log.i("RdpCameraVerification", "Camera is actively recording in nucbox session!")
                    break
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastClickTime >= 3500) {
                    lastClickTime = now
                    val curImage = grabTexture()
                    val curCenter = findButtonCenter(curImage) ?: finalButtonPos
                    curImage?.recycle()
                    val (cx, cy) = curCenter
                    android.util.Log.i("RdpCameraVerification", "Re-clicking 'Test my cam' button at ($cx, $cy)")
                    bridge.sendMouseEvent(activity.rdpInstance, cx, cy, FreeRDPBridge.PTR_FLAGS_MOVE)
                    Thread.sleep(100)
                    bridge.sendMouseEvent(activity.rdpInstance, cx, cy, FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN)
                    Thread.sleep(150)
                    bridge.sendMouseEvent(activity.rdpInstance, cx, cy, FreeRDPBridge.PTR_FLAGS_BUTTON1)
                }
                Thread.sleep(250)
            }

            // Grab post-test screenshot
            Thread.sleep(3000)
            val streamImage = grabTexture()
            streamImage?.let { bmp ->
                File(test.targetContext.cacheDir, "nucbox-streaming.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                android.util.Log.i("RdpCameraVerification", "Saved nucbox-streaming.png (${bmp.width}x${bmp.height})")
            }

            val unsupported = bridge.isCameraUnsupported(activity.rdpInstance)
            check(!unsupported) { "nucbox supports camera redirection; should not be marked unsupported" }
            android.util.Log.i("RdpCameraVerification", "verifyLiveNucbox result: recordingSeen=$recordingSeen, unsupported=$unsupported")
            check(recordingSeen) { "Expected camera stream to start recording on nucbox" }
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyHardwareProbe() {
        val activity = test.startActivitySync(
            Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        Thread.sleep(1500)
        try {
            val manager = activity.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val ids = manager.cameraIdList
            android.util.Log.i("RdpCameraProbe", "Camera IDs available: ${ids.joinToString()}")
            for (id in ids) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.map { "${it.width}x${it.height}" } ?: emptyList()
                android.util.Log.i("RdpCameraProbe", "Camera $id facing=$facing yuvSizes=$yuvSizes")

                val openLatch = CountDownLatch(1)
                var openedDevice: android.hardware.camera2.CameraDevice? = null
                var openError = -1
                test.runOnMainSync {
                    try {
                        manager.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
                            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                                openedDevice = camera
                                openLatch.countDown()
                            }
                            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                                camera.close()
                                openLatch.countDown()
                            }
                            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                                openError = error
                                camera.close()
                                openLatch.countDown()
                            }
                        }, Handler(Looper.getMainLooper()))
                    } catch (t: Throwable) {
                        android.util.Log.e("RdpCameraProbe", "openCamera error for $id", t)
                        openLatch.countDown()
                    }
                }

            val opened = openLatch.await(3, TimeUnit.SECONDS)
            android.util.Log.i("RdpCameraProbe", "Camera $id open result: opened=$opened, error=$openError, dev=$openedDevice")
            if (openedDevice != null) {
                val dev = openedDevice!!
                try {
                    val size = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.firstOrNull() ?: android.util.Size(640, 480)
                    val reader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 4)
                    val frameLatch = CountDownLatch(1)
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage()
                        img?.close()
                        frameLatch.countDown()
                    }, Handler(Looper.getMainLooper()))

                    val sessionLatch = CountDownLatch(1)
                    var createdSession: android.hardware.camera2.CameraCaptureSession? = null
                    val targets = listOf(reader.surface)
                    val req = dev.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD)
                    req.addTarget(reader.surface)

                    dev.createCaptureSession(targets, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            createdSession = session
                            session.setRepeatingRequest(req.build(), null, null)
                            sessionLatch.countDown()
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            sessionLatch.countDown()
                        }
                    }, Handler(Looper.getMainLooper()))

                    val sessionConfigured = sessionLatch.await(3, TimeUnit.SECONDS)
                    val gotFrame = frameLatch.await(3, TimeUnit.SECONDS)
                    android.util.Log.i("RdpCameraProbe", "Camera $id sessionConfigured=$sessionConfigured session=$createdSession gotFrame=$gotFrame")

                    createdSession?.close()
                    reader.close()
                } catch (t: Throwable) {
                    android.util.Log.e("RdpCameraProbe", "Camera $id exception", t)
                } finally {
                    dev.close()
                }
            }
        }
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }
}
