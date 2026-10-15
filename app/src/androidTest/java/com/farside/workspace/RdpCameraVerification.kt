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

internal class RdpCameraVerification(private val test: RdpResizeInstrumentation, private val profileName: String? = null,
    private val ffmpegPath: String? = null) {
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
            "host-status" -> verifyHostStatus()
            "live-laptop" -> verifyLiveLaptop()
            "live-nucbox", "live-chart" -> {
                for (camera in target?.let { listOf(it) } ?: listOf("virtual", "passthrough"))
                    verifyLiveNucbox(camera, resolutionOverride, mode == "live-chart")
            }
            "live-simultaneous" -> verifySimultaneous(target ?: "virtual")
            "live-simultaneous-creator-close" -> verifySimultaneous(target ?: "display", true)
            "live-session-isolation" -> verifyLiveNucbox(target ?: "virtual",
                resolutionOverride ?: "HD_720", pattern = true, isolation = true)
            "display-deny" -> verifyLiveNucbox("display", "HD_720", false, denyDisplay = true)
            "display-timeout" -> verifyLiveNucbox("display", "HD_720", false, denyDisplay = true, timeoutDisplay = true)
            "live-browser" -> verifyLiveNucbox(target ?: "virtual", resolutionOverride ?: "HD_720", false, browser = true)
            "live-browser-window" -> verifyLiveNucbox(target ?: "virtual", resolutionOverride ?: "HD_720", false, browser = true, browserWindow = true)
            "display-conversion" -> {
                FreeRDPBridge()
                check(QuestDisplayVerification.verifyConversion()) { "RGBA channel order, pitch or BT.601 range is incorrect" }
            }
            "frame-checker" -> verifyFrameChecker()
            "plane-copy" -> verifyPlaneCopy()
            "session-isolation" -> {
                FreeRDPBridge()
                check(QuestCameraVerification.verifySessionIsolation()) {
                    "Native session mute, frame masking or HAL teardown crossed session boundaries"
                }
            }
            "encoder" -> verifyEncoders()
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
        check(CameraResolution.entries.none { test.targetContext.getString(it.labelResId) == "1080p" })
    }

    private fun verifyHostStatus() {
        val profile = checkNotNull(ProfileManager(test.targetContext).getProfiles().firstOrNull {
            it.name.equals(profileName ?: "NUCBOX", ignoreCase = true) && it.type == ConnectionType.RDP
        }) { "Camera host profile missing" }
        val activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java)
            .putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
        val report = StringBuilder("profile=${profile.name} cameraEnabled=${profile.enableCamera} " +
            "hostReportUnsupported=${RdpHostCapabilities(test.targetContext).cameraUnsupported(profile.host)}\n")
        try {
            val deadline = SystemClock.uptimeMillis() + 20000
            while (activity.rdpInstance == 0L && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
            check(activity.rdpInstance != 0L) { "Camera host did not connect" }
            val start = SystemClock.uptimeMillis()
            var channelSeen = false
            repeat(48) {
                val instance = activity.rdpInstance
                val channelConnected = activity.rdpBridge?.isCameraChannelConnected(instance) == true
                channelSeen = channelSeen || channelConnected
                report.append("t=${SystemClock.uptimeMillis() - start}ms " +
                    "channelConnected=$channelConnected " +
                    "recording=${activity.rdpBridge?.isCameraRecording(instance)}\n")
                Thread.sleep(250)
            }
            check(channelSeen) { "NUCBOX did not open an RDPECAM channel" }
        } finally {
            test.targetContext.cacheDir.resolve("rdp-camera-host-status.txt").writeText(report.toString())
            test.runOnMainSync { activity.finish() }
        }
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
            adminMode = false, enableCamera = true, enableMicrophone = false, enableStorage = false,
            surface = android.view.Surface(android.graphics.SurfaceTexture(0)),
            certificateHost = laptop.host, certificateVerifier = verifier
        )
        check(instance != 0L) { "Failed to connect to laptop" }
        try {
            val deadline = SystemClock.uptimeMillis() + 6000
            var channelConnected = false
            while (SystemClock.uptimeMillis() < deadline) {
                if (bridge.isCameraChannelConnected(instance)) {
                    channelConnected = true
                    break
                }
                Thread.sleep(200)
            }
            check(!channelConnected) { "LAPTOP unexpectedly opened an RDPECAM channel" }
            check(RdpHostCapabilities(test.targetContext).cameraUnsupported(laptop.host)) {
                "Verified host diagnostic should identify the laptop's camera limitation"
            }
        } finally {
            bridge.disconnect(instance)
        }
    }

    private fun verifyLiveNucbox(targetCamera: String, resolutionOverride: String?, pattern: Boolean,
        isolation: Boolean = false, denyDisplay: Boolean = false, timeoutDisplay: Boolean = false, browser: Boolean = false, browserWindow: Boolean = false) {
        require(targetCamera in listOf("virtual", "passthrough", "display"))
        val manager = ProfileManager(test.targetContext)
        // Recover only profiles owned by this harness after an interrupted run.
        manager.getProfiles().filter {
            it.name == "FFmpeg camera verification" && it.enableCamera && it.redirectStorage
        }.forEach { removeCameraTestProfile(manager, it.id) }
        val original = checkNotNull(manager.getProfiles().firstOrNull {
            if (profileName != null) it.name.equals(profileName, ignoreCase = true)
            else it.name.contains("nucbox", ignoreCase = true) || it.host == "172.16.0.32"
        }) { "nucbox profile not found" }
        val resolutions = resolutionOverride?.let { listOf(CameraResolution.valueOf(it)) }
            ?: if (targetCamera == "display") listOf(CameraResolution.HD_720)
            else listOf(CameraResolution.SD, CameraResolution.HD_720, CameraResolution.NATIVE_MAX)
        for (resolution in resolutions) {
            val profile = original.copy(id = java.util.UUID.randomUUID().toString(),
                name = "FFmpeg camera verification", enableCamera = true,
                redirectStorage = true, redirectMicrophone = false, cameraResolution = resolution)
            val output = File(test.targetContext.getExternalFilesDir(null),
                "camera-ffmpeg/${if (isolation) "isolation" else if (pattern) "chart" else "live"}-$targetCamera-${resolution.name}-${System.currentTimeMillis()}")
            check(output.mkdirs())
            val scriptName = if (browser) "browser-probe.ps1" else "ffmpeg-probe.ps1"
            val script = test.context.assets.open("camera/$scriptName").bufferedReader().use { it.readText() }
            File(output, scriptName).writeText(script)
            val remoteDir = "\\\\tsclient\\Quest" + output.absolutePath.removePrefix("/sdcard")
                .removePrefix("/storage/emulated/0").replace('/', '\\')
            val cameras = test.targetContext.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val id = when (targetCamera) { "virtual" -> "1"; "display" -> "display-0"; else -> "50" }
            val map = if (id == "display-0") null else cameras.getCameraCharacteristics(id).get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largest = if (id == "display-0") android.util.Size(1280, 720) else checkNotNull(map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.filter { resolution == CameraResolution.NATIVE_MAX ||
                    (it.height <= resolution.maxDim && it.width <= resolution.maxDim * 16 / 9 + 64) }
                ?.maxByOrNull { it.width.toLong() * it.height })
            val size = "${largest.width}x${largest.height}"
            val camera = when (targetCamera) { "virtual" -> "Virtual Camera"; "display" -> "Headset Display"; else -> "Passthrough Camera" }
            val tool = File(test.targetContext.getExternalFilesDir(null), "camera-tools/ffmpeg.exe")
            val remoteTool = "\\\\tsclient\\Quest" + tool.absolutePath.removePrefix("/sdcard")
                .removePrefix("/storage/emulated/0").replace('/', '\\')
            val executable = ffmpegPath ?: remoteTool.takeIf { tool.exists() }
            val command = "& '$remoteDir\\$scriptName' -OutputDir '$remoteDir' -Camera '$camera' -Size '$size'" +
                (if (!browser && executable != null) " -FfmpegPath '${executable.replace("'", "''")}'" else "") +
                (if (denyDisplay) " -ExpectDenied" else "") + (if (browserWindow) " -Windowed" else "")
            manager.addProfile(profile)
            var activity: SessionActivity? = null
            var secondary = 0L
            var secondarySurface: android.view.Surface? = null
            var secondaryTexture: android.graphics.SurfaceTexture? = null
            val bridge = FreeRDPBridge()
            try {
                QuestCameraEncoder.diagnosticDirectory = if (pattern || browser) output.absolutePath else null
                check(bridge.setCameraVerificationPattern(pattern)) { "Camera chart requires a debug native build" }
                activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
                    putExtra("profileId", profile.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }) as SessionActivity
                val session = activity
                val deadline = SystemClock.uptimeMillis() + 20000
                while (session.rdpInstance == 0L && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
                check(session.rdpInstance != 0L) { "Failed to connect to nucbox" }
                if (isolation) {
                    // The saved laptop supports an independent desktop connection, but no camera.
                    // Muting/closing it must not affect nucbox's actual camera capture.
                    val laptop = checkNotNull(manager.getProfiles().firstOrNull {
                        it.host.contains("172.16.0.54") || it.name.contains("LAPTOP", ignoreCase = true)
                    }) { "LAPTOP profile not found" }
                    secondaryTexture = android.graphics.SurfaceTexture(0)
                    secondarySurface = android.view.Surface(secondaryTexture)
                    secondary = bridge.connect(laptop.host, laptop.port, laptop.user, laptop.pass, null,
                        adminMode = false, enableCamera = true, enableMicrophone = false, enableStorage = false,
                        surface = secondarySurface, certificateHost = laptop.host,
                        certificateVerifier = RdpCertificateVerifier(test.targetContext, laptop.host, laptop.port,
                            { request -> request?.respond(true) }))
                    check(secondary != 0L) { "Secondary laptop connection failed" }
                }
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
                val captureStarted = File(output, "capture-started.json")
                var captureStart = 0L
                var isolationStage = 0
                val timeline = org.json.JSONArray()
                val finishDeadline = SystemClock.uptimeMillis() + 300000
                while (!result.exists() && SystemClock.uptimeMillis() < finishDeadline) {
                    Thread.sleep(1000)
                    if (targetCamera == "display") respondToDisplayConsent(output, denyDisplay, !timeoutDisplay)
                    if (isolation && captureStarted.exists()) {
                        if (captureStart == 0L) captureStart = SystemClock.uptimeMillis()
                        val elapsed = SystemClock.uptimeMillis() - captureStart
                        val threshold = listOf(15000L, 25000L, 35000L, 43000L).getOrNull(isolationStage)
                        if (threshold != null && elapsed >= threshold) {
                            check(bridge.isCameraRecording(session.rdpInstance)) { "Nucbox capture stopped at stage $isolationStage" }
                            when (isolationStage) {
                                0 -> {
                                    check(!bridge.isCameraRecording(secondary)) { "Idle laptop reports nucbox capture" }
                                    bridge.setCameraMuted(secondary, true)
                                    check(bridge.isCameraMuted(secondary) && !bridge.isCameraMuted(session.rdpInstance))
                                }
                                1 -> { bridge.disconnect(secondary); secondary = 0L }
                                2 -> { bridge.setCameraMuted(session.rdpInstance, true); check(bridge.isCameraMuted(session.rdpInstance)) }
                                3 -> { bridge.setCameraMuted(session.rdpInstance, false); check(!bridge.isCameraMuted(session.rdpInstance)) }
                            }
                            timeline.put(org.json.JSONObject().put("stage", isolationStage).put("elapsedMs", elapsed))
                            isolationStage++
                            File(output, "isolation-timeline.json").writeText(timeline.toString(2))
                        }
                    }
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
                if (denyDisplay) {
                    check(report.optBoolean("denied") && report.getJSONArray("attempts").length() == 2)
                    check(session.displayCapture.consentRequests == 1) { "Display consent re-prompted" }
                    check(session.displayCapture.streamRequests.get() >= 2) { "Host did not send two actual stream starts" }
                    check(File(output, "consent.png").exists()) { "No actual consent dialog observed" }
                    check(!bridge.isCameraRecording(session.rdpInstance))
                    if (timeoutDisplay) check((0 until report.getJSONArray("attempts").length()).any {
                        report.getJSONArray("attempts").getJSONObject(it).getLong("elapsedMs") in 30000..40000
                    }) { "Consent did not time out at 30 seconds" }
                    File(output, "consent-result.json").writeText(org.json.JSONObject()
                        .put("consentRequests", session.displayCapture.consentRequests)
                        .put("streamRequests", session.displayCapture.streamRequests.get())
                        .put("timeout", timeoutDisplay).toString(2))
                    if (timeoutDisplay) respondToDisplayConsent(output, true)
                } else if (browser) {
                    check(report.getInt("frames") >= 55 && report.getString("actualSize") == size)
                    check(report.getString("backend") == "MediaFoundation") { "Browser fell back to DirectShow" }
                    if (targetCamera == "display") {
                        check(session.displayCapture.consentRequests == 1 && session.displayCapture.displayCreations == 1)
                        check(session.displayCapture.streamRequests.get() >= 2)
                        File(output, "consent-result.json").writeText(org.json.JSONObject()
                            .put("consentRequests", session.displayCapture.consentRequests)
                            .put("displayCreations", session.displayCapture.displayCreations)
                            .put("streamRequests", session.displayCapture.streamRequests.get()).toString(2))
                    }
                } else {
                    check(report.getString("actualSize") == size && report.getInt("frames") >= 55)
                    if (targetCamera == "display") {
                        check(session.displayCapture.consentRequests == 1 && session.displayCapture.displayCreations == 1) {
                            "Display restart consumed another consent or virtual display"
                        }
                        check(session.displayCapture.streamRequests.get() >= 2) { "Display did not restart after the sample probe" }
                        File(output, "consent-result.json").writeText(org.json.JSONObject()
                            .put("consentRequests", session.displayCapture.consentRequests)
                            .put("displayCreations", session.displayCapture.displayCreations)
                            .put("streamRequests", session.displayCapture.streamRequests.get()).toString(2))
                    }
                }
                if (isolation) check(isolationStage == 4) { "Capture ended before all live isolation checks" }
                if (pattern) {
                    val frames = checkNotNull(output.listFiles()) { "Missing frame directory" }
                        .filter { it.name.matches(Regex("frame-\\d+\\.png")) }.sortedBy { it.name }
                    check(frames.size >= 55)
                    val scores = org.json.JSONArray()
                    var failures = 0
                    var blackFrames = 0
                    for ((index, frame) in frames.withIndex()) {
                        val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeFile(frame.absolutePath))
                        val score = chartErrors(bitmap)
                        val black = isolation && isBlackFrame(bitmap)
                        bitmap.recycle()
                        scores.put(org.json.JSONObject().put("frame", frame.name).put("badSamples", score).put("black", black))
                        if (black) blackFrames++
                        // Secondary mute/teardown happens before 30 seconds; own mute is 35-43.
                        if ((score > 0 && !black) || (black && index !in 31..45)) failures++
                    }
                    File(output, "chart-scores.json").writeText(scores.toString(2))
                    check(failures == 0) { "Camera color/geometry corrupted in $failures/${frames.size} frames: $output" }
                    if (isolation) check(blackFrames in 6..10) { "Own mute produced $blackFrames blackout frames, expected about eight" }
                }
                check(bridge.isCameraChannelConnected(session.rdpInstance))
                val stopDeadline = SystemClock.uptimeMillis() + 10000
                while (bridge.isCameraRecording(session.rdpInstance) && SystemClock.uptimeMillis() < stopDeadline)
                    Thread.sleep(200)
                check(!bridge.isCameraRecording(session.rdpInstance)) { "Camera still recording after the remote probe closed capture" }
                File(output, "stream-lifecycle.json").writeText(org.json.JSONObject()
                    .put("camera", targetCamera).put("recordingAfterRemoteClose", false)
                    .put("displayStreamRequests", session.displayCapture.streamRequests.get()).toString(2))
            } finally {
                if (secondary != 0L) bridge.disconnect(secondary)
                secondarySurface?.release()
                secondaryTexture?.release()
                activity?.rdpInstance?.takeIf { it != 0L }?.let { bridge.setCameraMuted(it, false) }
                QuestCameraEncoder.diagnosticDirectory = null
                bridge.setCameraVerificationPattern(false)
                activity?.let { session -> test.runOnMainSync { session.finish() } }
                test.waitForIdleSync()
                removeCameraTestProfile(manager, profile.id)
            }
            Thread.sleep(1500)
        }
    }

    private fun verifySimultaneous(target: String, closeCreator: Boolean = false) {
        require(target in listOf("virtual", "passthrough", "display"))
        val manager = ProfileManager(test.targetContext)
        val originals = listOf("NUCBOX", "NUCBOX 2").map { name ->
            checkNotNull(manager.getProfiles().firstOrNull { it.name.equals(name, true) }) { "$name not saved" }
        }
        val root = File(test.targetContext.getExternalFilesDir(null), "camera-simultaneous/$target-${System.currentTimeMillis()}")
        check(root.mkdirs())
        val activities = mutableListOf<SessionActivity>()
        val profiles = mutableListOf<com.farside.workspace.data.ConnectionProfile>()
        val bridge = FreeRDPBridge()
        val closing = if(closeCreator) 0 else 1
        val surviving = 1-closing
        fun launch(index: Int): File {
            val profile = originals[index].copy(id = java.util.UUID.randomUUID().toString(),
                name = "FFmpeg camera verification", enableCamera = true, redirectStorage = true,
                redirectMicrophone = false, cameraResolution = CameraResolution.HD_720)
            profiles.add(profile); manager.addProfile(profile)
            val output = File(root, "session-$index").apply { mkdirs() }
            File(output, "browser-probe.ps1").writeText(test.context.assets.open("camera/browser-probe.ps1").bufferedReader().use { it.readText() })
            val session = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", profile.id); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }) as SessionActivity
            activities.add(session)
            val deadline = SystemClock.uptimeMillis() + 20000
            while (session.rdpInstance == 0L && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
            check(session.rdpInstance != 0L)
            Thread.sleep(3000)
            bridge.sendKeyEvent(session.rdpInstance, 0x15B, true)
            Thread.sleep(150)
            bridge.sendKeyEvent(session.rdpInstance, 0x13, true)
            Thread.sleep(100)
            bridge.sendKeyEvent(session.rdpInstance, 0x13, false)
            bridge.sendKeyEvent(session.rdpInstance, 0x15B, false)
            Thread.sleep(800)
            for (char in "powershell.exe -NoProfile -ExecutionPolicy Bypass") {
                bridge.sendUnicodeKeyEvent(session.rdpInstance, char.code, true)
                bridge.sendUnicodeKeyEvent(session.rdpInstance, char.code, false)
                Thread.sleep(10)
            }
            bridge.sendKeyEvent(session.rdpInstance, 0x1C, true); bridge.sendKeyEvent(session.rdpInstance, 0x1C, false)
            Thread.sleep(2000)
            val remote = "\\\\tsclient\\Quest" + output.absolutePath.removePrefix("/sdcard").removePrefix("/storage/emulated/0").replace('/', '\\')
            val camera = when(target) { "virtual" -> "Virtual Camera"; "display" -> "Headset Display"; else -> "Passthrough Camera" }
            val duration = if(closeCreator) { if(index == 0) 35 else 60 } else { if(index == 0) 60 else 20 }
            val command = "& '$remote\\browser-probe.ps1' -OutputDir '$remote' -Camera '$camera' -Size '1280x720' -CaptureSeconds $duration -Attempts 1; exit"
            check(bridge.sendClipboardText(session.rdpInstance, command)); Thread.sleep(500)
            bridge.sendKeyEvent(session.rdpInstance, 0x1D, true); bridge.sendKeyEvent(session.rdpInstance, 0x2F, true)
            bridge.sendKeyEvent(session.rdpInstance, 0x2F, false); bridge.sendKeyEvent(session.rdpInstance, 0x1D, false)
            Thread.sleep(750)
            bridge.sendKeyEvent(session.rdpInstance, 0x1C, true); bridge.sendKeyEvent(session.rdpInstance, 0x1C, false)
            val ready = File(output, "browser-started.json")
            val readyDeadline = SystemClock.uptimeMillis()+45000
            while (!ready.exists() && SystemClock.uptimeMillis()<readyDeadline) {
                if(target == "display") respondToDisplayConsent(output, false)
                check(!File(output, "result.json").exists()) { "Capture failed: ${File(output, "result.json").readText()}" }
                Thread.sleep(200)
            }
            test.runOnMainSync {
                fun texture(view: android.view.View): android.view.TextureView? {
                    if(view is android.view.TextureView) return view
                    if(view is android.view.ViewGroup) for(i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
                    return null
                }
                texture(session.window.decorView)?.bitmap?.let { bitmap ->
                    File(output,"desktop.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
            check(ready.exists()) { "No browser stream started: $output" }
            return output
        }
        try {
            check(bridge.setCameraVerificationPattern(true))
            val first = launch(0)
            val second = launch(1)
            check(activities.all { bridge.isCameraRecording(it.rdpInstance) }) { "One session stopped when the second camera opened" }
            Thread.sleep(3000)
            bridge.setCameraMuted(activities[closing].rdpInstance, true)
            check(!bridge.isCameraMuted(activities[surviving].rdpInstance))
            Thread.sleep(4000)
            bridge.setCameraMuted(activities[closing].rdpInstance, false)
            val secondResult = File(if(closeCreator) first else second, "result.json")
            val deadline = SystemClock.uptimeMillis()+90000
            while(!secondResult.exists() && SystemClock.uptimeMillis()<deadline) Thread.sleep(300)
            check(secondResult.exists())
            test.runOnMainSync { activities[closing].finish() }; test.waitForIdleSync()
            check(bridge.isCameraRecording(activities[surviving].rdpInstance)) { "Closing the other session stopped the camera" }
            bridge.setCameraMuted(activities[surviving].rdpInstance, true); Thread.sleep(4000)
            bridge.setCameraMuted(activities[surviving].rdpInstance, false)
            val firstResult = File(if(closeCreator) second else first, "result.json")
            while(!firstResult.exists() && SystemClock.uptimeMillis()<deadline) Thread.sleep(300)
            for (file in listOf(firstResult, secondResult)) {
                check(file.exists())
                val report = org.json.JSONObject(file.readText().removePrefix("\uFEFF"))
                check(report.getBoolean("success")) { report.toString() }
                val samples = report.getJSONArray("attempts").getJSONObject(0).getJSONArray("samples")
                var black = 0
                for(i in 1 until samples.length()-1) {
                    val sample = samples.getJSONObject(i)
                    check(sample.getInt("frames") > 0) { "Frozen camera: $file, $sample" }
                    if(sample.getBoolean("black")) black++
                }
                check(black in 3..6) { "Incorrect session mute ($black seconds): $file" }
                check(!samples.getJSONObject(samples.length()-1).getBoolean("black")) { "Camera did not resume after unmute: $file" }
                val capture = report.getJSONArray("attempts").getJSONObject(0)
                check(capture.getInt("frames") * 1000.0 / samples.getJSONObject(samples.length()-1).getDouble("elapsedMs") >= 15) { "Capture below 15fps: $file" }
            }
            File(root, "summary.json").writeText(org.json.JSONObject().put("success",true).put("target",target)
                .put("simultaneous",true).put("independentMute",true).put("survivesOtherSessionClose",true).toString(2))
        } finally {
            activities.forEach { session -> test.runOnMainSync { session.finish() } }
            test.waitForIdleSync()
            profiles.forEach { removeCameraTestProfile(manager,it.id) }
            bridge.setCameraVerificationPattern(false)
        }
    }

    private fun respondToDisplayConsent(output: File, deny: Boolean, respond: Boolean = true) {
        for (window in automation.windows) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                nodes.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            val description = nodes.joinToString("\n") { "${it.packageName} ${it.viewIdResourceName} ${it.text}" }
            if (!description.contains("com.meta.systemui") && !description.contains("com.android.systemui")) continue
            if (!description.contains("share", true) && !description.contains("screen", true) &&
                !description.contains("recording", true)) continue
            if (!deny) {
                val entireView = nodes.firstOrNull { it.contentDescription?.toString() == "Entire view" }
                if (entireView != null && !entireView.isSelected) {
                    entireView.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            val button = nodes.firstOrNull {
                val label = it.text?.toString().orEmpty().lowercase()
                val id = it.viewIdResourceName.orEmpty()
                it.isClickable && it.isEnabled && if (deny) label in listOf("cancel", "deny", "don't allow") || id == "android:id/button2"
                else label in listOf("start now", "start", "allow", "share") || id == "android:id/button1"
            } ?: continue
            File(output, "consent-nodes.txt").writeText(description)
            automation.takeScreenshot()?.let { screenshot ->
                File(output, "consent.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            if (respond) check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot respond to display consent" }
            return
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

    private fun isBlackFrame(bitmap: Bitmap): Boolean {
        for (row in 1..8) for (col in 1..8) {
            val pixel = bitmap.getPixel(col * bitmap.width / 9, row * bitmap.height / 9)
            if (maxOf(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel)) > 20) return false
        }
        return true
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

    private fun verifyEncoders() {
        FreeRDPBridge()
        val output = File(test.targetContext.getExternalFilesDir(null), "camera-encoder")
        output.mkdirs()
        val report = org.json.JSONArray()
        for (sizes in listOf(listOf(320 to 240, 320 to 240), listOf(320 to 240, 640 to 240),
            listOf(1280 to 720, 1600 to 1200), listOf(320 to 240, 1280 to 1280))) {
            val widths = sizes.map { it.first }
            val heights = sizes.map { it.second }
            val colors = listOf(intArrayOf(81, 90, 240), intArrayOf(145, 54, 34))
            val encoders = sizes.map { (width, height) ->
                QuestCameraEncoder(width, height, 30, if (height >= 720) 2700000 else 700000)
            }
            try {
                val inputs = widths.mapIndexed { index, width ->
                    java.nio.ByteBuffer.allocateDirect(width * heights[index] * 3 / 2).apply {
                        repeat(width * heights[index]) { put(colors[index][0].toByte()) }
                        repeat(width * heights[index] / 4) {
                            put(colors[index][1].toByte()); put(colors[index][2].toByte())
                        }
                        flip()
                    }
                }
                // Deliberately leave multiple outputs pending on both hardware codecs.
                repeat(3) { frame ->
                    encoders.forEachIndexed { index, encoder ->
                        check(encoder.queueInput(inputs[index], (frame + 1L) * 33333))
                    }
                }
                Thread.sleep(300)
                encoders.forEachIndexed { index, encoder ->
                    val count = encoder.drainOutput(10000)
                    check(count == 3) { "Batched drain returned $count of 3 access units" }
                    val packets = List(3) {
                        checkNotNull(encoder.encode(inputs[index], 999999))
                    }
                    val file = File(output, "${sizes[1].first}x${sizes[1].second}-stream-$index.h264")
                    file.outputStream().use { stream -> packets.forEach { stream.write(it) } }
                    // SPS profile_idc must be baseline (66); this verifies the hardware setting.
                    val first = packets.first()
                    val sps = (0 until first.size - 5).firstOrNull { offset ->
                        first[offset] == 0.toByte() && first[offset + 1] == 0.toByte() &&
                            first[offset + 2] == 0.toByte() && first[offset + 3] == 1.toByte() &&
                            first[offset + 4].toInt() and 31 == 7
                    }
                    check(sps != null && first[sps + 5].toInt() and 255 == 66) {
                        "Hardware encoder did not produce baseline SPS"
                    }
                    check(first[sps + 6].toInt() and 0xC0 == 0xC0) {
                        "Hardware encoder did not emit Constrained Baseline flags"
                    }
                    verifyDecodedColors(packets, widths[index], heights[index], colors[index])
                    report.put(org.json.JSONObject().put("width", widths[index])
                        .put("height", heights[index]).put("stream", index).put("batchDrained", count).put("decodedFrames", 3))
                }
            } finally {
                encoders.forEach { it.close() }
            }
        }
        File(output, "result.json").writeText(report.toString(2))
    }

    private fun verifyDecodedColors(packets: List<ByteArray>, width: Int, height: Int, color: IntArray) {
        val decoder = android.media.MediaCodec.createDecoderByType("video/avc")
        try {
            val format = android.media.MediaFormat.createVideoFormat("video/avc", width, height)
            format.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder.configure(format, null, null, 0)
            decoder.start()
            packets.forEachIndexed { index, bytes ->
                val input = decoder.dequeueInputBuffer(100000)
                check(input >= 0)
                checkNotNull(decoder.getInputBuffer(input)).put(bytes)
                decoder.queueInputBuffer(input, 0, bytes.size, (index + 1L) * 33333, 0)
            }
            val info = android.media.MediaCodec.BufferInfo()
            var decoded = 0
            val deadline = SystemClock.uptimeMillis() + 5000
            while (decoded < packets.size && SystemClock.uptimeMillis() < deadline) {
                val buffer = decoder.dequeueOutputBuffer(info, 10000)
                if (buffer < 0) continue
                try {
                    check(info.presentationTimeUs == (decoded + 1L) * 33333) { "Frame order changed" }
                    checkNotNull(decoder.getOutputImage(buffer)).use { image ->
                        check(image.width == width && image.height == height)
                        image.planes.forEachIndexed { planeIndex, plane ->
                            // Interior samples on every row catch mixed frames and stride corruption.
                            val rows = if (planeIndex == 0) height else height / 2
                            val cols = if (planeIndex == 0) width else width / 2
                            for (row in 1 until rows - 1) {
                                val offset = row * plane.rowStride + (cols / 2) * plane.pixelStride
                                val actual = plane.buffer.get(plane.buffer.position() + offset).toInt() and 255
                                check(kotlin.math.abs(actual - color[planeIndex]) <= 12) {
                                    "Stream color corrupted: plane=$planeIndex row=$row actual=$actual"
                                }
                            }
                        }
                    }
                    decoded++
                } finally {
                    decoder.releaseOutputBuffer(buffer, false)
                }
            }
            check(decoded == packets.size) { "Decoded $decoded of ${packets.size} retained frames" }
            decoder.stop()
        } finally {
            decoder.release()
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
