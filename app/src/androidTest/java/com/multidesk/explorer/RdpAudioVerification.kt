package com.multidesk.explorer

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
import com.multidesk.explorer.data.ConnectionType
import com.multidesk.explorer.data.ProfileManager
import com.multidesk.explorer.ui.AddProfileForm

/** Real runtime permission dialogs and session controls; audio packets require the Windows probe. */
internal class RdpAudioVerification(private val test: Instrumentation) {
    private val automation get() = test.uiAutomation
    private fun find(label: String): AccessibilityNodeInfo? {
        automation.clearCache()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == label ||
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
    fun run(mode: String, encodedProbe: String? = null) {
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = service
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
        val temporary = original.copy(id = "audio-verification", name = "RDP audio verification", redirectMicrophone = mode != "session-off", enableCamera = false)
        manager.addProfile(temporary)
        var activity: SessionActivity? = null
        try {
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
            if (micEnabled) {
                await("Mute microphone")
                check(!FreeRDPBridge().isMicrophoneRecording(activity.rdpInstance)) { "Recording before remote request" }
                click("Mute microphone")
                await("Unmute microphone")
                click("Unmute microphone")
                await("Mute microphone")
            } else check(find("Mute microphone") == null && find("Unmute microphone") == null) { "Denied/disabled microphone still has toolbar control" }
            if (mode == "stream" || mode == "playback") {
                // Optionally launch the bounded Windows probe through the existing RDP input path.
                if (encodedProbe != null) {
                    val bridge = FreeRDPBridge()
                    val instance = activity.rdpInstance
                    repeat(2) {
                        bridge.sendKeyEvent(instance, 0x01, true)
                        bridge.sendKeyEvent(instance, 0x01, false)
                        Thread.sleep(150)
                    }
                    bridge.sendKeyEvent(instance, 0x15b, true)
                    bridge.sendKeyEvent(instance, 0x13, true)
                    bridge.sendKeyEvent(instance, 0x13, false)
                    bridge.sendKeyEvent(instance, 0x15b, false)
                    Thread.sleep(1000)
                    bridge.sendKeyEvent(instance, 0x1d, true)
                    bridge.sendKeyEvent(instance, 0x1e, true)
                    bridge.sendKeyEvent(instance, 0x1e, false)
                    bridge.sendKeyEvent(instance, 0x1d, false)
                    val command = String(android.util.Base64.decode(encodedProbe, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    for (character in command) {
                        bridge.sendUnicodeKeyEvent(instance, character.code, true)
                        bridge.sendUnicodeKeyEvent(instance, character.code, false)
                        Thread.sleep(2)
                    }
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
                if (mode == "playback") {
                    repeat(200) { if (bridge.getAudioFrameCounts(activity.rdpInstance)[2] == 0L) Thread.sleep(200) }
                    val start = bridge.getAudioFrameCounts(activity.rdpInstance)
                    check(start[0] > 0 && start[2] > 0) { "No audible playback samples from Windows" }
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
        }
    }
}
