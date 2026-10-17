package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File

/** On-device regression check: saved RDP profile, actual task resizes, full-frame captures. */
class RdpResizeInstrumentation : Instrumentation() {
    private var sftpMode: String? = null
    private var sftpProfile: String? = null
    private var sshKeyTransportOnly = false
    private var systemPinMode: String? = null
    private var vncMode: String? = null
    private var vncProfile: String? = null
    private var vncPassword: String? = null
    private var adbCapabilityOnly = false
    private var adbTunnelMode: String? = null
    private var lifecycleMode: String? = null
    private var launcherOnly = false
    private var cloudflaredOnly = false
    private var cloudflaredHost: String? = null
    private var cloudflaredCredential: String? = null
    private var certificateMode: String? = null
    private var audioProbe: String? = null
    private var audioMode: String? = null
    private var cameraMode: String? = null
    private var cameraTarget: String? = null
    private var cameraResolutionArg: String? = null
    private var cameraProfile: String? = null
    private var cameraFfmpeg: String? = null
    private var storageMode: String? = null
    private var inputOnly = false
    private var credentialsOnly = false
    private var credentialDeletionOnly = false
    private var proxiesOnly = false
    private var rdpHttpLive = false
    private var rdpSocksLive = false
    private var windowCapacityOnly = false
    private var windowLaunchOnly = false
    private var sshFeaturesOnly = false
    private var sshOnly = false
    private var sshToolbarOnly = false
    private var sshKeysOnly = false
    private var sshProbe = false
    private var httpProxyOnly = false
    private var zoomTest = false
    private var clipboardOnly = false
    private var imeOnly = false
    private var sshImeOnly = false
    private var jumpArgs: Bundle? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        sftpMode = arguments?.getString("sftpMode")
        jumpArgs = arguments
        sftpProfile = arguments?.getString("sftpProfile")
        sshKeyTransportOnly = arguments?.getString("sshKeyTransportOnly") == "true"
        systemPinMode = arguments?.getString("systemPinMode")
        vncPassword = arguments?.getString("vncPassword")
        vncMode = arguments?.getString("vncMode")
        vncProfile = arguments?.getString("vncProfile")
        adbCapabilityOnly = arguments?.getString("adbCapabilityOnly") == "true"
        adbTunnelMode = arguments?.getString("adbTunnelMode")
        cloudflaredOnly = arguments?.getString("cloudflaredOnly") == "true"
        cloudflaredHost = arguments?.getString("cloudflaredHost")
        cloudflaredCredential = arguments?.getString("cloudflaredCredential")
        lifecycleMode = arguments?.getString("lifecycleMode")
        launcherOnly = arguments?.getString("launcherOnly") == "true"
        certificateMode = arguments?.getString("certificateMode")
        audioProbe = arguments?.getString("audioProbe")
        audioMode = arguments?.getString("audioMode")
        cameraMode = arguments?.getString("cameraMode")
        cameraTarget = arguments?.getString("cameraTarget")
        cameraResolutionArg = arguments?.getString("cameraResolution")
        cameraProfile = arguments?.getString("cameraProfile")
        cameraFfmpeg = arguments?.getString("cameraFfmpeg")
        storageMode = arguments?.getString("storageMode")
        inputOnly = arguments?.getString("inputOnly") == "true"
        credentialsOnly = arguments?.getString("credentialsOnly") == "true"
        credentialDeletionOnly = arguments?.getString("credentialDeletionOnly") == "true"
        proxiesOnly = arguments?.getString("proxiesOnly") == "true"
        rdpHttpLive = arguments?.getString("rdpHttpLive") == "true"
        rdpSocksLive = arguments?.getString("rdpSocksLive") == "true"
        windowCapacityOnly = arguments?.getString("windowCapacityOnly") == "true"
        windowLaunchOnly = arguments?.getString("windowLaunchOnly") == "true"
        sshFeaturesOnly = arguments?.getString("sshFeaturesOnly") == "true"
        sshOnly = arguments?.getString("sshOnly") == "true"
        sshToolbarOnly = arguments?.getString("sshToolbarOnly") == "true"
        sshKeysOnly = arguments?.getString("sshKeysOnly") == "true"
        sshProbe = arguments?.getString("sshProbe") == "true"
        httpProxyOnly = arguments?.getString("httpProxyOnly") == "true"
        zoomTest = arguments?.getString("zoomTest") == "true"
        clipboardOnly = arguments?.getString("clipboardOnly") == "true"
        imeOnly = arguments?.getString("imeOnly") == "true"
        sshImeOnly = arguments?.getString("sshImeOnly") == "true"
        start()
    }

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    override fun onStart() {
        val results = Bundle()
        try {
            if (jumpArgs?.getString("sshPublicOnly") == "true") {
                results.putString("stream", SshPublicKeyVerification(this).run())
                finish(0, results)
                return
            }
            jumpArgs?.getString("rdpName")?.let { name ->
                results.putString("stream", RdpDiscoveryVerification(this).diagnoseName(name))
                finish(0, results)
                return
            }
            jumpArgs?.getString("rdpSavedProfile")?.let { name ->
                results.putString("stream", RdpDiscoveryVerification(this).verifySavedSession(name))
                finish(0, results)
                return
            }
            if (jumpArgs?.getString("rdpDiscoveryOnly") == "true") {
                results.putString("stream", RdpDiscoveryVerification(this).run())
                finish(0, results)
                return
            }
            if (jumpArgs?.getString("localizationOnly") == "true") {
                results.putString("stream", LocalizationVerification(this).run())
                finish(0, results)
                return
            }
            jumpArgs?.getString("jumpMode")?.let { mode ->
                val a = jumpArgs!!
                // Hop hosts are setup-specific: no defaults in code. Resolver mode uses
                // synthetic profiles and ignores them.
                val hop1 = a.getString("jumpHop1") ?: ""
                val hop2 = a.getString("jumpHop2") ?: ""
                val hopDead = a.getString("jumpDead") ?: ""
                if ((mode == "live" || mode == "seed") && (hop1.isBlank() || hop2.isBlank() || hopDead.isBlank())) {
                    results.putString("stream", "FAIL: live/seed needs -e jumpHop1 <bastion> -e jumpHop2 <target> -e jumpDead <dead-host>")
                } else {
                    results.putString("stream", JumpHostVerification(this).run(mode, a.getString("jumpKey"), hop1, hop2, hopDead))
                }
                finish(0, results)
                return
            }
            sftpMode?.let { mode ->
                results.putString("stream", SftpVerification(this).run(mode, sftpProfile))
                finish(0, results)
                return
            }
            if (sshKeyTransportOnly) {
                SshFeaturesVerification(this, imeOnly = true, keyAuth = true).run()
                results.putString("stream", "PASS: saved-key terminal auth, shared transport, forwarding and IME.\n")
                finish(0, results)
                return
            }
            systemPinMode?.let {
                SystemPinVerification(this).run(it)
                results.putString("stream", "PASS: system passcode checks.\n")
                finish(0, results)
                return
            }
            vncMode?.let { mode ->
                val report = VncVerification(this).run(mode, vncProfile, vncPassword)
                results.putString("stream", "PASS: $report\n")
                finish(0, results)
                return
            }
            if (windowLaunchOnly) {
                results.putString("stream", "PASS: ${WindowLaunchVerification(this).run()}\n")
                finish(0, results)
                return
            }
            if (windowCapacityOnly) {
                results.putString("stream", "PASS: ${WindowCapacityVerification(this).run()}\n")
                finish(0, results)
                return
            }
            adbTunnelMode?.let { mode ->
                val report = AdbTunnelVerification(this).run(mode)
                results.putString("stream", "PASS: $report\n")
                finish(0, results)
                return
            }
            if (adbCapabilityOnly) {
                val report = AdbCapabilityVerification(this).run()
                results.putString("stream", "$report\n")
                finish(0, results)
                return
            }
            if (launcherOnly) {
                val report = LauncherArrangementVerification(this).run()
                results.putString("stream", "PASS: $report\n")
                finish(0, results)
                return
            }
            if (imeOnly) {
                val report = ImeVerification(this).run()
                results.putString("stream", "PASS: $report\n")
                finish(0, results)
                return
            }
            if (zoomTest) {
                val report = verifyZoomTouchCoordinates()
                results.putString("stream", "PASS:\n$report\n")
                finish(0, results)
                return
            }
            if (clipboardOnly) {
                val report = verifyClipboard()
                results.putString("stream", "PASS: $report\n")
                finish(0, results)
                return
            }
            if (proxiesOnly) {
                ProxyVerification(this).run()
                results.putString("stream", "PASS: four proxy choices, RDP HTTP/SOCKS5, SSH SOCKS5 transport, and unsupported proxy rejection without direct TCP fallback.\n")
                finish(0, results)
                return
            }
            if (rdpHttpLive) {
                results.putString("stream", ProxyVerification(this).runLiveRdp() + "\n")
                finish(0, results)
                return
            }
            if (rdpSocksLive) {
                results.putString("stream", ProxyVerification(this).runLiveRdp(socks5 = true) + "\n")
                finish(0, results)
                return
            }
            if (sshFeaturesOnly || sshImeOnly) {
                SshFeaturesVerification(this, imeOnly = sshImeOnly).run()
                results.putString("stream", if (sshImeOnly) "PASS: actual Compose IME typing/replacement and buffered Enter over encrypted SSH.\n" else "PASS: SSH forwarding traffic, live edits, terminal settings, selection, scrollback and joystick checks.\n")
                finish(0, results)
                return
            }
            if (sshToolbarOnly) {
                SshVerification(this).run(toolbarOnly = true)
                results.putString("stream", "PASS: SSH floating toolbar controls present; menu/settings menu button absent.\n")
                finish(0, results)
                return
            }
            if (sshOnly) {
                SshVerification(this).run()
                results.putString("stream", "PASS: SSH session floating toolbar (name, host, pin, zoom font sizing, keyboard toggle), always-full extra keys (modifier row, Backspace, F-keys/arrows, Number/Function Keys swap), no retired 123/ABC controls, and unchanged main content window geometry.\n")
                finish(0, results)
                return
            }
            if (sshKeysOnly) {
                SshKeyAndPinVerification(this).run()
                results.putString("stream", "PASS: SSH key saving, App PIN lock/cache, key association, optional credentials and Keystore encryption.\n")
                finish(0, results)
                return
            }
            if (sshProbe) {
                runSshProbe(results)
                finish(0, results)
                return
            }
            if (httpProxyOnly) {
                val profiles = ProfileManager(targetContext).getProfiles()
                val profile = profiles.single { it.name == "nano via http" }
                check(profile.proxyType == com.farside.workspace.data.ProxyType.HTTP)
                val upstreamName = checkNotNull(jumpArgs?.getString("proxyUpstreamProfile")) {
                    "Missing instrumentation arg: pass -e proxyUpstreamProfile <saved SSH profile name>"
                }
                val upstream = profiles.single { it.name == upstreamName }
                val upstreamActivity = startActivitySync(android.content.Intent(targetContext, SessionActivity::class.java)
                    .putExtra("profileId", upstream.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
                try {
                    var ready = false
                    for (attempt in 0 until 100) {
                        ready = runCatching { java.net.Socket("127.0.0.1", profile.proxyPort!!).use { } }.isSuccess
                        if (ready) break
                        Thread.sleep(100)
                    }
                    check(ready) { "Saved SSH forwarding proxy did not start" }
                    val lease = com.farside.workspace.ssh.SshConnectionManager.acquire(targetContext, "http-proxy-live-check")
                    try {
                        val ssh = lease.connect(profile)
                        check(ssh.isConnected && ssh.isAuthenticated)
                        results.putString("stream", "PASS: saved nano via http authenticated over SSHJ HTTP CONNECT.\n")
                    } finally { lease.close() }
                } finally { runOnMainSync { upstreamActivity.finish() } }
                finish(0, results)
                return
            }
            if (credentialDeletionOnly) {
                CredentialUiVerification(this).verifyLiveDeletion()
                results.putString("stream", "PASS: credential deletion closed its live RDP session and rejected saved-profile reconnect.\n")
                finish(0, results)
                return
            }
            if (credentialsOnly) {
                CredentialUiVerification(this).run()
                results.putString("stream", "PASS: credential lifecycle, App PIN setup/gates/backoff, password encryption/IME and stable links.\n")
                finish(0, results)
                return
            }
            cloudflaredHost?.let {
                CloudflaredVerification(this).runLive(it, cloudflaredCredential)
                results.putString("stream", "PASS: live Cloudflared RDP and cached reconnect.\n")
                finish(0, results)
                return
            }
            if (cloudflaredOnly) {
                CloudflaredVerification(this).run()
                results.putString("stream", "PASS: Cloudflared security and packaged process/authentication fixtures.\n")
                finish(0, results)
                return
            }
            lifecycleMode?.let {
                SessionLifecycleVerification(this).run(it)
                results.putString("stream", "PASS: session lifecycle $it.\n")
                finish(0, results)
                return
            }
            certificateMode?.let {
                RdpCertificateVerification(this).run(it)
                results.putString("stream", "PASS: RDP certificates $it.\n")
                finish(0, results)
                return
            }
            audioMode?.let {
                RdpAudioVerification(this).run(it, audioProbe)
                results.putString("stream", "PASS: RDP audio $it.\n")
                finish(0, results)
                return
            }
            cameraMode?.let {
                RdpCameraVerification(this, cameraProfile, cameraFfmpeg).run(it, cameraTarget, cameraResolutionArg)
                results.putString("stream", "PASS: RDP camera $it.\n")
                finish(0, results)
                return
            }
            storageMode?.let {
                RdpStorageVerification(this).run(it)
                results.putString("stream", "PASS: RDP storage $it.\n")
                finish(0, results)
                return
            }
            verifyPointerGestures()
            verifyDoubleTapTolerance()
            verifyControllerScroll()
            verifyZoomedPointerMapping()
            if (inputOnly) {
                results.putString("stream", "PASS: pointer gestures, double-tap position tolerance, four-direction controller wheel/deadzone/repeat/stop, and zoomed pointer coordinate mapping.\n")
                finish(0, results)
                return
            }
            val profile = ProfileManager(targetContext).getProfiles().first { it.type == ConnectionType.RDP }
            val activity = startActivitySync(Intent(targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", profile.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Thread.sleep(6000)
            val report = StringBuilder()
            for ((width, height) in listOf(1280 to 800, 1000 to 600, 1600 to 900, 800 to 1000, 1280 to 800)) {
                uiAutomation.executeShellCommand("am task resize ${activity.taskId} 0 0 $width $height").close()
                Thread.sleep(3000)
                // Flush a pending TextureView layer update before copying its GPU image.
                runOnMainSync {
                    texture(activity.window.decorView)?.let {
                        it.bitmap?.recycle()
                        it.invalidate()
                    }
                }
                Thread.sleep(750)
                var image: Bitmap? = null
                var failure: Throwable? = null
                runOnMainSync {
                    try {
                        val content = activity.findViewById<View>(android.R.id.content)
                        val view = checkNotNull(texture(content)) { "Missing TextureView" }
                        val contentPosition = IntArray(2).also { content.getLocationInWindow(it) }
                        val texturePosition = IntArray(2).also { view.getLocationInWindow(it) }
                        check(contentPosition.contentEquals(texturePosition)) { "Texture offset inside content" }
                        check(view.width == content.width && view.height == content.height) {
                            "Texture ${view.width}x${view.height} does not fill content ${content.width}x${content.height}"
                        }
                        check(view.width == width && view.height == height) {
                            "Task resize not applied: ${view.width}x${view.height}, expected ${width}x${height}"
                        }
                        image = checkNotNull(view.bitmap) { "Missing rendered texture" }
                        report.append("${view.width}x${view.height}: fills content; ")
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
                failure?.let { throw it }
                var bitmap = checkNotNull(image)
                fun hasContent(candidate: Bitmap): Boolean {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until candidate.height step 17)
                        for (x in 0 until candidate.width step 17) colors.add(candidate.getPixel(x, y))
                    return colors.size > 16
                }
                // A server can take several seconds to repaint after a resolution change.
                for (attempt in 0 until 20) {
                    if (hasContent(bitmap)) break
                    Thread.sleep(500)
                    bitmap.recycle()
                    runOnMainSync { image = texture(activity.window.decorView)?.bitmap }
                    bitmap = checkNotNull(image)
                }
                File(targetContext.cacheDir, "rdp-verification-${width}x${height}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                check(hasContent(bitmap)) { "Blank/flat frame at ${width}x${height}" }
                bitmap.recycle()
            }
            runOnMainSync { activity.finish() }
            Thread.sleep(1500) // Exercise native worker shutdown after an active GFX session.
            results.putString("stream", "PASS: $report\nPNG captures in target app cache; session closed.\n")
            finish(0, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(1, results)
        }
    }
    private fun verifyControllerScroll() {
        val sent = mutableListOf<Int>()
        lateinit var scroll: RdpControllerScroll
        fun motion(x: Float, y: Float, source: Int = android.view.InputDevice.SOURCE_JOYSTICK) {
            val coords = android.view.MotionEvent.PointerCoords().apply {
                setAxisValue(android.view.MotionEvent.AXIS_X, x)
                setAxisValue(android.view.MotionEvent.AXIS_Y, y)
            }
            val event = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_MOVE, 1,
                arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
            try { check(scroll.onMotion(event) == (source == android.view.InputDevice.SOURCE_JOYSTICK)) }
            finally { event.recycle() }
        }
        runOnMainSync {
            scroll = RdpControllerScroll { sent.add(it) }
            motion(0.1f, -0.1f)
            check(sent.isEmpty())
            for ((x, y, expected) in listOf(Triple(0f, -1f, 0x278), Triple(0f, 1f, 0x388),
                Triple(-1f, 0f, 0x588), Triple(1f, 0f, 0x478))) {
                motion(x, y)
                check(sent.last() == expected) { "Incorrect wheel direction: ${sent.last()}" }
                motion(0f, 0f)
            }
            motion(1f, -1f)
        }
        Thread.sleep(250)
        var stoppedCount = 0
        runOnMainSync {
            check(sent.size >= 10) { "Held stick did not repeat both axes" }
            scroll.stop()
            stoppedCount = sent.size
            motion(1f, 1f, android.view.InputDevice.SOURCE_TOUCHSCREEN)
        }
        Thread.sleep(200)
        runOnMainSync {
            check(sent.size == stoppedCount) { "Scroll continued after stop" }
            sent.clear()
            motion(0.3f, 0f) // Small deflection (0.3): delay is ~709ms
            check(sent.size == 1) { "Initial small deflection event missing" }
        }
        Thread.sleep(250)
        runOnMainSync {
            check(sent.size == 1) { "Small stick deflection repeated too fast: expected 1, got ${sent.size}" }
            scroll.stop()
        }
    }

    private fun verifyDoubleTapTolerance() {
        val sent = mutableListOf<Triple<Float, Float, Int>>()
        val gesture = RdpPointerGesture(8f, { x, y, flags -> sent.add(Triple(x, y, flags)) }, { _, _ -> })
        fun event(action: Int, time: Long, x: Float, y: Float = 30f) {
            val motion = android.view.MotionEvent.obtain(0, time, action, x, y, 0)
            try { gesture.onTouch(motion) } finally { motion.recycle() }
        }
        fun pair(x: Float, y: Float, delay: Long, expectedX: Float, expectedY: Float) {
            gesture.cancel()
            sent.clear()
            event(android.view.MotionEvent.ACTION_DOWN, 1000, 30f)
            event(android.view.MotionEvent.ACTION_UP, 1050, 30f)
            event(android.view.MotionEvent.ACTION_DOWN, 1050 + delay, x, y)
            event(android.view.MotionEvent.ACTION_UP, 1100 + delay, x, y)
            check(sent.size == 4 && sent[2].first == expectedX && sent[2].second == expectedY) {
                "Incorrect double-tap snapping: $sent"
            }
        }
        pair(42f, 38f, 100, 30f, 30f) // Diagonal jitter beyond ordinary drag slop, within double-tap radius.
        pair(46f, 30f, 100, 30f, 30f) // Inclusive radius boundary.
        pair(47f, 30f, 100, 47f, 30f)
        pair(42f, 38f, android.view.ViewConfiguration.getDoubleTapTimeout().toLong(), 42f, 38f)
        gesture.cancel()
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 2000, 30f)
        event(android.view.MotionEvent.ACTION_UP, 2050, 30f)
        gesture.cancel()
        event(android.view.MotionEvent.ACTION_DOWN, 2100, 42f)
        event(android.view.MotionEvent.ACTION_UP, 2150, 42f)
        check(sent[2].first == 42f) { "Cancellation retained a double-tap candidate" }
    }

    private fun verifyPointerGestures() {
        for ((elapsed, expected) in listOf(0L to 0f, 499L to 0f, 500L to 0f,
            625L to 0.25f, 750L to 0.5f, 875L to 0.75f, 1000L to 1f, 1500L to 1f)) {
            check(rightClickBoxProgress(elapsed) == expected) { "Wrong right-click box progress at $elapsed" }
        }
        val sent = mutableListOf<Int>()
        var indicator = false
        val gesture = RdpPointerGesture(8f, { _, _, flags -> sent.add(flags) }, { point, _ -> indicator = point != null })
        fun event(action: Int, elapsed: Long, x: Float = 30f) {
            val motion = android.view.MotionEvent.obtain(100L, 100L + elapsed, action, x, 30f, 0)
            gesture.onTouch(motion)
            motion.recycle()
        }
        val left = FreeRDPBridge.PTR_FLAGS_BUTTON1
        val right = FreeRDPBridge.PTR_FLAGS_BUTTON2
        val down = FreeRDPBridge.PTR_FLAGS_DOWN
        for (duration in listOf(100L, 999L, 1000L, 1500L)) {
            sent.clear()
            event(android.view.MotionEvent.ACTION_DOWN, 0)
            check(sent.isEmpty() && indicator) { "Hold must not send left down" }
            event(android.view.MotionEvent.ACTION_UP, duration)
            val button = if (duration >= 1000L) right else left
            check(sent == listOf(button or down, button) && !indicator) { "Wrong click at $duration: $sent" }
        }
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_MOVE, 100, 50f)
        event(android.view.MotionEvent.ACTION_UP, 1500, 60f)
        check(sent == listOf(left or down, FreeRDPBridge.PTR_FLAGS_MOVE, left) && !indicator)
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_CANCEL, 1500)
        check(sent.isEmpty() && !indicator)
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_MOVE, 100, 50f)
        event(android.view.MotionEvent.ACTION_CANCEL, 200)
        check(sent.last() == left && !indicator) { "Cancel left a pressed button" }
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_POINTER_DOWN, 100)
        event(android.view.MotionEvent.ACTION_UP, 1500)
        check(sent.isEmpty() && !indicator)
        val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply {
            id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
        })
        val coordinates = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 30f; y = 30f })
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val motion = android.view.MotionEvent.obtain(100L, 200L, action, 1, properties, coordinates,
                0, if (action == android.view.MotionEvent.ACTION_DOWN) android.view.MotionEvent.BUTTON_SECONDARY else 0,
                1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
            gesture.onTouch(motion)
            motion.recycle()
        }
        check(sent == listOf(right or down, right)) { "Secondary button release lost its identity" }
    }


    private fun verifyZoomedPointerMapping() {
        val viewW = 1280f
        val viewH = 800f
        val actualW = 1280
        val actualH = 800

        // 1. Identity transform: 100% zoom, 0 pan
        check(mapScreenToDesktop(0f, 0f, viewW, viewH, 1f, 0f, 0f, actualW, actualH) == 0 to 0)
        check(mapScreenToDesktop(640f, 400f, viewW, viewH, 1f, 0f, 0f, actualW, actualH) == 640 to 400)
        check(mapScreenToDesktop(1280f, 800f, viewW, viewH, 1f, 0f, 0f, actualW, actualH) == (actualW - 1) to (actualH - 1))

        // 2. 142% zoom (user scenario: zoom = 1.42f, centered)
        val zoom = 1.42f
        check(mapScreenToDesktop(640f, 400f, viewW, viewH, zoom, 0f, 0f, actualW, actualH) == 640 to 400)

        for (tx in listOf(100f, 300f, 640f, 900f, 1100f)) {
            for (ty in listOf(100f, 250f, 400f, 600f, 750f)) {
                val screenX = (tx - viewW / 2f) * zoom + viewW / 2f
                val screenY = (ty - viewH / 2f) * zoom + viewH / 2f
                val (rdpX, rdpY) = mapScreenToDesktop(screenX, screenY, viewW, viewH, zoom, 0f, 0f, actualW, actualH)
                check(kotlin.math.abs(rdpX - tx.toInt()) <= 1) { "142% zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
                check(kotlin.math.abs(rdpY - ty.toInt()) <= 1) { "142% zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
            }
        }

        // 3. High zoom (2.0f and 3.0f) with pan
        for (z in listOf(2.0f, 3.0f)) {
            val panX = 120f
            val panY = -80f
            for (tx in listOf(500f, 640f, 800f)) {
                for (ty in listOf(300f, 400f, 500f)) {
                    val screenX = (tx - viewW / 2f) * z + viewW / 2f + panX
                    val screenY = (ty - viewH / 2f) * z + viewH / 2f + panY
                    val (rdpX, rdpY) = mapScreenToDesktop(screenX, screenY, viewW, viewH, z, panX, panY, actualW, actualH)
                    check(kotlin.math.abs(rdpX - tx.toInt()) <= 1) { "$z zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
                    check(kotlin.math.abs(rdpY - ty.toInt()) <= 1) { "$z zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
                }
            }
        }

        // 4. Clamping bounds check
        check(mapScreenToDesktop(-500f, -500f, viewW, viewH, 1.42f, 0f, 0f, actualW, actualH) == 0 to 0)
        check(mapScreenToDesktop(5000f, 5000f, viewW, viewH, 1.42f, 0f, 0f, actualW, actualH) == (actualW - 1) to (actualH - 1))
    }


    private fun verifyZoomTouchCoordinates(): String {
        val profile = ProfileManager(targetContext).getProfiles().first { it.type == ConnectionType.RDP }
        val activity = startActivitySync(Intent(targetContext, SessionActivity::class.java).apply {
            putExtra("profileId", profile.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as SessionActivity
        val sb = StringBuilder()
        Thread.sleep(6000)

        val touchesReceived = mutableListOf<Triple<Float, Float, Float>>()
        activity.onTouchDebug = { event, z, _, _ ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                touchesReceived.add(Triple(z, event.x, event.y))
                sb.append("onTouchDebug z=$z: event=(${event.x}, ${event.y})\n")
            }
        }

        for (z in listOf(1.0f, 1.25f, 1.42f, 1.5f, 2.0f, 3.0f)) {
            runOnMainSync {
                activity.setZoom?.invoke(z)
            }
            Thread.sleep(300)
            runOnMainSync {
                val downTime = android.os.SystemClock.uptimeMillis()
                val event1 = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, 800f, 500f, 0)
                event1.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(event1)
                event1.recycle()
            }
            Thread.sleep(200)
        }
        runOnMainSync { activity.finish() }
        check(touchesReceived.size == 6) { "Expected 6 touch events across all zoom levels, got ${touchesReceived.size}: $touchesReceived" }
        for ((z, x, y) in touchesReceived) {
            check(x == 800f && y == 500f) { "At zoom $z: expected unscaled screen coords (800, 500), got ($x, $y)" }
        }
        sb.append("ALL 6 ZOOM LEVELS (1.0x to 3.0x) PASSED: Screen coordinates are unscaled (800, 500) and delivered properly!\n")
        return sb.toString()
    }

    private fun verifyClipboard(): String {
        return RdpClipboardVerification(this).run()
    }

    private fun runSshProbe(results: Bundle) {
        val pm = ProfileManager(targetContext)
        val probeHost = jumpArgs?.getString("sshProbeHost")
        val profile = probeHost?.let { frag -> pm.getProfiles().find { it.host.contains(frag) } }
            ?: pm.getProfiles().firstOrNull { it.type == ConnectionType.SSH }
        val sshKey = pm.getSshKeys().find { it.id == profile?.sshKeyId }
            ?: pm.getSshKeys().firstOrNull()
        val report = StringBuilder()
        report.append("Profile: name=${profile?.name}, host=${profile?.host}, user='${profile?.user}', keyId=${profile?.sshKeyId}\n")
        report.append("SavedKey: id=${sshKey?.id}, name=${sshKey?.name}, len=${sshKey?.privateKey?.length}\n")

        val cleanKey = sshKey?.privateKey?.trim()?.replace("\r\n", "\n") ?: ""
        val tunnel = com.farside.workspace.tunnel.CloudflaredTunnel(targetContext)
        try {
            val port = tunnel.start(
                hostInput = profile?.host ?: checkNotNull(probeHost) {
                    "Missing instrumentation arg: pass -e sshProbeHost <host> (or save a matching SSH profile)"
                },
                parameters = profile?.cloudflaredParameters ?: "",
                onStatus = { report.append("Tunnel status: $it\n") },
                onExit = {},
                accessSubcommand = "ssh"
            )
            report.append("Tunnel listening on 127.0.0.1:$port\n")

            com.termux.terminal.SshTerminalSession.ensureBouncyCastleProvider()
            val ssh = net.schmizz.sshj.SSHClient()
            ssh.addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
            ssh.connect("127.0.0.1", port)
            report.append("Connected. ServerVersion: ${ssh.transport.serverVersion}\n")

            val keyProvider = ssh.loadKeys(cleanKey, null as String?, null)
            report.append("Key loaded: type=${keyProvider.type}, algo=${keyProvider.public.algorithm}\n")
            report.append("Key fingerprint: ${net.schmizz.sshj.common.SecurityUtils.getFingerprint(keyProvider.public)}\n")

            val keyAlgos = runCatching { ssh.transport.config.keyAlgorithms.map { it.name } }.getOrNull()
            report.append("Client key algorithms: $keyAlgos\n")

            val extraUsers = jumpArgs?.getString("sshProbeUsers")?.split(",")?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: listOf("admin", "ubuntu", "debian", "root")
            val testUsers = (listOfNotNull(profile?.user?.takeIf { it.isNotBlank() }) + extraUsers).distinct()

            var sshAuthenticated = false
            for (u in testUsers) {
                report.append("Trying auth with user '$u'...\n")
                var sshConn: net.schmizz.sshj.SSHClient? = null
                try {
                    sshConn = net.schmizz.sshj.SSHClient().apply {
                        addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
                        connect("127.0.0.1", port)
                    }
                    val passFinder = if (sshKey?.passphrase?.isNotEmpty() == true) {
                        net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(sshKey.passphrase.toCharArray())
                    } else null
                    val kp = sshConn.loadKeys(cleanKey, null as String?, passFinder)
                    sshConn.authPublickey(u, kp)
                    report.append(">>> Auth for '$u' SUCCESS! authenticated=${sshConn.isAuthenticated}\n")
                    if (sshConn.isAuthenticated) {
                        sshAuthenticated = true
                        if (profile != null && profile.user.isBlank()) {
                            val updated = profile.copy(user = u)
                            pm.updateProfile(updated)
                            report.append("Profile updated with user='$u'. Stored user: '${pm.getProfiles().find { it.id == profile.id }?.user}'\n")
                        }
                        val sess = sshConn.startSession()
                        sess.allocateDefaultPTY()
                        val sh = sess.startShell()
                        sh.outputStream.write("echo 'TEST_SSH_OK'\r\n".toByteArray())
                        sh.outputStream.flush()
                        val buf = ByteArray(1024)
                        val readLen = sh.inputStream.read(buf)
                        val resp = if (readLen > 0) String(buf, 0, readLen) else ""
                        report.append("Shell response: $resp\n")
                        sess.close()
                        break
                    }
                } catch (e: Exception) {
                    val allowed = try { sshConn?.userAuth?.allowedMethods } catch (ex: Exception) { null }
                    report.append("Auth for '$u' FAILED: ${e::class.java.simpleName}: ${e.message}, allowed=$allowed\n")
                } finally {
                    try { sshConn?.disconnect() } catch (e: Exception) {}
                }
            }
            check(sshAuthenticated) { "SSH authentication failed for all candidate users: $report" }
        } catch (e: Exception) {
            report.append("Probe exception: ${e.stackTraceToString()}\n")
            throw e
        } finally {
            try { tunnel.close() } catch (e: Exception) {}
        }
        results.putString("stream", report.toString())
    }
}


