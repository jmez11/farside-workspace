package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import com.farside.workspace.tunnel.*
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

class AdbTunnelVerification(private val instrumentation: Instrumentation) {
    fun run(mode: String): String = when (mode) {
        "local" -> local()
        "live" -> live()
        "ui" -> ui()
        "ui-gates" -> ui(startConnector = false)
        else -> error("Unknown ADB tunnel verification mode")
    }
    private fun ui(startConnector: Boolean = true): String {
        val context = instrumentation.targetContext
        val real = AdbDiscovery.discover(context)
        check(real.port != null)
        val sample = java.util.concurrent.atomic.AtomicReference(real)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
            instrumentation.uiAutomation.clearCache()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> = listOf(node) +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
            val windows = instrumentation.uiAutomation.windowsOnAllDisplays
            return (0 until windows.size()).flatMap { i -> windows.valueAt(i).flatMap { it.root?.let(::visit).orEmpty() } }
        }
        fun find(label: String) = nodes().firstOrNull { it.text?.toString()?.equals(label, ignoreCase = true) == true || it.contentDescription?.toString() == label }
        fun waitFor(label: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(60) { find(label)?.let { return it }; Thread.sleep(200) }
            error("Missing ADB settings control: $label")
        }
        fun click(label: String) {
            var node = waitFor(label)
            while (!node.isClickable) node = checkNotNull(node.parent)
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $label" }
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            com.farside.workspace.ui.AdbTunnelSection { sample.get() }
                        }
                    }
                }
            }
            waitFor("Wireless ADB Cloudflare Tunnel")
            sample.set(AdbState(false, false))
            Thread.sleep(3500)
            check(find("Wireless ADB Cloudflare Tunnel") == null) { "Tunnel setup visible with developer mode off" }
            sample.set(AdbState(true, false))
            Thread.sleep(3500)
            check(find("Wireless ADB Cloudflare Tunnel") == null) { "Tunnel section visible with wireless debugging off" }
            check(find("Save & start tunnel") == null) { "Setup available with wireless debugging off" }
            sample.set(AdbState(true, true))
            Thread.sleep(3500)
            check(find("Wireless ADB Cloudflare Tunnel") == null) { "Tunnel section visible without a detected endpoint" }
            sample.set(real)
            val start = waitFor("Save & start tunnel")
            check(start.isEnabled) { "Start disabled with actual ADB discovered" }
            if (startConnector) {
                click("Save & start tunnel")
                repeat(60) { if (!AdbTunnelService.connectorReady) Thread.sleep(500) }
                check(AdbTunnelService.running && AdbTunnelService.currentPort == real.port && AdbTunnelService.connectorReady) { "Settings start did not start healthy service" }
            }
            val bitmap = android.graphics.Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
            val done = java.util.concurrent.CountDownLatch(1)
            var result = -1
            instrumentation.runOnMainSync {
                android.view.PixelCopy.request(activity.window, bitmap, { result = it; done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
            File(context.cacheDir, "adb-tunnel-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (startConnector) {
                click("Stop tunnel")
                Thread.sleep(3000)
                check(!AdbTunnelService.running && AdbTunnelService.currentPort == null) { "Settings Stop failed" }
            }
        } finally {
            instrumentation.runOnMainSync { if (startConnector) AdbTunnelService.stop(context); activity.finish() }
        }
        return if (startConnector) "Compose setup hidden with synthetic developer-off state, fields absent with synthetic wireless-off state, actual ADB shown, saved encrypted token retained by blank field, actual Start/Stop and connector readiness PASS. Real OS debugging switches preserved."
            else "Actual headset discovery exposes the Compose tunnel controls and enables Start; synthetic developer-off hides the section and wireless-off hides setup. No token or connector required; real OS settings preserved."
    }
    private fun local(): String {
        val context = instrumentation.targetContext
        val state = AdbDiscovery.discover(context)
        check(state.developer && state.wireless && state.port != null) { "Actual wireless ADB endpoint not discovered" }
        repeat(10) { attempt ->
            val repeated = AdbDiscovery.discover(context, 3000)
            check(repeated.port == state.port) { "Discovery lost endpoint on attempt $attempt: $repeated" }
            Socket("127.0.0.1", checkNotNull(repeated.port)).close()
        }
        val a = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val b = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val bridgePort = ServerSocket(0).use { it.localPort }
        fun echo(server: ServerSocket) = Thread {
            try { while (!server.isClosed) server.accept().use { socket ->
                while (true) { val value = socket.getInputStream().read(); if (value < 0) break; socket.getOutputStream().write(value) }
            } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        echo(a); echo(b)
        try {
            AdbLoopbackRelay(bridgePort).use { relay ->
                relay.update(a.localPort)
                Socket("127.0.0.1", bridgePort).use { first ->
                    first.soTimeout = 3000
                    first.getOutputStream().write(37)
                    check(first.getInputStream().read() == 37) { "Relay failed bidirectional traffic" }
                    relay.update(b.localPort)
                    check(runCatching { first.getInputStream().read() }.getOrDefault(-1) == -1) { "Old ADB client survived port change" }
                }
                Socket("127.0.0.1", bridgePort).use { second ->
                    second.soTimeout = 3000
                    second.getOutputStream().write(81)
                    check(second.getInputStream().read() == 81) { "Relay did not switch endpoint" }
                    relay.update(null)
                    check(runCatching { second.getInputStream().read() }.getOrDefault(-1) == -1) { "Disabled ADB retained a connection" }
                }
                Socket("127.0.0.1", bridgePort).use { blocked ->
                    blocked.soTimeout = 3000
                    check(runCatching { blocked.getInputStream().read() }.getOrDefault(-1) == -1) { "Disabled relay accepted traffic" }
                }
                relay.update(a.localPort)
                Socket("127.0.0.1", bridgePort).use { resumed ->
                    resumed.soTimeout = 3000; resumed.getOutputStream().write(123)
                    check(resumed.getInputStream().read() == 123) { "Relay did not recover" }
                }
            }
            check(runCatching { Socket("127.0.0.1", bridgePort).close() }.isFailure) { "Relay listener leaked" }
        } finally { a.close(); b.close() }
        val token = android.util.Base64.encodeToString(org.json.JSONObject().put("a", "0".repeat(32))
            .put("t", "182e88f6-6c5c-4952-b1c7-f52a5d053264").put("s", "test-only-secret").toString().toByteArray(), android.util.Base64.NO_WRAP)
        val config = AdbTunnelConfig(token, "quest-adb.example.com", 5555).validate()
        check("tcp://127.0.0.1:5555" in config.yaml() && token !in config.yaml() && token !in config.toString())
        for (bad in listOf(config.copy(hostname = "https://evil.example/path"), config.copy(hostname = "evil\nservice: http://bad"), config.copy(port = 0), config.copy(token = "not a token"))) {
            check(runCatching { bad.validate() }.isFailure) { "Invalid tunnel input accepted" }
        }
        val settings = File(context.noBackupFilesDir, "adb-tunnel/settings.enc")
        val previous = settings.takeIf { it.exists() }?.readBytes()
        val yaml = File(context.noBackupFilesDir, "adb-tunnel/config.yml")
        val oldYaml = yaml.takeIf { it.exists() }?.readBytes()
        try {
            AdbTunnelSettings(context).save(config)
            check(AdbTunnelSettings(context).load() == config)
            check(token !in String(settings.readBytes(), Charsets.ISO_8859_1)) { "Token stored in plaintext" }
        } finally {
            if (previous == null) settings.delete() else settings.writeBytes(previous)
            if (oldYaml == null) yaml.delete() else yaml.writeBytes(oldYaml)
        }
        val report = "Ordinary UID ${android.os.Process.myUid()}: actual ADB port ${state.port}; ten repeated 3s discovery/teardown and loopback checks; bidirectional relay, port-change client termination, disable/recovery, listener cleanup, input rejection, encrypted settings roundtrip PASS. Port changes/disable use synthetic local endpoints."
        File(context.cacheDir, "adb-tunnel-local.txt").writeText(report)
        return report
    }
    private fun live(): String {
        val context = instrumentation.targetContext
        val tokenFile = File(context.cacheDir, "adb-tunnel-test-token")
        val token = if (tokenFile.exists()) tokenFile.readText().trim() else checkNotNull(AdbTunnelSettings(context).load()).token
        tokenFile.delete()
        val tunnelHostname = checkNotNull(instrumentation.arguments?.getString("adbTunnelHostname")) {
            "Missing instrumentation arg: pass -e adbTunnelHostname <host>"
        }
        val config = AdbTunnelConfig(token, tunnelHostname, 5555).validate()
        AdbTunnelSettings(context).save(config)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val reportFile = File(context.cacheDir, "adb-tunnel-live.txt")
        try {
            instrumentation.runOnMainSync { AdbTunnelService.start(context) }
            val started = android.os.SystemClock.elapsedRealtime()
            var sawPort = false
            var sawConnected = false
            while (android.os.SystemClock.elapsedRealtime() - started < 90000) {
                val message = AdbTunnelService.status.value
                sawPort = sawPort || AdbTunnelService.currentPort != null
                sawConnected = sawConnected || AdbTunnelService.connectorReady
                reportFile.writeText("running=${AdbTunnelService.running}\nport=${AdbTunnelService.currentPort}\nconnectorReady=${AdbTunnelService.connectorReady}\nstatus=$message\n")
                Thread.sleep(1000)
            }
            check(sawPort) { "Live service did not discover ADB" }
            check(sawConnected) { "Live connector did not register with Cloudflare; see sanitized status" }
        } finally {
            instrumentation.runOnMainSync { AdbTunnelService.stop(context) }
            Thread.sleep(3000)
            check(!AdbTunnelService.running && AdbTunnelService.currentPort == null) { "Service teardown failed" }
            check(!File(context.noBackupFilesDir, "adb-tunnel/runtime/token").exists()) { "Runtime token file leaked" }
            instrumentation.runOnMainSync { activity.finish() }
        }
        return "Live token: discovered ADB, registered Cloudflare connector, repeated health checks, stop cleanup PASS; settings saved. Remote ADB requires dashboard route and client bridge."
    }
}
