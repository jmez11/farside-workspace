package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.ProxyType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProxyVerification(private val test: Instrumentation) {
    fun runLiveRdp(socks5: Boolean = false): String {
        val profile = ProfileManager(test.targetContext).getProfiles().first {
            it.type == ConnectionType.RDP && it.name.startsWith("LAPTOP-")
        }
        val relay: AutoCloseable = if (socks5) Socks5Fixture(profile.port, profile.host)
            else HttpConnectFixture(profile.port, profile.host)
        relay.use { proxy ->
            val proxyPort = if (proxy is Socks5Fixture) proxy.port else (proxy as HttpConnectFixture).port
            val throughProxy = profile.copy(proxyType = if (socks5) ProxyType.SOCKS5 else ProxyType.HTTP,
                proxyHost = "127.0.0.1", proxyPort = proxyPort,
                proxyUser = if (socks5) "proxy-fixture" else null,
                proxyPass = if (socks5) "secret" else null)
            val bridge = FreeRDPBridge()
            val verifier = RdpCertificateVerifier(test.targetContext, profile.host, profile.port, {})
            val done = CountDownLatch(1)
            var instance = -1L
            var reason = ""
            val connection = RdpSessionConnection(test.targetContext, throughProxy, bridge, verifier,
                onDisconnected = { message, _ -> reason = message }, onConnected = { instance = it; done.countDown() })
            val texture = android.graphics.SurfaceTexture(false).apply { setDefaultBufferSize(1280, 800) }
            try {
                connection.attach(android.view.Surface(texture))
                check(done.await(30, TimeUnit.SECONDS)) { "RDP proxy connection timed out: $reason" }
                check(instance != 0L) { "RDP proxy connection failed: $reason" }
                if (proxy is Socks5Fixture) check(proxy.authenticated && proxy.destination == "${profile.host}:${profile.port}")
                else check((proxy as HttpConnectFixture).request?.startsWith("CONNECT ${profile.host}:${profile.port} HTTP/1.1\r\n") == true)
                check(bridge.getDesktopWidth(instance) > 0 && bridge.getDesktopHeight(instance) > 0)
                return "PASS: live RDP desktop connected through ${if (socks5) "SOCKS5" else "HTTP CONNECT"}."
            } finally { connection.close(); texture.release() }
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        val displays = test.uiAutomation.windowsOnAllDisplays
        return (0 until displays.size()).flatMap { index -> displays.valueAt(index).flatMap { it.root?.let(::visit).orEmpty() } }
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(30) {
            nodes().firstOrNull { it.text?.toString() == label }?.let { return it }
            Thread.sleep(200)
        }
        error("Missing proxy control: $label")
    }
    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(300)
    }
    fun run() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            Socks5Fixture(server.localPort).use { proxy ->
                val received = CountDownLatch(1)
                val peer = kotlin.concurrent.thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        check(input.read() == 42)
                        output.write(43)
                        received.countDown()
                    }
                }
                com.farside.workspace.ssh.Socks5SocketFactory("127.0.0.1", proxy.port,
                    "proxy-fixture", "secret").createSocket("remote.example", server.localPort).use { socket ->
                    socket.getOutputStream().write(42)
                    check(socket.getInputStream().read() == 43)
                }
                check(received.await(5, TimeUnit.SECONDS))
                check(proxy.authenticated && proxy.destination == "remote.example:${server.localPort}")
                peer.join(1000)
            }
        }
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            test.runOnMainSync {
                activity.setContent {
                    var selected by remember { mutableStateOf(ProxyType.NONE) }
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        com.farside.workspace.ui.ProxyTypeSelector(selected) { selected = it }
                    }
                }
            }
            var selected = "None"
            for (choice in listOf("Cloudflared", "SOCKS5", "HTTP", "None")) {
                click(selected)
                for (label in listOf("None", "Cloudflared", "SOCKS5", "HTTP")) find(label)
                check(nodes().none { it.text?.toString() in listOf("SOCKS4", "LOCAL", "TELNET") })
                click(choice)
                find(choice)
                selected = choice
            }
            for (legacy in listOf(ProxyType.SOCKS4, ProxyType.LOCAL, ProxyType.TELNET)) {
                val profile = ConnectionProfile("proxy-test", "Proxy test", ConnectionType.RDP, "127.0.0.1", 3389,
                    "fixture-user", "fixture-pass", proxyType = legacy)
                check(ConnectionProfile.fromJson(profile.toJson()).proxyType == legacy)
                test.runOnMainSync {
                    activity.setContent { MaterialTheme { com.farside.workspace.ui.ProxyTypeSelector(legacy) {} } }
                }
                find("Unsupported saved proxy")
            }
            // A real loopback listener catches any accidental attempt to connect directly.
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 300
                for (type in listOf(ProxyType.SOCKS4, ProxyType.LOCAL, ProxyType.TELNET)) {
                    val profile = ConnectionProfile("proxy-test", "Proxy test", ConnectionType.RDP, "127.0.0.1", listener.localPort,
                        "fixture-user", "fixture-pass", proxyType = type)
                    val result = CountDownLatch(1)
                    var reason = ""
                    var instance = -1L
                    val verifier = RdpCertificateVerifier(test.targetContext, profile.host, profile.port, {})
                    val connection = RdpSessionConnection(test.targetContext, profile, FreeRDPBridge(), verifier,
                        onDisconnected = { message, established -> check(!established); reason = message },
                        onConnected = { instance = it; result.countDown() })
                    val texture = android.graphics.SurfaceTexture(false)
                    try {
                        connection.attach(android.view.Surface(texture))
                        check(result.await(5, TimeUnit.SECONDS))
                        check(instance == 0L && reason.contains("not supported yet"))
                        check(runCatching { listener.accept().use { } }.exceptionOrNull() is java.net.SocketTimeoutException)
                    } finally { connection.close(); texture.release() }
                }
                Socks5Fixture().use { proxy ->
                    val profile = ConnectionProfile("proxy-socks-test", "SOCKS5 proxy test", ConnectionType.RDP,
                        "127.0.0.1", listener.localPort, "fixture-user", "fixture-pass", proxyType = ProxyType.SOCKS5,
                        proxyHost = "127.0.0.1", proxyPort = proxy.port,
                        proxyUser = "proxy-fixture", proxyPass = "secret")
                    val result = CountDownLatch(1)
                    var instance = -1L
                    val connection = RdpSessionConnection(test.targetContext, profile, FreeRDPBridge(),
                        RdpCertificateVerifier(test.targetContext, profile.host, profile.port, {}),
                        onDisconnected = { _, _ -> }, onConnected = { instance = it; result.countDown() })
                    val texture = android.graphics.SurfaceTexture(false)
                    try {
                        connection.attach(android.view.Surface(texture))
                        check(result.await(20, TimeUnit.SECONDS))
                        check(instance == 0L && proxy.authenticated) {
                            "SOCKS5 RDP result=$instance authenticated=${proxy.authenticated} destination=${proxy.destination} fixtureFailure=${proxy.failure}"
                        }
                        check(proxy.destination == "127.0.0.1:${listener.localPort}")
                        check(runCatching { listener.accept().use { } }.exceptionOrNull() is java.net.SocketTimeoutException)
                    } finally { connection.close(); texture.release() }
                }
                HttpConnectFixture().use { proxy ->
                    val profile = ConnectionProfile("proxy-http-test", "HTTP proxy test", ConnectionType.RDP,
                        "127.0.0.1", listener.localPort, "fixture-user", "fixture-pass", proxyType = ProxyType.HTTP,
                        proxyHost = "127.0.0.1", proxyPort = proxy.port,
                        proxyUser = "proxy-fixture", proxyPass = "secret")
                    val result = CountDownLatch(1)
                    var instance = -1L
                    val verifier = RdpCertificateVerifier(test.targetContext, profile.host, profile.port, {})
                    val connection = RdpSessionConnection(test.targetContext, profile, FreeRDPBridge(), verifier,
                        onDisconnected = { _, _ -> }, onConnected = { instance = it; result.countDown() })
                    val texture = android.graphics.SurfaceTexture(false)
                    try {
                        connection.attach(android.view.Surface(texture))
                        check(result.await(20, TimeUnit.SECONDS))
                        check(instance == 0L)
                        check(proxy.request?.startsWith("CONNECT 127.0.0.1:${listener.localPort} HTTP/1.1\r\n") == true) {
                            "RDP did not use HTTP CONNECT: ${proxy.request?.lineSequence()?.firstOrNull()}"
                        }
                        check(proxy.request?.contains("Proxy-Authorization: Basic cHJveHktZml4dHVyZTpzZWNyZXQ=\r\n") == true) {
                            "RDP proxy authorization missing: ${proxy.request}"
                        }
                        check(runCatching { listener.accept().use { } }.exceptionOrNull() is java.net.SocketTimeoutException)
                    } finally { connection.close(); texture.release() }
                }
            }
        } finally { test.runOnMainSync { activity.finish() } }
    }
}
