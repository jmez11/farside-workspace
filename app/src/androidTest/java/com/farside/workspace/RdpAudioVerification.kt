package com.farside.workspace

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.AddProfileForm

/** Real runtime permission dialogs and session controls; audio packets require the Windows probe. */
internal class RdpAudioVerification(private val test: Instrumentation) {
    private val automation get() = test.uiAutomation
    private fun find(label: String): AccessibilityNodeInfo? {
        automation.clearCache()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == label || node.text?.toString() == label && label != "Redirect Microphone" ||
                node.viewIdResourceName?.endsWith(":id/$label") == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { return it } }
            return null
        }
        val displays = automation.windowsOnAllDisplays
        for (i in 0 until displays.size()) for (window in displays.valueAt(i))
            window.root?.let { search(it)?.let { return it } }
        return null
    }
    private fun click(label: String) {
        var node = checkNotNull(find(label)) { "Missing $label" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        Thread.sleep(400)
    }
    private fun await(label: String, seconds: Int = 10) {
        repeat(seconds * 5) { if (find(label) != null) return; Thread.sleep(200) }
        error("Missing $label after ${seconds}s")
    }
    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
        return null
    }
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    fun run(mode: String, encodedProbe: String? = null, verifySpatial: Boolean = false) {
        if (mode == "spatial-live") { run("playback", encodedProbe, true); return }
        if (mode in setOf("spatial-audio", "spatial-isolation", "spatial-same-process")) {
            SpatialAudioVerification(test).run(mode, encodedProbe ?: "space")
            return
        }
        if (mode == "playback-close") {
            check(encodedProbe != null) { "playback-close needs a bounded Windows audioProbe" }
            repeat(3) { run("playback", encodedProbe) }
            android.util.Log.i("RdpAudioVerification", "PASS playback-close: three live playback/mute/resume/session-close cycles")
            return
        }
        if (mode == "playback-teardown") {
            FreeRDPBridge()
            check(QuestAudioVerification.verifyPlaybackTeardown()) { "Concurrent AAudio playback/close/reopen failed" }
            android.util.Log.i("RdpAudioVerification", "PASS playback-teardown: 30 concurrent close/reopen cycles on real AAudio output")
            return
        }
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = service
        if (mode == "blocked-ui") {
            val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val toggles = java.util.concurrent.atomic.AtomicInteger(0)
            try {
                test.runOnMainSync {
                    activity.setContent {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color.Black) {
                                RdpMicrophoneControl(muted = false, recording = false, hostUnsupported = true,
                                    onToggleMute = { toggles.incrementAndGet() }, onInteraction = {})
                            }
                        }
                    }
                }
                await("Microphone redirection unsupported")
                val icon = checkNotNull(find("Microphone redirection unsupported"))
                val iconBounds = android.graphics.Rect().also { icon.getBoundsInScreen(it) }
                val capture = android.graphics.Bitmap.createBitmap(activity.window.decorView.width,
                    activity.window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
                val copied = java.util.concurrent.CountDownLatch(1)
                var copyResult = -1
                test.runOnMainSync {
                    android.view.PixelCopy.request(activity.window, capture, { result -> copyResult = result; copied.countDown() },
                        android.os.Handler(android.os.Looper.getMainLooper()))
                }
                check(copied.await(5, java.util.concurrent.TimeUnit.SECONDS) && copyResult == android.view.PixelCopy.SUCCESS)
                java.io.File(test.targetContext.cacheDir, "rdp-mic-blocked.png").outputStream().use {
                    capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                var redPixels = 0
                var whitePixels = 0
                for (y in iconBounds.top.coerceAtLeast(0) until iconBounds.bottom.coerceAtMost(capture.height))
                    for (x in iconBounds.left.coerceAtLeast(0) until iconBounds.right.coerceAtMost(capture.width)) {
                        val c = capture.getPixel(x, y)
                        if (android.graphics.Color.red(c) > 180 && android.graphics.Color.green(c) < 80) redPixels++
                        if (android.graphics.Color.red(c) > 180 && android.graphics.Color.green(c) > 180) whitePixels++
                    }
                check(redPixels > 10 && whitePixels > 2) { "Missing red prohibition overlay or white mic: $redPixels/$whitePixels" }
                capture.recycle()
                click("Microphone redirection unsupported")
                await("This host does not support microphone redirection.")
                check(toggles.get() == 0) { "Blocked button changed mute" }
                click("OK")
                check(find("This host does not support microphone redirection.") == null)
                click("Microphone redirection unsupported")
                await("This host does not support microphone redirection.")
                click("OK")
                check(toggles.get() == 0)
            } finally { test.runOnMainSync { activity.finish() } }
            return
        }
        val manager = ProfileManager(test.targetContext)
        if (mode.startsWith("setup-")) {
            check(!hasMicrophonePermission(test.targetContext)) { "Revoke permission before setup test" }
            val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                test.runOnMainSync {
                    activity.setContent {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            AddProfileForm(onSave = {}, onCancel = {}, profileManager = manager, onNavigateToSettings = {})
                        }
                    }
                }
                Thread.sleep(1000)
                repeat(15) {
                    if (find("Redirect Microphone") == null) {
                        fun scroll(node: AccessibilityNodeInfo): Boolean {
                            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                            for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                            return false
                        }
                        automation.rootInActiveWindow?.let { scroll(it) }
                        Thread.sleep(200)
                    }
                }
                val toggle = checkNotNull(find("Redirect Microphone"))
                check(!toggle.isChecked) { "Microphone default is on without permission" }
                click("Redirect Microphone")
                await("permission_deny_button")
                val grant = mode == "setup-grant"
                click(if (grant) "permission_allow_button" else "permission_deny_button")
                Thread.sleep(500)
                check(hasMicrophonePermission(test.targetContext) == grant)
                check(checkNotNull(find("Redirect Microphone")).isChecked == grant) { "Wrong microphone toggle after dialog" }
            } finally { test.runOnMainSync { activity.finish() } }
            return
        }
        val original = manager.getProfiles().first { it.type == ConnectionType.RDP }
        val avOutput = if (mode.startsWith("av-sync")) java.io.File(test.targetContext.getExternalFilesDir(null), "audio-av/${System.currentTimeMillis()}").apply { mkdirs() } else null
        val probe = if (avOutput != null) {
            java.io.File(avOutput,"browser-av-probe.ps1").writeText(test.context.assets.open("audio/browser-av-probe.ps1").bufferedReader().use { it.readText() })
            val remote = "\\\\tsclient\\Quest" + avOutput.absolutePath.removePrefix("/sdcard").removePrefix("/storage/emulated/0").replace('/', '\\')
            android.util.Base64.encodeToString("& '$remote\\browser-av-probe.ps1' -OutputDir '$remote'".toByteArray(),android.util.Base64.NO_WRAP)
        } else encodedProbe
        val temporary = original.copy(id = "audio-verification", name = "RDP audio verification", redirectMicrophone = mode != "session-off" && avOutput == null, enableCamera = false, redirectStorage = if(avOutput != null) true else original.redirectStorage)
        manager.addProfile(temporary)
        var activity: SessionActivity? = null
        val evidenceFile = java.io.File(test.targetContext.filesDir, "rdp-host-capabilities.json")
        val evidenceBackup = if (mode == "host-unknown") evidenceFile.readText() else null
        try {
            if (evidenceBackup != null) {
                val otherHost = org.json.JSONObject(evidenceBackup)
                    .put("HostAliases", org.json.JSONArray().put("unrelated-host"))
                evidenceFile.writeText(otherHost.toString())
                check(!RdpHostCapabilities(test.targetContext).microphoneUnsupported(temporary.host))
                check(RdpHostCapabilities(test.targetContext).microphoneUnsupported("unrelated-host"))
            }
            val permissionBefore = hasMicrophonePermission(test.targetContext)
            activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", temporary.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as SessionActivity
            if (!permissionBefore && temporary.redirectMicrophone) {
                await("permission_deny_button")
                var hasTexture = false
                test.runOnMainSync { hasTexture = texture(activity.window.decorView) != null }
                check(!hasTexture && activity.rdpInstance == 0L) { "Connection started before permission response" }
                click(if (mode == "session-grant") "permission_allow_button" else "permission_deny_button")
            }
            repeat(100) {
                if (activity.rdpInstance == 0L) Thread.sleep(100)
            }
            check(activity.rdpInstance != 0L) { "RDP did not connect after permission result" }
            test.runOnMainSync { activity.onWindowFocusChanged(true) }
            Thread.sleep(300)
            await("Keep toolbar visible")
            click("Keep toolbar visible")
            click("Mute speakers")
            await("Unmute speakers")
            click("Unmute speakers")
            await("Mute speakers")
            val micEnabled = temporary.redirectMicrophone && hasMicrophonePermission(test.targetContext)
            if (mode == "host-unsupported") {
                check(micEnabled && RdpHostCapabilities(test.targetContext).microphoneUnsupported(temporary.host)) {
                    "This check requires microphone permission and verified host evidence"
                }
            }
            if (micEnabled && RdpHostCapabilities(test.targetContext).microphoneUnsupported(temporary.host)) {
                await("Microphone redirection unsupported")
                check(find("Mute microphone") == null && find("Unmute microphone") == null)
                click("Microphone redirection unsupported")
                await("This host does not support microphone redirection.")
                click("OK")
                await("Microphone redirection unsupported")
                check(find("Unmute microphone") == null) { "Blocked button toggled mute" }
                val image = android.graphics.Bitmap.createBitmap(activity.window.decorView.width,
                    activity.window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
                val copied = java.util.concurrent.CountDownLatch(1)
                var copyResult = -1
                test.runOnMainSync {
                    android.view.PixelCopy.request(activity.window, image, { result -> copyResult = result; copied.countDown() },
                        android.os.Handler(android.os.Looper.getMainLooper()))
                }
                check(copied.await(5, java.util.concurrent.TimeUnit.SECONDS) && copyResult == android.view.PixelCopy.SUCCESS)
                java.io.File(test.targetContext.cacheDir, "rdp-mic-host-blocked.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            } else if (micEnabled) {
                await("Mute microphone")
                check(!FreeRDPBridge().isMicrophoneRecording(activity.rdpInstance)) { "Recording before remote request" }
                click("Mute microphone")
                await("Unmute microphone")
                click("Unmute microphone")
                await("Mute microphone")
            } else check(find("Mute microphone") == null && find("Unmute microphone") == null) { "Denied/disabled microphone still has toolbar control" }
            if (mode == "host-unknown") {
                check(micEnabled)
                Thread.sleep(15000)
                await("Mute microphone")
                check(find("Microphone redirection unsupported") == null) { "Idle host was falsely marked unsupported" }
                click("Mute microphone")
                await("Unmute microphone")
                click("Unmute microphone")
            }
            if (mode == "stream" || mode == "playback" || mode == "playback-latency" || avOutput != null) {
                // Optionally launch the bounded Windows probe through the existing RDP input path.
                if (probe != null) {
                    val bridge = FreeRDPBridge()
                    val instance = activity.rdpInstance
                    repeat(2) {
                        bridge.sendKeyEvent(instance, 0x01, true)
                        bridge.sendKeyEvent(instance, 0x01, false)
                        Thread.sleep(150)
                    }
                    bridge.sendKeyEvent(instance, 0x15b, true)
                    Thread.sleep(150)
                    bridge.sendKeyEvent(instance, 0x13, true)
                    Thread.sleep(100)
                    bridge.sendKeyEvent(instance, 0x13, false)
                    bridge.sendKeyEvent(instance, 0x15b, false)
                    Thread.sleep(1000)
                    bridge.sendKeyEvent(instance, 0x1d, true)
                    bridge.sendKeyEvent(instance, 0x1e, true)
                    bridge.sendKeyEvent(instance, 0x1e, false)
                    bridge.sendKeyEvent(instance, 0x1d, false)
                    val command = String(android.util.Base64.decode(probe, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    // Stage long probe commands in the console; Run can truncate them.
                    for (character in "powershell.exe -NoProfile -ExecutionPolicy Bypass") {
                        bridge.sendUnicodeKeyEvent(instance, character.code, true)
                        bridge.sendUnicodeKeyEvent(instance, character.code, false)
                        Thread.sleep(10)
                    }
                    bridge.sendKeyEvent(instance, 0x1c, true)
                    bridge.sendKeyEvent(instance, 0x1c, false)
                    Thread.sleep(2000)
                    check(bridge.sendClipboardText(instance, command + "; exit")) { "Remote audio probe clipboard unavailable" }
                    Thread.sleep(400)
                    bridge.sendKeyEvent(instance, 0x1d, true)
                    bridge.sendKeyEvent(instance, 0x2f, true)
                    bridge.sendKeyEvent(instance, 0x2f, false)
                    bridge.sendKeyEvent(instance, 0x1d, false)
                    Thread.sleep(750)
                    bridge.sendKeyEvent(instance, 0x1c, true)
                    bridge.sendKeyEvent(instance, 0x1c, false)
                }
                Thread.sleep(3000)
                test.runOnMainSync {
                    texture(activity.window.decorView)?.bitmap?.let { bitmap ->
                        java.io.File(test.targetContext.cacheDir, "rdp-audio-probe.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                val bridge = FreeRDPBridge()
                if (avOutput != null) {
                    val ready = java.io.File(avOutput,"video-started.json")
                    val result = java.io.File(avOutput,"result.json")
                    val readyDeadline = android.os.SystemClock.uptimeMillis()+60000
                    while(!ready.exists() && !result.exists() && android.os.SystemClock.uptimeMillis()<readyDeadline) Thread.sleep(100)
                    check(ready.exists()) { "Browser video did not start: $avOutput" }
                    val videoPulses = mutableListOf<Long>()
                    val outputLatency = org.json.JSONArray()
                    var brightBefore = false
                    val deadline = android.os.SystemClock.uptimeMillis()+32000
                    val stallAt = android.os.SystemClock.uptimeMillis()+10000
                    var stalled = false
                    var nextMetrics = 0L
                    while(android.os.SystemClock.uptimeMillis()<deadline) {
                        var bright = false
                        test.runOnMainSync {
                            texture(activity.window.decorView)?.bitmap?.let { image ->
                                val pixel = image.getPixel(image.width/2,image.height/2)
                                bright = android.graphics.Color.red(pixel)>220 && android.graphics.Color.green(pixel)>220 && android.graphics.Color.blue(pixel)>220
                                if(bright && !brightBefore && videoPulses.size<2) java.io.File(avOutput,"video-flash.png").outputStream().use {
                                    image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                                }
                                if(!bright && videoPulses.size<2) java.io.File(avOutput,"video-dark.png").outputStream().use {
                                    image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                                }
                                image.recycle()
                            }
                        }
                        val now = android.os.SystemClock.uptimeMillis()
                        if(mode == "av-sync-stall" && !stalled && now>=stallAt) {
                            bridge.stallAudioForVerification(activity.rdpInstance)
                            stalled = true
                        }
                        if(bright && !brightBefore) videoPulses.add(now)
                        brightBefore = bright
                        if(now>=nextMetrics) {
                            val latency=bridge.getAudioLatencyMetrics(activity.rdpInstance)
                            outputLatency.put(org.json.JSONObject().put("timeMs",now).put("outputMs",latency[0]).put("maxOutputMs",latency[1]))
                            nextMetrics=now+1000
                        }
                        Thread.sleep(40)
                    }
                    // Let audio for the last observed video pulse reach the hardware.
                    // Otherwise matching the tail would force an incorrect pulse offset.
                    Thread.sleep(1000)
                    val audioPulses = bridge.getAudioPulseTimes(activity.rdpInstance).toList()
                    val comparison = org.json.JSONObject().put("videoMs",org.json.JSONArray(videoPulses))
                        .put("audioMs",org.json.JSONArray(audioPulses)).put("outputLatency",outputLatency).put("injectedStallMs",if(stalled)1500 else 0)
                    java.io.File(avOutput,"sync.json").writeText(comparison.toString(2))
                    check(videoPulses.size>=20 && audioPulses.size>=20) { "Insufficient actual video/audio pulse observations: $comparison" }
                    // After the deliberate stall, compare only recovered playback. Fit the
                    // unequal pulse intervals; a whole second of delay cannot alias a match.
                    val stableVideo = videoPulses.filter { !stalled || it>stallAt+4000 }.takeLast(12)
                    val candidates = (-audioPulses.size..audioPulses.size).mapNotNull { shift ->
                        val differences = stableVideo.mapIndexedNotNull { i,v -> audioPulses.getOrNull(i+shift)?.minus(v) }
                        if(differences.size != stableVideo.size) null else {
                            val median = differences.sorted()[differences.size/2]
                            if(kotlin.math.abs(median)>4000) null else Triple(median,
                                differences.map { kotlin.math.abs(it-median) }.average(),differences)
                        }
                    }
                    val fit = candidates.minBy { it.second }
                    comparison.put("audioMinusVideoMs",fit.first).put("fitMeanErrorMs",fit.second)
                        .put("pairedDifferencesMs",org.json.JSONArray(fit.third))
                    java.io.File(avOutput,"sync.json").writeText(comparison.toString(2))
                    check(kotlin.math.abs(fit.first)<600 && fit.second<120) { "Audio/video failed to stay synchronized: $comparison" }
                    val finishDeadline=android.os.SystemClock.uptimeMillis()+30000
                    while(!result.exists() && android.os.SystemClock.uptimeMillis()<finishDeadline) Thread.sleep(200)
                    check(result.exists() && org.json.JSONObject(result.readText().removePrefix("\uFEFF")).getBoolean("success"))
                    android.util.Log.i("RdpAudioVerification","PASS av-sync observations: video=${videoPulses.size}, audio=${audioPulses.size}; artifacts=$avOutput")
                    return
                }
                if (mode == "playback" || mode == "playback-latency") {
                    repeat(200) { if (bridge.getAudioFrameCounts(activity.rdpInstance)[2] == 0L) Thread.sleep(200) }
                    val start = bridge.getAudioFrameCounts(activity.rdpInstance)
                    check(start[0] > 0 && start[2] > 0) { "No audible playback samples from Windows" }
                    if (mode == "playback-latency") {
                        val samples = org.json.JSONArray()
                        repeat(20) {
                            Thread.sleep(1000)
                            val latency = bridge.getAudioLatencyMetrics(activity.rdpInstance)
                            samples.put(org.json.JSONObject().put("second",it+1).put("outputMs",latency[0]).put("maxOutputMs",latency[1]))
                        }
                        java.io.File(test.targetContext.cacheDir,"audio-latency.json").writeText(samples.toString(2))
                        android.util.Log.i("RdpAudioVerification", "Audio output latency: $samples")
                    }
                    click("Mute speakers")
                    Thread.sleep(400)
                    val mutedStart = bridge.getAudioFrameCounts(activity.rdpInstance)
                    Thread.sleep(1000)
                    val mutedEnd = bridge.getAudioFrameCounts(activity.rdpInstance)
                    check(mutedEnd[0] > mutedStart[0] && mutedEnd[2] == mutedStart[2]) { "Speaker mute did not send silence: ${mutedStart.toList()} -> ${mutedEnd.toList()}" }
                    click("Unmute speakers")
                    Thread.sleep(1000)
                    val unmuted = bridge.getAudioFrameCounts(activity.rdpInstance)
                    check(unmuted[2] > mutedEnd[2]) { "Speaker audio did not resume" }
                    if (verifySpatial) {
                        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Thread.sleep(600)
                        val sessionId = bridge.getAudioPlaybackSessionId(activity.rdpInstance)
                        check(activity.rdpBridge?.audioWindowSpace != null && activity.rdpBridge?.audioSpatialBound == true)
                        val before = readHorizonAudioState(test)
                        java.io.File(test.targetContext.cacheDir, "audio-spatial-live-launcher.txt").writeText(before)
                        check(horizonSessionIsSpatial(before, sessionId)) { "Live RDP audio flattened when Launcher opened" }
                        click("Keyboard")
                        repeat(80) {
                            if (activity.keyboardPanelState?.lifecycle?.value != metavrx.layout.window.compose.state.WindowLifecycle.Active) Thread.sleep(100)
                        }
                        check(activity.keyboardPanelState?.lifecycle?.value == metavrx.layout.window.compose.state.WindowLifecycle.Active)
                        val during = readHorizonAudioState(test)
                        java.io.File(test.targetContext.cacheDir, "audio-spatial-live-keyboard.txt").writeText(during)
                        check(horizonSessionIsSpatial(during, sessionId)) { "Live RDP audio flattened when companion keyboard opened" }
                        click("Keyboard")
                        android.util.Log.i("RdpAudioVerification", "PASS live spatial session=$sessionId with Launcher and companion keyboard")
                    }
                    android.util.Log.i("RdpAudioVerification", "PASS playback frames=${unmuted[0]} silence during mute, audio after unmute")
                    return
                }
                repeat(300) {
                    if (!bridge.isMicrophoneRecording(activity.rdpInstance)) Thread.sleep(200)
                }
                check(bridge.isMicrophoneRecording(activity.rdpInstance)) { "Windows probe did not open remote recording" }
                await("Mute microphone (recording)")
                val counts = bridge.getAudioFrameCounts(activity.rdpInstance)
                check(counts[0] > 0 && counts[1] > 0) { "No playback/capture frames: ${counts.toList()}" }
                click("Mute microphone (recording)")
                await("Unmute microphone")
                click("Unmute microphone")
                await("Mute microphone (recording)")
                Thread.sleep(1000)
                val later = bridge.getAudioFrameCounts(activity.rdpInstance)
                check(later[0] > counts[0] && later[1] > counts[1]) { "Audio stopped after mute/unmute" }
                android.util.Log.i("RdpAudioVerification", "playback=${later[0]} capture=${later[1]}")
            }
            var frame = false
            test.runOnMainSync { texture(activity.window.decorView)?.bitmap?.let { frame = true; it.recycle() } }
            check(frame) { "Missing desktop after audio controls" }
        } finally {
            activity?.let { test.runOnMainSync { it.finish() } }
            manager.deleteProfile(temporary.id)
            // Instrumentation exits immediately; drain the asynchronous profile write.
            check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
            if (evidenceBackup != null) evidenceFile.writeText(evidenceBackup)
        }
    }
}
