package com.farside.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import com.farside.workspace.data.ProfileManager
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual Windows clipboard round trip, large enough to fragment static-channel PDUs. */
internal class RdpClipboardVerification(private val test: RdpResizeInstrumentation) {
    fun run(): String {
        val manager = ProfileManager(test.targetContext)
        val original = checkNotNull(manager.getProfiles().firstOrNull {
            it.name == "LAPTOP-DDJV33M6" || it.host == "172.16.0.54"
        }) { "Saved LAPTOP profile unavailable" }
        // Recover this harness's fixtures after instrumentation force-stops.
        manager.getProfiles().filter {
            it.name == "Large clipboard verification" && it.host == original.host && it.redirectStorage
        }.forEach { manager.deleteProfile(it.id) }
        val preferences = test.targetContext.getSharedPreferences("farside_profiles", android.content.Context.MODE_PRIVATE)
        check(preferences.edit().commit())
        val profile = original.copy(id = UUID.randomUUID().toString(), name = "Large clipboard verification", redirectStorage = true)
        val token = System.currentTimeMillis().toString()
        val output = File(test.targetContext.getExternalFilesDir(null), "clipboard-large/$token")
        check(output.mkdirs())
        File(output, "probe.ps1").writeText(test.context.assets.open("clipboard/probe.ps1").bufferedReader().use { it.readText() })
        val remote = "\\\\tsclient\\Quest" + output.absolutePath.removePrefix("/sdcard")
            .removePrefix("/storage/emulated/0").replace('/', '\\')
        val clipboard = test.targetContext.getSystemService(ClipboardManager::class.java)
        var oldClip: ClipData? = null
        var activity: SessionActivity? = null
        manager.addProfile(profile)
        // Unworn Quest windows may lack Home focus. Test-only clipboard access avoids
        // interpreting Android's background clipboard denial as an RDP transport failure.
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        try {
            activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("profileId", profile.id)
            }) as SessionActivity
            val session = activity
            await("session") { session.rdpInstance != 0L }
            val bridge = checkNotNull(session.rdpBridge)
            val instance = session.rdpInstance
            await("cliprdr") { bridge.isClipboardConnected(instance) }
            Thread.sleep(4000)
            test.runOnMainSync { oldClip = clipboard.primaryClip }
            fun key(code: Int) {
                bridge.sendKeyEvent(instance, code, true)
                bridge.sendKeyEvent(instance, code, false)
            }
            fun type(text: String) {
                text.forEach {
                    bridge.sendUnicodeKeyEvent(instance, it.code, true)
                    bridge.sendUnicodeKeyEvent(instance, it.code, false)
                    Thread.sleep(5)
                }
            }
            key(0x01)
            bridge.sendKeyEvent(instance, 0x15b, true)
            key(0x13)
            bridge.sendKeyEvent(instance, 0x15b, false)
            Thread.sleep(700)
            bridge.sendKeyEvent(instance, 0x1d, true)
            key(0x1e)
            bridge.sendKeyEvent(instance, 0x1d, false)
            type("powershell.exe -NoProfile -STA -ExecutionPolicy Bypass")
            key(0x1c)
            Thread.sleep(2500)
            type("& '$remote\\probe.ps1' -Token '$token'; exit")
            key(0x1c)
            await("Windows probe ready") { File(output, "ready.txt").exists() }
            val payload = "Farside $token\r\n" + "Farside large clipboard Ω 中 0123456789\r\n".repeat(4096)
            val expected = "REMOTE->QUEST:" + payload
            val received = AtomicReference<String>()
            val listener = bridge.clipboardListener
            bridge.clipboardListener = { text -> received.set(text); listener?.invoke(text) }
            try {
                test.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("Farside verification", payload)) }
                val result = File(output, "result.json")
                await("Windows large clipboard read") {
                    runCatching { org.json.JSONObject(result.readText().removePrefix("\uFEFF")).has("ok") }.getOrDefault(false)
                }
                check(org.json.JSONObject(result.readText().removePrefix("\uFEFF")).getBoolean("ok")) { result.readText() }
                await("remote large clipboard callback") { received.get() == expected }
                await("Android large clipboard") {
                    var text: String? = null
                    test.runOnMainSync { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                    text == expected
                }
                File(output, "android-result.txt").writeText("PASS exact bidirectional text; outbound=${payload.length} UTF-16 units, inbound=${expected.length}; static-channel payload > 300 KiB each direction\n")
            } finally {
                bridge.clipboardListener = listener
                File(output, "done.txt").writeText("done")
            }
            await("Windows clipboard restored") { File(output, "restored.txt").exists() }
            return "Large bidirectional Windows/Android clipboard: exact ${payload.length}/${expected.length} UTF-16 units; artifacts ${output.absolutePath}"
        } finally {
            File(output, "done.txt").writeText("done")
            test.runOnMainSync {
                activity?.finish()
            }
            Thread.sleep(1000)
            test.runOnMainSync {
                oldClip?.let { clipboard.setPrimaryClip(it) } ?: clipboard.clearPrimaryClip()
            }
            manager.deleteProfile(profile.id)
            check(preferences.edit().commit()) { "Failed to persist clipboard fixture removal" }
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        check(predicate()) { "Timed out: $label" }
    }
}
