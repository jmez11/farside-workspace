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
            "live-nucbox", "live-chart" -> {
                for (camera in target?.let { listOf(it) } ?: listOf("virtual", "passthrough"))
                    verifyLiveNucbox(camera, resolutionOverride, mode == "live-chart")
            }
            "frame-checker" -> verifyFrameChecker()
            "plane-copy" -> verifyPlaneCopy()
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
        val customProfile = defaultProfile.copy(cameraResolution = CameraResolution.NATIVE_MAX)
        val json = customProfile.toJson()
        val restored = ConnectionProfile.fromJson(json)
        check(restored.cameraResolution == CameraResolution.NATIVE_MAX) { "cameraResolution must round-trip via JSON" }
        json.put("cameraResolution", "FHD_1080")
        check(ConnectionProfile.fromJson(json).cameraResolution == CameraResolution.NATIVE_MAX)
        check(CameraResolution.entries.none { it.displayName == "1080p" })
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

    private fun verifyLiveNucbox(targetCamera: String, resolutionOverride: String?, pattern: Boolean) {
        require(targetCamera in listOf("virtual", "passthrough"))
        val manager = ProfileManager(test.targetContext)
        // Recover only profiles owned by this harness after an interrupted run.
        manager.getProfiles().filter {
            it.name == "FFmpeg camera verification" && it.enableCamera && it.redirectStorage
        }.forEach { removeCameraTestProfile(manager, it.id) }
        val original = checkNotNull(manager.getProfiles().firstOrNull {
            it.name.contains("nucbox", ignoreCase = true) || it.host == "172.16.0.32"
        }) { "nucbox profile not found" }
        val resolutions = resolutionOverride?.let { listOf(CameraResolution.valueOf(it)) }
            ?: listOf(CameraResolution.SD, CameraResolution.HD_720, CameraResolution.NATIVE_MAX)
        for (resolution in resolutions) {
            val profile = original.copy(id = java.util.UUID.randomUUID().toString(),
                name = "FFmpeg camera verification", enableCamera = true,
                redirectStorage = true, redirectMicrophone = false, cameraResolution = resolution)
            val output = File(test.targetContext.getExternalFilesDir(null),
                "camera-ffmpeg/${if (pattern) "chart" else "live"}-$targetCamera-${resolution.name}-${System.currentTimeMillis()}")
            check(output.mkdirs())
            val script = test.context.assets.open("camera/ffmpeg-probe.ps1").bufferedReader().use { it.readText() }
            File(output, "ffmpeg-probe.ps1").writeText(script)
            val remoteDir = "\\\\tsclient\\Quest" + output.absolutePath.removePrefix("/sdcard")
                .removePrefix("/storage/emulated/0").replace('/', '\\')
            val cameras = test.targetContext.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val id = if (targetCamera == "virtual") "1" else "50"
            val map = cameras.getCameraCharacteristics(id).get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largest = checkNotNull(map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.filter { resolution == CameraResolution.NATIVE_MAX ||
                    (it.height <= resolution.maxDim && it.width <= resolution.maxDim * 16 / 9 + 64) }
                ?.maxByOrNull { it.width.toLong() * it.height })
            val size = "${largest.width}x${largest.height}"
            val camera = if (targetCamera == "virtual") "Virtual Camera" else "Passthrough Camera"
            val command = "& '$remoteDir\\ffmpeg-probe.ps1' -OutputDir '$remoteDir' -Camera '$camera' -Size '$size'"
            manager.addProfile(profile)
            var activity: SessionActivity? = null
            val bridge = FreeRDPBridge()
            try {
                QuestCameraEncoder.diagnosticDirectory = if (pattern) output.absolutePath else null
                check(bridge.setCameraVerificationPattern(pattern)) { "Camera chart requires a debug native build" }
                activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
                    putExtra("profileId", profile.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }) as SessionActivity
                val session = activity
                val deadline = SystemClock.uptimeMillis() + 20000
                while (session.rdpInstance == 0L && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
                check(session.rdpInstance != 0L) { "Failed to connect to nucbox" }
                Thread.sleep(5000)
                bridge.sendKeyEvent(session.rdpInstance, 0x15B, true)
                Thread.sleep(150)
                bridge.sendKeyEvent(session.rdpInstance, 0x13, true)
                Thread.sleep(100)
                bridge.sendKeyEvent(session.rdpInstance, 0x13, false)
                bridge.sendKeyEvent(session.rdpInstance, 0x15B, false)
                Thread.sleep(500)
                // Start a short command in Run; send the script invocation to the console.
                // Run's edit control can truncate long encoded PowerShell commands.
                val launch = "powershell.exe -NoProfile -ExecutionPolicy Bypass"
                for (char in launch) {
                    bridge.sendUnicodeKeyEvent(session.rdpInstance, char.code, true)
                    bridge.sendUnicodeKeyEvent(session.rdpInstance, char.code, false)
                    Thread.sleep(10)
                }
                bridge.sendKeyEvent(session.rdpInstance, 0x1C, true)
                bridge.sendKeyEvent(session.rdpInstance, 0x1C, false)
                Thread.sleep(2000)
                android.util.Log.i("RdpCameraVerification", "Launching FFmpeg probe; artifacts=$output")
                check(bridge.sendClipboardText(session.rdpInstance, command + "; exit")) { "Remote clipboard unavailable" }
                Thread.sleep(500)
                bridge.sendKeyEvent(session.rdpInstance, 0x1D, true)
                Thread.sleep(100)
                bridge.sendKeyEvent(session.rdpInstance, 0x2F, true)
                bridge.sendKeyEvent(session.rdpInstance, 0x2F, false)
                bridge.sendKeyEvent(session.rdpInstance, 0x1D, false)
                Thread.sleep(500)
                bridge.sendKeyEvent(session.rdpInstance, 0x1C, true)
                bridge.sendKeyEvent(session.rdpInstance, 0x1C, false)
                val result = File(output, "result.json")
                val finishDeadline = SystemClock.uptimeMillis() + 300000
                while (!result.exists() && SystemClock.uptimeMillis() < finishDeadline) {
                    Thread.sleep(1000)
                    test.runOnMainSync {
                        fun texture(view: android.view.View): android.view.TextureView? {
                            if (view is android.view.TextureView) return view
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
                                texture(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        texture(session.window.decorView)?.bitmap?.let { bitmap ->
                            File(output, "desktop.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                    }
                }
                check(result.exists()) { "No FFmpeg result: ${output.absolutePath}" }
                val report = org.json.JSONObject(result.readText().removePrefix("\uFEFF"))
                android.util.Log.i("RdpCameraVerification", "FFmpeg $targetCamera $resolution: $report; artifacts=$output")
                check(report.optBoolean("success")) { "FFmpeg $targetCamera $resolution failed: $report; artifacts=$output" }
                check(report.getString("actualSize") == size)
                if (pattern) {
                    val frames = checkNotNull(output.listFiles()) { "Missing frame directory" }
                        .filter { it.name.matches(Regex("frame-\\d+\\.png")) }.sortedBy { it.name }
                    check(frames.size >= 55)
                    val scores = org.json.JSONArray()
                    var failures = 0
                    for (frame in frames) {
                        val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeFile(frame.absolutePath))
                        val score = chartErrors(bitmap)
                        bitmap.recycle()
                        scores.put(org.json.JSONObject().put("frame", frame.name).put("badSamples", score))
                        if (score > 0) failures++
                    }
                    File(output, "chart-scores.json").writeText(scores.toString(2))
                    check(failures == 0) { "Camera color/geometry corrupted in $failures/${frames.size} frames: $output" }
                }
                check(!bridge.isCameraUnsupported(session.rdpInstance))
            } finally {
                QuestCameraEncoder.diagnosticDirectory = null
                bridge.setCameraVerificationPattern(false)
                activity?.let { session -> test.runOnMainSync { session.finish() } }
                test.waitForIdleSync()
                removeCameraTestProfile(manager, profile.id)
            }
            Thread.sleep(1500)
        }
    }

    private fun removeCameraTestProfile(manager: ProfileManager, id: String) {
        manager.deleteProfile(id)
        val positions = test.targetContext.getSharedPreferences("session_window_positions", android.content.Context.MODE_PRIVATE)
        val edit = positions.edit()
        positions.all.keys.filter { it.startsWith("$id/") }.forEach { edit.remove(it) }
        edit.commit()
    }

    private val chartRgb = intArrayOf(
        AndroidColor.RED, AndroidColor.GREEN, AndroidColor.BLUE, AndroidColor.WHITE,
        AndroidColor.YELLOW, AndroidColor.CYAN, AndroidColor.MAGENTA, AndroidColor.BLACK)

    private fun chartErrors(bitmap: Bitmap): Int {
        var errors = 0
        // Probe multiple positions inside every tile, including near edges: a
        // chroma offset can leave tile centers correct while boundaries are wrong.
        for (row in 0 until 4) for (col in 0 until 8) {
            val expected = chartRgb[(col + row * 3) % 8]
            for (fy in listOf(0.1, 0.5, 0.9)) for (fx in listOf(0.1, 0.5, 0.9)) {
                val pixel = bitmap.getPixel(((col + fx) * bitmap.width / 8).toInt(),
                    ((row + fy) * bitmap.height / 4).toInt())
                val delta = maxOf(kotlin.math.abs(AndroidColor.red(pixel) - AndroidColor.red(expected)),
                    kotlin.math.abs(AndroidColor.green(pixel) - AndroidColor.green(expected)),
                    kotlin.math.abs(AndroidColor.blue(pixel) - AndroidColor.blue(expected)))
                if (delta > 45) errors++
            }
        }
        return errors
    }

    private fun verifyFrameChecker() {
        val good = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
        for (y in 0 until good.height) for (x in 0 until good.width)
            good.setPixel(x, y, chartRgb[(x * 8 / good.width + (y * 4 / good.height) * 3) % 8])
        val displaced = Bitmap.createBitmap(good.width, good.height, Bitmap.Config.ARGB_8888)
        val scrambled = Bitmap.createBitmap(good.width, good.height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until good.height) for (x in 0 until good.width) {
                val color = good.getPixel((x + 40) % good.width, y)
                // Retain luminance while moving chroma: reproduce displaced colors.
                val luma = good.getPixel(x, y)
                fun luminance(c: Int) = 0.299 * AndroidColor.red(c) +
                    0.587 * AndroidColor.green(c) + 0.114 * AndroidColor.blue(c)
                val difference = luminance(luma) - luminance(color)
                displaced.setPixel(x, y, AndroidColor.rgb(
                    (AndroidColor.red(color) + difference).toInt().coerceIn(0, 255),
                    (AndroidColor.green(color) + difference).toInt().coerceIn(0, 255),
                    (AndroidColor.blue(color) + difference).toInt().coerceIn(0, 255)))
                scrambled.setPixel(x, y, good.getPixel((x + (y / 16) * 71) % good.width, y))
            }
            check(chartErrors(good) == 0) { "Checker rejects a clean chart" }
            check(chartErrors(displaced) > 0) { "Checker misses displaced chroma" }
            check(chartErrors(scrambled) > 0) { "Checker misses scrambled rows" }
        } finally {
            good.recycle(); displaced.recycle(); scrambled.recycle()
        }
    }

    private fun verifyPlaneCopy() {
        FreeRDPBridge() // Load the library before calling the standalone plane copier.
        val source = java.nio.ByteBuffer.allocateDirect(24)
        for (i in 0 until 24) source.put(i, (i + 1).toByte())
        fun buffer(size: Int) = java.nio.ByteBuffer.allocateDirect(size).apply {
            for (i in 0 until size) put(i, 85)
        }
        fun checkBytes(actual: java.nio.ByteBuffer, expected: Map<Int, Int>) {
            for (i in 0 until actual.capacity()) check((actual.get(i).toInt() and 255) == (expected[i] ?: 85)) {
                "Plane byte $i differs; padding/visible samples must remain within bounds"
            }
        }
        // Planar U/V with independent row strides, and no trailing last-row padding.
        val y = buffer(25); val u = buffer(6); val v = buffer(7)
        check(QuestCameraPlaneCopy.copyNv12(source, 4, 4, y, 7, 1, u, 4, 1, v, 5, 1))
        checkBytes(y, (0 until 16).associate { (it / 4 * 7 + it % 4) to (it + 1) })
        checkBytes(u, mapOf(0 to 17, 1 to 19, 4 to 21, 5 to 23))
        checkBytes(v, mapOf(0 to 18, 1 to 20, 5 to 22, 6 to 24))
        // Interleaved chroma, in both UV and VU order, with different plane offsets.
        for (swap in listOf(false, true)) {
            val shared = buffer(10)
            fun plane(offset: Int) = shared.duplicate().apply {
                position(offset); limit(if (offset == 0) 9 else 10)
            }.slice()
            val uOffset = if (swap) 1 else 0
            val vOffset = if (swap) 0 else 1
            val yy = buffer(31)
            check(QuestCameraPlaneCopy.copyNv12(source, 4, 4, yy, 8, 2,
                plane(uOffset), 6, 2, plane(vOffset), 6, 2))
            checkBytes(yy, (0 until 16).associate { (it / 4 * 8 + it % 4 * 2) to (it + 1) })
            val expected = mutableMapOf<Int, Int>()
            for (row in 0 until 2) for (col in 0 until 2) {
                expected[row * 6 + col * 2 + uOffset] = 17 + row * 4 + col * 2
                expected[row * 6 + col * 2 + vOffset] = 18 + row * 4 + col * 2
            }
            checkBytes(shared, expected)
        }
        val untouched = buffer(16)
        check(!QuestCameraPlaneCopy.copyNv12(source, 4, 4, untouched, 4, 1, buffer(1), 2, 1, buffer(4), 2, 1))
        checkBytes(untouched, emptyMap())
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
