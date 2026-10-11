package com.farside.workspace.tunnel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import org.json.JSONObject
import android.util.Base64
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One child per RDP window. No shell, shared service, arbitrary executable or raw child logging. */
class CloudflaredTunnel(private val context: Context) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val child = AtomicReference<Process?>()
    private val main = Handler(Looper.getMainLooper())
    private var lastAuthUrl: String? = null
    val home: File get() = File(context.noBackupFilesDir, "cloudflared")
    private val executable: File get() = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    fun start(hostInput: String, parameters: String, onStatus: (String) -> Unit, onExit: () -> Unit, listenPort: Int = 0, accessSubcommand: String = "rdp"): Int {
        val host = CloudflaredOptions.hostname(hostInput)
        val args = CloudflaredOptions.parse(parameters)
        check(executable.canExecute()) { "The bundled Cloudflared executable is unavailable." }
        check(home.mkdirs() || home.isDirectory) { "Could not prepare Cloudflared token storage." }
        Os.chmod(home.absolutePath, 448) // 0700, even if a directory existed already.
        // Export Android's current trust anchors, including the modern Conscrypt root store.
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val caFile = File.createTempFile("android-ca-", ".pem", home)
        caFile.writeText(trust.trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }.joinToString("\n") {
                "-----BEGIN CERTIFICATE-----\n${Base64.encodeToString(it.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")}\n-----END CERTIFICATE-----"
            })
        Os.chmod(caFile.absolutePath, 384)
        Os.rename(caFile.absolutePath, File(home, "android-ca.pem").absolutePath)
        // Authenticate before FreeRDP starts its protocol handshake timeout. The bundled login
        // patch accepts the same Access credentials and falls back to cloudflared's browser flow.
        onStatus("Authenticating with Cloudflare… Complete sign-in in the browser if it opens.")
        val login = launch(listOf("access", "login", "--quiet") + args + listOf("https://$host"))
        val browserFailure = AtomicBoolean(false)
        val userAuth = AtomicBoolean(false)
        val diagnostic = AtomicReference("")
        val noAccessMetadata = AtomicBoolean(false)
        val loginReader = drain(login, host, browserFailure) {
            if (it == "FARSIDE_AUTH_USER") userAuth.set(true)
            if ("failed to find Access application" in it) noAccessMetadata.set(true)
            val reason = when {
                "x509:" in it -> "Cloudflare TLS certificate validation failed."
                "no such host" in it -> "The Cloudflare hostname could not be resolved."
                "failed to find Access application" in it -> "This hostname did not return a Cloudflare Access application."
                "Empty app aud" in it -> "Cloudflare Access did not return an application audience."
                "Empty app domain" in it -> "Cloudflare Access did not return an application domain."
                "failed to get own" in it || "failed to acquire" in it -> "Cloudflared could not lock its private token cache."
                "connection refused" in it -> "The Cloudflare endpoint refused the connection."
                "timeout" in it || "deadline exceeded" in it -> "The Cloudflare endpoint did not respond in time."
                else -> null
            }
            reason?.let(diagnostic::set)
        }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        while (!login.waitFor(100, TimeUnit.MILLISECONDS)) {
            check(!closed.get()) { "Cloudflared connection cancelled." }
            check(!browserFailure.get()) { "Could not open the browser for Cloudflare authentication." }
            check(System.nanoTime() < deadline) { "Cloudflare authentication timed out. Try connecting again." }
        }
        loginReader.join(1000)
        check(!browserFailure.get()) { "Could not open the browser for Cloudflare authentication." }
        // Public Tunnel routes need no Access JWT. Let the normal, TLS-verified WebSocket
        // handshake determine this; it still handles any actual Access redirect itself.
        check(login.exitValue() == 0 || noAccessMetadata.get()) { diagnostic.get().ifBlank { "Cloudflare authentication failed (exit ${login.exitValue()}). Check the hostname, Access policy and credentials." } }
        child.compareAndSet(login, null)
        check(!closed.get()) { "Cloudflared connection cancelled." }
        require(listenPort in 0..65535) { "Invalid local Cloudflared port." }
        val port = ServerSocket(listenPort, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val ready = CountDownLatch(1)
        onStatus("Starting Cloudflared…")
        val process = launch(listOf("access", accessSubcommand, "--hostname", host, "--url", "127.0.0.1:$port") + if (userAuth.get()) emptyList() else args)
        drain(process, host, browserFailure) { line ->
            if (line == "FARSIDE_LISTENER_READY 127.0.0.1:$port") ready.countDown()
        }
        val startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!ready.await(100, TimeUnit.MILLISECONDS)) {
            check(!closed.get()) { "Cloudflared connection cancelled." }
            check(process.isAlive) { "Cloudflared exited before its local listener was ready." }
            check(System.nanoTime() < startupDeadline) { "Cloudflared listener startup timed out." }
        }
        Thread({
            process.waitFor()
            if (!closed.get()) onExit()
        }, "cloudflared-exit").start()
        onStatus("Connecting to the remote desktop…")
        return port
    }

    private fun launch(args: List<String>): Process {
        check(!closed.get()) { "Cloudflared connection cancelled." }
        val builder = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(home).redirectErrorStream(true)
        // Inherit Android's networking/runtime variables; remove cloudflared overrides.
        builder.environment().keys.removeAll { it.startsWith("TUNNEL_") || it.startsWith("CF_") || it in setOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy", "BROWSER") }
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        builder.environment()["SSL_CERT_FILE"] = File(home, "android-ca.pem").absolutePath
        builder.environment()["SSL_CERT_DIR"] = File(home, "empty-ca-dir").apply { mkdirs() }.absolutePath
        val process = builder.start()
        child.set(process)
        if (closed.get()) { process.destroyForcibly(); error("Cloudflared connection cancelled.") }
        return process
    }

    private fun drain(process: Process, host: String, browserFailure: AtomicBoolean, onLine: (String) -> Unit = {}): Thread =
        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    // Bound each line; never retain or log raw output, credentials, JWTs or URLs.
                    val line = StringBuilder()
                    var overflow = false
                    while (true) {
                        val value = reader.read()
                        if (value == -1) break
                        if (value == 10) {
                            if (!overflow) {
                                val text = line.toString().trim()
                                onLine(text)
                                authenticationUri(text, host)?.let { uri ->
                                    synchronized(this) {
                                        if (lastAuthUrl != text) {
                                            lastAuthUrl = text
                                            main.post {
                                                if (!closed.get()) try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri)
                                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                } catch (_: Exception) { browserFailure.set(true) }
                                            }
                                        }
                                    }
                                }
                            }
                            line.clear(); overflow = false
                        } else if (line.length < 16384) line.append(value.toChar()) else overflow = true
                    }
                }
            } catch (_: Exception) { /* Destroy closes the pipe. */ }
        }, "cloudflared-output").apply { start() }

    override fun close() {
        closed.set(true)
        child.getAndSet(null)?.let {
            it.destroy()
            Thread({ if (!it.waitFor(500, TimeUnit.MILLISECONDS)) it.destroyForcibly() }, "cloudflared-close").start()
        }
    }

    companion object {
        /** Clear Access app and organization JWTs, preserving trust anchors and other settings. */
        fun clearCachedLogins(context: Context) {
            val cache = File(context.noBackupFilesDir, "cloudflared/.cloudflared")
            if (!cache.exists()) return
            val files = checkNotNull(cache.listFiles()) { "Could not read Cloudflare login cache." }
            val locks = files.filter { it.isFile && it.name.endsWith("-token.lock") }
            check(locks.none { isAuthenticationLockActive(it) }) {
                "Cloudflare sign-in is in progress. Close Cloudflare sessions and try again."
            }
            for (file in files.filter { it.isFile && (it.name.endsWith("-token") ||
                    it.name.endsWith("-token.lock") || it.name.endsWith("-token.url")) }) {
                check(file.delete()) { "Could not clear all Cloudflare logins. Try again." }
            }
        }

        private fun isAuthenticationLockActive(lock: File): Boolean {
            // Cloudflared leaves lock/URL files behind after exiting. Match its ten-minute
            // authentication timeout, PID and process start time rather than file existence.
            if (System.currentTimeMillis() - lock.lastModified() > TimeUnit.MINUTES.toMillis(10)) return false
            return runCatching {
                val owner = JSONObject(lock.readText())
                val pid = owner.getInt("pid")
                check(pid > 0)
                try {
                    Os.kill(pid, 0)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.ESRCH) return false
                    throw error
                }
                // Go's boot-time calculation is rounded to seconds; tolerate that rounding.
                kotlin.math.abs(processStartMillis(pid) - owner.getLong("start_time")) <= 2000
            }.getOrDefault(true) // Unreadable/mid-write locks must not interrupt active sign-in.
        }

        internal fun processStartMillis(pid: Int): Long {
            val stat = File("/proc/$pid/stat").readText()
            val fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
            val ticks = fields[19].toLong() // /proc stat field 22 (starttime).
            val bootMillis = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000 * 1000
            return bootMillis + ticks * 1000 / Os.sysconf(OsConstants._SC_CLK_TCK)
        }

        fun authenticationUri(line: String, host: String): Uri? {
            if (line.length > 16384 || line.any { it.isWhitespace() }) return null
            val uri = Uri.parse(line)
            return uri.takeIf {
                it.scheme == "https" && it.host?.equals(host, true) == true &&
                    it.userInfo == null && it.port == -1 && it.fragment == null &&
                    it.path == "/cdn-cgi/access/cli" && it.getQueryParameter("edge_token_transfer") == "true" &&
                    !it.getQueryParameter("token").isNullOrBlank() && !it.getQueryParameter("aud").isNullOrBlank()
            }
        }
    }
}
