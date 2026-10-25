package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.setup.ConnectionSetupFlow
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal class MacRelayToggleUiVerification(private val test: Instrumentation) {
    fun run(id: String): String {
        val pm = ProfileManager(test.targetContext)
        val vnc = ConnectionProfile(id, "Relay", ConnectionType.VNC, "192.0.2.1", user = "", pass = "")
        val ssh = vnc.copy(id = "ssh-fixture", type = ConnectionType.SSH)
        val credential = com.farside.workspace.data.SavedCredential(name = "Relay warning fixture", user = "", pass = "fixture")
        pm.addCredential(credential)
        val fixture = vnc.copy(name = "Relay Toggle Fixture", sftpPairProfileId = ssh.id, enableMacSshRelay = false, credentialId = credential.id)
        var saved: ConnectionProfile? = null

        val prefs = test.targetContext.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("mac_relay_warning_shown", true).commit()
        ProfileManager(test.targetContext)
        check(!prefs.contains("mac_relay_warning_shown")) { "Legacy warning preference retained" }

        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = checkNotNull(test.waitForMonitorWithTimeout(monitor, 15000)) as MainActivity
            test.waitForIdleSync()
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        ConnectionSetupFlow(initialProfile = fixture, onSave = { saved = it }, onCancel = {}, profileManager = pm, savedProfiles = listOf(fixture, ssh))
                    }
                }
            }

            click("4 "+test.targetContext.getString(R.string.setup_review))
            val toggleLabel = test.targetContext.getString(R.string.mac_ssh_relay_toggle)
            val dialogTitle = test.targetContext.getString(R.string.mac_ssh_relay_dialog_title)
            val enableBtn = test.targetContext.getString(R.string.enable)

            // 1. Click toggle to turn on. Dialog MUST appear.
            click(toggleLabel)
            check(nodes().any { it.text?.toString() == dialogTitle }) { "Dialog did not appear on first toggle" }

            // Approval belongs to the profile only when saved.
            click(enableBtn)
            check(!prefs.contains("mac_relay_warning_shown")) { "Legacy preference written" }

            // 3. Toggle off.
            click(toggleLabel)

            // Without a saved enabled profile, another enable still requires the warning.
            click(toggleLabel)
            check(nodes().any { it.text?.toString() == dialogTitle }) { "Unsaved approval suppressed warning" }
            click(enableBtn)

            check(nodes().none { it.text?.toString()==test.targetContext.getString(R.string.mac_ssh_relay_same_mac) })
            val throughLabel=test.targetContext.getString(R.string.mac_through_ssh)
            click(throughLabel)
            val sshSummary=test.targetContext.getString(R.string.mac_through_ssh_summary,ssh.name,fixture.port)
            repeat(6) {
                nodes().filter {it.isScrollable && it.className?.toString()!="android.widget.EditText"}
                    .forEach {it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)}
                Thread.sleep(150)
            }
            check(nodes().any {it.isVisibleToUser && it.text?.toString()==sshSummary}) {"SSH summary missing"}
            check(nodes().none {it.isVisibleToUser && it.text?.toString()=="VNC · ${fixture.host}:${fixture.port}"})
            test.runOnMainSync {
                val root=activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                File(test.targetContext.cacheDir,"mac-through-ssh-options.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            // Click save
            click(test.targetContext.getString(R.string.save_changes))
            val result = checkNotNull(saved) { "Profile was not saved" }
            check(result.enableMacSshRelay) { "enableMacSshRelay was not true in saved profile" }
            check(result.macSshRelaySameMac) { "Same-Mac selection was not saved" }
            check(result.macThroughSsh) { "Single SSH route was not saved" }
            check(ConnectionProfile.fromJson(result.toJson()).macThroughSsh)

            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        ConnectionSetupFlow(initialProfile = fixture, onSave = { saved = it }, onCancel = {}, profileManager = pm,
                            savedProfiles = listOf(fixture, ssh, result.copy(id = "accepted-fixture")))
                    }
                }
            }
            click("4 "+test.targetContext.getString(R.string.setup_review))
            check(nodes().any {it.text?.toString()=="VNC · ${fixture.host}:${fixture.port}"}) {"Separate-route endpoint missing"}
            click(toggleLabel)
            check(nodes().none { it.text?.toString() == dialogTitle }) { "Saved enabled profile did not suppress warning" }
            click(test.targetContext.getString(R.string.save_changes))
            check(checkNotNull(saved).enableMacSshRelay && !checkNotNull(saved).macThroughSsh)
            return "PASS saved single SSH route/JSON and unchanged separate-route default; warning repeats without saved opt-in; saved opt-in suppresses warning; same-Mac assumed true"
        } finally {
            pm.deleteCredential(credential.id)
            test.removeMonitor(monitor)
            test.runOnMainSync { activity?.finish() }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; repeat(n.childCount) { n.getChild(it)?.let(::visit) } }
        val windows = test.uiAutomation.windowsOnAllDisplays
        for (i in 0 until windows.size()) windows.valueAt(i).forEach {
            it.root?.takeIf {root->root.packageName==test.targetContext.packageName}?.let(::visit)
        }
        return result
    }

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            val all = nodes()
            val node = all.firstOrNull { it.isVisibleToUser && it.isClickable && it.contentDescription?.toString() == label }
                ?: all.firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
            if (node != null) {
                var clickable: AccessibilityNodeInfo = node
                while (!clickable.isClickable && clickable.parent != null) clickable = checkNotNull(clickable.parent)
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(300); return }
            }
            all.filter { it.isScrollable && it.className?.toString() != "android.widget.EditText" }.maxByOrNull {
                android.graphics.Rect().also(it::getBoundsInScreen).height()
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(300)
        }
        File(test.targetContext.cacheDir,"mac-through-ssh-ui-failure.txt").writeText(nodes().joinToString("\n") {
            "${it.className} visible=${it.isVisibleToUser} clickable=${it.isClickable} text=${it.text} description=${it.contentDescription}"
        })
        error("Missing control: $label")
    }
}
