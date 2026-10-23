package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.LocalContext
import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.*
import com.farside.workspace.ssh.*
import java.util.UUID

/** Actual controls with owned data; physical trigger/Horizon presentation still need wearer checks. */
internal class LauncherPolishVerification(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        val flags = test.uiAutomation.serviceInfo
        flags.flags = flags.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = flags
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val previousClip = clipboard.primaryClip
        val namespace = "owned_launcher_polish"
        context.deleteSharedPreferences(namespace + "farside_profiles")
        context.deleteSharedPreferences(namespace + "ssh_host_keys")
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(namespace + name, mode)
            override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply { this.flags = this.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
        }
        val pm = ProfileManager(isolated)
        val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = checkNotNull(test.waitForMonitorWithTimeout(monitor, 15000)) as MainActivity
        val ui = SetupPhaseOneVerification(test)
        fun text(id: Int) = context.getString(id)
        fun up() { test.runOnMainSync { activity.onNavigateUp() }; ui.find(text(R.string.add_connection)) }
        try {
            ui.click(text(R.string.add_connection))
            ui.click(text(R.string.back));ui.find(text(R.string.add_connection))
            ui.click(text(R.string.add_connection))
            ui.click(text(R.string.close));ui.find(text(R.string.add_connection))
            ui.click(text(R.string.add_connection))
            val closeNode = ui.nodes().first { it.packageName?.toString() == context.packageName && it.contentDescription?.toString() == text(R.string.close) && it.isVisibleToUser }
            val close = android.graphics.Rect().also(closeNode::getBoundsInScreen)
            val title = android.graphics.Rect().also { ui.find(text(R.string.setup_new_connection)).getBoundsInScreen(it) }
            check(close.centerX() > title.right && close.centerY() <= title.bottom + close.height()) { "New connection close is not at upper right" }
            ui.capture(activity, "launcher-new-connection.png")
            ui.click(text(R.string.continue_action))
            ui.enter(R.string.host_server, "fixture.invalid")
            check(ui.find(text(R.string.connection_name), true).text.toString() == "fixture.invalid")
            ui.enter(R.string.host_server, "127.0.0.1")
            check(ui.find(text(R.string.connection_name), true).text.toString() == "127.0.0.1")
            ui.enter(R.string.connection_name, "Custom fixture")
            ui.enter(R.string.host_server, "changed.invalid")
            check(ui.find(text(R.string.connection_name), true).text.toString() == "Custom fixture")
            ui.enter(R.string.connection_name, "")
            check(ui.find(text(R.string.connection_name), true).text.toString() == "changed.invalid")
            up()
            ui.click(text(R.string.setup_vault)); ui.find(text(R.string.setup_sign_ins)); up()
            ui.click(text(R.string.setup_info)); ui.find(text(R.string.about)); up()
            if (ui.nodes().any { it.contentDescription?.toString() == text(R.string.files) }) {
                ui.click(text(R.string.files)); up()
            }
            // Isolated vault: a complete credential used by all three protocols gets three badges.
            val credential = SavedCredential(name="Owned shared sign-in", user="fixture", pass="fixture")
            pm.addCredential(credential)
            ConnectionType.entries.forEach { type -> pm.addProfile(ConnectionProfile(UUID.randomUUID().toString(),
                "Owned " + type.name, type, "fixture.invalid", user="", pass="", credentialId=credential.id)) }
            test.runOnMainSync { activity.setContent { CompositionLocalProvider(LocalContext provides isolated, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {
                MaterialTheme(colorScheme=darkColorScheme()) { SignInVault(pm) {} }
            } } }
            ui.find("Owned shared sign-in")
            listOf("RDP", "SSH", "VNC/Mac").forEach { ui.find(it) }
            check(ui.nodes().none { it.text?.toString() == "Login" })
            ui.click(text(R.string.setup_import_key))
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Owned paste fixture", "owned-private-key-paste"))
            val field = ui.find(text(R.string.private_key), true)
            val fieldBounds = android.graphics.Rect().also(field::getBoundsInScreen)
            var targetView: android.view.View? = null
            val origins = mutableListOf<String>()
            test.runOnMainSync {
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                    val id = view.createAccessibilityNodeInfo()?.windowId
                    origins += "${view.javaClass.simpleName}:$id:${view.width}x${view.height}"
                    if (id == field.windowId) targetView = view
                }
            }
            java.io.File(context.cacheDir,"owned-paste-input-geometry.txt").writeText("field=$fieldBounds id=${field.windowId} roots=$origins")
            val target = checkNotNull(targetView) { "No owning window for field: $origins" }
            val origin = IntArray(2)
            test.runOnMainSync { target.getLocationOnScreen(origin) }
            val x = fieldBounds.exactCenterX() - origin[0]
            val y = fieldBounds.exactCenterY() - origin[1]
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int) { test.runOnMainSync {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { target.dispatchTouchEvent(event) } finally { event.recycle() }
            } }
            touch(android.view.MotionEvent.ACTION_DOWN)
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 350)
            touch(android.view.MotionEvent.ACTION_UP)
            val pasteVisible = ui.nodes().any { it.text?.toString() == context.getString(android.R.string.paste) }
            check(pasteVisible) { "Held-touch did not open Paste in the selected field window" }
            ui.click(context.getString(android.R.string.paste))
            check(ui.find(text(R.string.private_key), true).text.toString() == "owned-private-key-paste")
            // Release-like context: no FLAG_DEBUGGABLE, but all About debug actions remain available.
            val keys = SshHostKeyStore(isolated)
            keys.save("fixture.invalid", 22, SshHostKey("ssh-ed25519", "SHA256:owned"))
            test.runOnMainSync { activity.setContent { CompositionLocalProvider(LocalContext provides isolated, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {
                MaterialTheme(colorScheme=darkColorScheme()) { InfoScreen {} }
            } } }
            check(!ConnectionInfoVisibility.get(isolated))
            ui.click(text(R.string.show_connection_info))
            ui.find(text(R.string.hide_connection_info));check(ConnectionInfoVisibility.get(isolated))
            test.runOnMainSync {activity.setContent {CompositionLocalProvider(LocalContext provides isolated) {InfoScreen {}}}}
            ui.find(text(R.string.hide_connection_info))
            ui.click(text(R.string.hide_connection_info))
            ui.find(text(R.string.show_connection_info));check(!ConnectionInfoVisibility.get(isolated))
            ui.find(text(R.string.clear_trusted_certificates))
            ui.find(text(R.string.revoke_granted_permissions))
            ui.click(text(R.string.ssh_host_keys_clear))
            ui.find(text(R.string.ssh_host_keys_cleared))
            check(keys.get("fixture.invalid", 22) == null)
            return "PASS upper-right Close; hostname default follows blank name and preserves custom names; actual window Up returns setup/vault/About/Files to launcher; protocol badges; private-key held-touch Paste; release-context debug actions and isolated clear-host-keys. Synthetic window input/accessibility; physical trigger remains wearer validation"
        } finally {
            test.runOnMainSync { activity.finish() }
            test.removeMonitor(monitor)
            if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip()
            test.uiAutomation.dropShellPermissionIdentity()
            context.deleteSharedPreferences(namespace + "farside_profiles")
            context.deleteSharedPreferences(namespace + "ssh_host_keys")
            context.deleteSharedPreferences(namespace + "debug_options")
        }
    }
}
