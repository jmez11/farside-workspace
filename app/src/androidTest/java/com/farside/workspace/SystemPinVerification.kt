package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.farside.workspace.data.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SystemPinVerification(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        automation.clearCache()
        val displays = automation.windowsOnAllDisplays
        return (0 until displays.size()).flatMap { displays.valueAt(it).flatMap { window -> window.root?.let(::descendants).orEmpty() } }
    }
    private fun click(text: String) {
        repeat(80) {
            var node = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            if (node != null) {
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { Thread.sleep(300); return }
            }
            Thread.sleep(100)
        }
        error("Missing control: $text")
    }

    private fun captureDialog(activity: android.app.Activity) {
        var window = activity.window
        instrumentation.runOnMainSync {
            fun dialog(view: android.view.View): android.view.Window? {
                if (view is androidx.compose.ui.window.DialogWindowProvider) return view.window
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) dialog(view.getChildAt(i))?.let { return it }
                return null
            }
            window = checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::dialog))
        }
        val bitmap = android.graphics.Bitmap.createBitmap(window.decorView.width, window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        instrumentation.runOnMainSync {
            android.view.PixelCopy.request(window, bitmap, { result = it; copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        check(copied.await(5, TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
        File(context.cacheDir, "system-pin-activation.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    fun run(mode: String) {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val report = mutableListOf<String>()
        val grant = DeviceCredentialAuth.UnlockGrant()
        check(!grant.valid(true, false, 100))
        grant.authenticated(false, 100)
        check(!grant.valid(true, false, 101))
        grant.authenticated(true, 100)
        check(grant.valid(true, false, 300099))
        check(!grant.valid(true, false, 300100))
        grant.authenticated(true, 100)
        check(!grant.valid(false, false, 101))
        check(!grant.valid(true, false, 102))
        grant.authenticated(true, 100)
        check(!grant.valid(true, true, 101))
        check(!grant.valid(true, false, 102))
        grant.authenticated(true, 100); grant.clear()
        check(!grant.valid(true, false, 101))
        report += "PASS: unchecked remember, exact 300s expiry, lock/removal invalidation, explicit clear (synthetic clock/state)."
        DeviceCredentialAuth.clear()
        val secure = DeviceCredentialAuth.isSecure(context)
        report += "Actual ordinary UID=${android.os.Process.myUid()}, deviceCredentialAvailable=$secure, Android isDeviceSecure=${context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure}"
        val availability = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            .canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        report += "Actual DEVICE_CREDENTIAL canAuthenticate=$availability"
        val manager = ProfileManager(context)
        val prefs = context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        val originalMode = prefs.getBoolean("system_pin_enabled", false)
        val originalVerifier = prefs.getString("app_pinVerifier", null)
        val credential = SavedCredential(id = "system-pin-fixture", name = "System PIN fixture", user = "fixture", pass = "fixture-secret", requirePin = true)
        val sshKey = SavedSshKey(id = "system-pin-key-fixture", name = "System PIN key fixture", privateKey = "fixture-private-key", requirePin = true)
        val originalCredentials = prefs.getString("credentials", null)
        val originalKeys = prefs.getString("ssh_keys", null)
        val originalProfiles = prefs.getString("profiles", null)
        var main: MainActivity? = null
        var request: CancellationSignal? = null
        try {
            // Test the protected system path without activating it for the user's connections.
            check(prefs.edit().putBoolean("system_pin_enabled", true).commit())
            manager.addCredential(credential)
            manager.addSshKey(sshKey)
            check(manager.getCredentials().single { it.id == credential.id } == credential)
            check(manager.getSshKeys().single { it.id == sshKey.id } == sshKey)
            check("fixture-secret" !in prefs.getString("credentials", "")!!)
            check("fixture-private-key" !in prefs.getString("ssh_keys", "")!!)
            for (type in ConnectionType.entries) {
                val profile = ConnectionProfile(id = "system-pin-profile-${type.name}", name = "System PIN ${type.name}",
                    host = "127.0.0.1", port = 9, type = type, user = "", pass = "",
                    credentialId = if (type == ConnectionType.SSH) null else credential.id,
                    sshKeyId = if (type == ConnectionType.SSH) sshKey.id else null)
                manager.addProfile(profile)
                val session = instrumentation.startActivitySync(Intent(context, SessionActivity::class.java)
                    .putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
                try {
                    Thread.sleep(800)
                    check(session.rdpInstance == 0L && session.vncInstance == 0L && session.sshHistoryScroll == null)
                    click("Unlock with headset passcode")
                    if (secure) {
                        Thread.sleep(1500)
                        check(nodes().any { it.packageName?.toString() in listOf("com.android.settings", "com.oculus.systemux", "com.oculus.vrshell") })

                    } else {
                        check(nodes().any { it.text?.toString()?.startsWith("Set a headset passcode") == true })
                    }
                    check(session.rdpInstance == 0L && session.vncInstance == 0L && session.sshHistoryScroll == null)
                    click("Cancel")
                    report += "PASS: ${type.name} protected startup gate, ${if (secure) "pending system request cancellation" else "missing system PIN rejection"}, no session initialization."
                } finally { instrumentation.runOnMainSync { session.finish() } }
            }
            if (mode != "live") {
                check(prefs.edit().putBoolean("system_pin_enabled", false).commit())
                manager.setAppPinVerifier(CredentialPin.create("135724"))
            }
            main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            if (mode != "live") {
                click("Settings")
                check(nodes().none { it.text?.toString() == "Use headset passcode instead" })
                report += "PASS: system-passcode switch hidden in actual Settings."
                // Exercise the dormant production dialog without exposing the blocked switch.
                instrumentation.runOnMainSync {
                    main!!.setContent {
                        androidx.compose.material3.MaterialTheme {
                            com.farside.workspace.ui.SystemCredentialDialog(
                                title = "Use headset passcode", allowRemembered = false,
                                onUnlocked = { manager.enableSystemPin() }, onCancel = {})
                        }
                    }
                }
                click("Unlock with headset passcode")
                val deadline = android.os.SystemClock.elapsedRealtime() + 35_000
                var failed = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (nodes().any { it.text?.toString()?.let { text ->
                        text.contains("Error authenticating", true) || text.startsWith("Quest did not") ||
                        text.startsWith("System passcode authentication") || text.startsWith("Set a headset passcode")
                    } == true }) { failed = true; break }
                    Thread.sleep(200)
                }
                check(failed) { "Activation rejection was not displayed" }
                check(!manager.usesSystemPin())
                check(CredentialPin.matches("135724", manager.getAppPinVerifier()!!))
                check(!DeviceCredentialAuth.hasRememberedUnlock(context))
                captureDialog(main!!)
                click("Cancel")
                report += "PASS: dormant activation dialog rejected by OS, App PIN verifier retained, mode unchanged, no cached authorization."
            } else {
                val completed = CountDownLatch(1)
                var failure: String? = "No callback"
                instrumentation.runOnMainSync {
                    request = DeviceCredentialAuth.authenticate(main!!, "Verify Farside system PIN", true) {
                        failure = it; completed.countDown()
                    }
                }
                check(completed.await(35, TimeUnit.SECONDS)) { "System authentication callback timed out" }
                check(failure == null) { "System authentication rejected: $failure" }
                check(DeviceCredentialAuth.hasRememberedUnlock(context))
                report += "PASS: actual headset passcode success and remembered authorization."
            }
        } finally {
            instrumentation.runOnMainSync { request?.cancel(); main?.finish() }
            DeviceCredentialAuth.clear()
            val editor = prefs.edit().putBoolean("system_pin_enabled", originalMode)
            if (originalVerifier == null) editor.remove("app_pinVerifier") else editor.putString("app_pinVerifier", originalVerifier)
            for ((key, value) in listOf("credentials" to originalCredentials, "ssh_keys" to originalKeys, "profiles" to originalProfiles)) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            check(editor.commit())
            check(manager.usesSystemPin() == originalMode)
            check(prefs.getString("app_pinVerifier", null) == originalVerifier)
            check(prefs.getString("credentials", null) == originalCredentials)
            check(prefs.getString("ssh_keys", null) == originalKeys)
            check(prefs.getString("profiles", null) == originalProfiles)
            File(context.cacheDir, "system-pin-results.txt").writeText(report.joinToString("\n"))
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", report.joinToString("\n") + "\n") })
    }
}
