package com.farside.workspace.tunnel

import android.content.Context
import android.system.Os
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Token stays in the child environment; output is consumed without logging secrets/config. */
class AdbTunnelProcess(context: Context, config: AdbTunnelConfig, private val status: (String) -> Unit) : AutoCloseable {
    private val root = File(context.noBackupFilesDir, "adb-tunnel/runtime").apply { mkdirs(); Os.chmod(absolutePath, 448) }
    private val metricsPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private val process: Process
    val connected: Boolean get() = runCatching {
        java.net.Socket().use { socket ->
            socket.soTimeout = 1000
            socket.connect(java.net.InetSocketAddress("127.0.0.1", metricsPort), 1000)
            socket.getOutputStream().write("GET /ready HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.US_ASCII))
            socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()?.split(' ')?.getOrNull(1) == "200"
        }
    }.getOrDefault(false)
    @Volatile var routeConfigured = false
        private set
    init {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val ca = File(root, "android-ca.pem").apply {
            writeText(trust.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }.joinToString("\n") {
                "-----BEGIN CERTIFICATE-----\n${Base64.encodeToString(it.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")}\n-----END CERTIFICATE-----"
            }); Os.chmod(absolutePath, 384)
        }
        val executable = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        process = try {
            ProcessBuilder(executable.absolutePath, "--no-autoupdate", "tunnel", "--protocol", "http2", "--metrics", "127.0.0.1:$metricsPort", "run")
                .directory(root).redirectErrorStream(true).apply {
                    environment()["HOME"] = root.absolutePath
                    environment()["SSL_CERT_FILE"] = ca.absolutePath
                    environment()["TUNNEL_TOKEN"] = config.token
                }.start()
        } catch (error: Exception) { throw error }
        Thread({
            // Bound each line: do not accumulate arbitrary connector output or print its settings.
            process.inputStream.bufferedReader().use { reader ->
                val line = StringBuilder()
                var overflow = false
                try {
                    while (true) {
                        val ch = reader.read()
                        if (ch == -1) break
                        if (ch == 10) {
                            if (!overflow) {
                                val text = line.toString()
                                if ("Registered tunnel connection" in text) {
                                    status(if (routeConfigured) "Cloudflare connected; ADB route configured" else "Cloudflare connected; check dashboard ADB route")
                                }
                                if ("Updated to new configuration" in text) {
                                    val jsonText = text.replace("\\\"", "\"")
                                    routeConfigured = runCatching {
                                        val ingress = org.json.JSONObject(jsonText.substring(jsonText.indexOf('{'))).getJSONArray("ingress")
                                        (0 until ingress.length()).any { index ->
                                            val rule = ingress.getJSONObject(index)
                                            rule.optString("hostname") == config.hostname && rule.optString("service") in listOf("tcp://127.0.0.1:${config.port}", "tcp://localhost:${config.port}")
                                        }
                                    }.getOrDefault(false)
                                    status(if (routeConfigured) "Cloudflare connected; ADB route configured" else "Set dashboard route: ${config.hostname} → tcp://127.0.0.1:${config.port}")
                                }
                                if ("Unauthorized" in text || "Invalid tunnel secret" in text) status("Cloudflare rejected the tunnel token")
                            }
                            line.clear(); overflow = false
                        } else if (line.length < 65536) line.append(ch.toChar()) else overflow = true
                    }
                } catch (_: Exception) { }
            }
        }, "adb-cloudflared-output").apply { isDaemon = true; start() }
    }
    fun alive() = process.isAlive
    override fun close() {
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
