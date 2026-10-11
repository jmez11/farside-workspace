package com.farside.workspace

import android.app.Instrumentation
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Base64
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProxyType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.tunnel.CloudflaredOptions
import com.farside.workspace.tunnel.CloudflaredTunnel
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Real packaged Go/NDK child processes and local TLS Access fixtures; no production credentials. */
class CloudflaredVerification(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    fun runLive(host: String, credentialName: String? = null) {
        val manager = ProfileManager(context)
        val profileId = "cloudflared-live-verification"
        manager.deleteProfile(profileId) // Recover only this harness's abandoned temporary profile.
        val saved = manager.getProfiles().first { it.type == ConnectionType.RDP }
        val credential = credentialName?.let { name -> manager.getCredentials().single { it.name.equals(name, true) } }
        val profile = saved.copy(id = profileId, name = "Cloudflared live verification", host = CloudflaredOptions.hostname(host),
            user = credential?.user ?: saved.user, pass = credential?.pass ?: saved.pass,
            proxyType = ProxyType.CLOUDFLARED, useCloudflare = false, cloudflaredParameters = "", redirectMicrophone = false)
        manager.addProfile(profile)
        var activity: SessionActivity? = null
        fun texture(view: View): TextureView? {
            if (view is TextureView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            repeat(2) { pass ->
                val started = System.nanoTime()
                val current = instrumentation.startActivitySync(Intent(context, SessionActivity::class.java)
                    .putExtra("profileId", profileId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
                activity = current
                var frame: Bitmap? = null
                val serviceInfo = instrumentation.uiAutomation.serviceInfo
                serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                instrumentation.uiAutomation.serviceInfo = serviceInfo
                val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(if (pass == 0) 10 else 2)
                while (frame == null && System.nanoTime() < deadline) {
                    instrumentation.runOnMainSync {
                        val candidate = texture(current.window.decorView)?.bitmap
                        if (candidate != null) {
                            val colors = mutableSetOf<Int>()
                            for (y in 0 until candidate.height step 17) for (x in 0 until candidate.width step 17) colors.add(candidate.getPixel(x, y))
                            if (colors.size > 16) frame = candidate else candidate.recycle()
                        }
                    }
                    instrumentation.uiAutomation.clearCache()
                    fun containsFailure(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                        if (node.text?.toString() == "Connection failed") return true
                        for (i in 0 until node.childCount) node.getChild(i)?.let { if (containsFailure(it)) return true }
                        return false
                    }
                    val windows = instrumentation.uiAutomation.windowsOnAllDisplays.get(current.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY).orEmpty()
                    for (window in windows)
                        window.root?.let { check(!containsFailure(it)) { "Live tunnel displayed Connection failed; inspect Cloudflared log." } }
                    if (frame == null) Thread.sleep(250)
                }
                checkNotNull(frame) { "No live tunneled RDP frame; browser sign-in/certificate approval or server reachability remains unverified." }
                // Windows can paint a nonblank Welcome screen before the user's shell is ready.
                Thread.sleep(6000)
                instrumentation.runOnMainSync {
                    texture(current.window.decorView)?.bitmap?.let { refreshed -> frame?.recycle(); frame = refreshed }
                }
                val bitmap = checkNotNull(frame)
                File(context.cacheDir, "cloudflared-live-$pass.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val home = CloudflaredTunnel(context).home
                val tokens = File(home, ".cloudflared").listFiles().orEmpty().filter { it.name.endsWith("-token") }
                check(tokens.isNotEmpty()) { "Browser login did not persist an Access token" }
                check(tokens.all { Os.stat(it.absolutePath).st_mode and 511 == 384 }) { "Token permissions are not 0600" }
                Log.i("CloudflaredTest", "Live pass $pass: nonblank RDP frame, private 0600 Access tokens, elapsed ${(System.nanoTime() - started) / 1_000_000_000}s")
                instrumentation.runOnMainSync { current.finish() }
                Thread.sleep(1500)
                activity = null
            }
            Log.i("CloudflaredTest", "PASS: live browser authentication, private token cache, tunneled RDP frame and cached reconnect. User completed any browser/certificate prompts; no automated Horizon gestures.")
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
            val retained = manager.getProfiles().filter { it.id != profileId }
            check(context.getSharedPreferences("farside_profiles", android.content.Context.MODE_PRIVATE).edit()
                .putString("profiles", org.json.JSONArray(retained.map { it.toJson() }).toString()).commit())
            context.getSharedPreferences("session_window_positions", android.content.Context.MODE_PRIVATE).edit()
                .remove("$profileId/0").commit()
        }
    }

    fun run() {
        check(CloudflaredOptions.hostname("RDP.Example.COM") == "rdp.example.com")
        for (host in listOf("--help", "https://rdp.example.com", "host:443", "host/path", "a..b", "a;id", "a\n.b"))
            check(runCatching { CloudflaredOptions.hostname(host) }.isFailure)
        val params = "--id=fixture-id --secret 'fixture-secret'"
        check(CloudflaredOptions.parse(params) == listOf("--service-token-id", "fixture-id", "--service-token-secret", "fixture-secret"))
        check(CloudflaredOptions.parse("-H \"Cf-Access-Token: fixture-jwt\"").last() == "Cf-Access-Token: fixture-jwt")
        for (bad in listOf("--url 0.0.0.0:3389", "--hostname other", "--connect-to other", "--logfile /sdcard/token", "--loglevel debug", "--debug-stream 100", "--no-tls-verify", "--service-token-id only", "--secret value", "--id a --id b --secret c", "-H 'Host: other'", "-H 'Authorization: secret'", "--header 'Cf-Access-Client-Id: only'", "--id a --secret c;id", "$(id)", "`id`", "a | b", "--header 'Cf-Access-Token: a\r\nb'", "--header 'unclosed", "--", "access rdp", "--id '' --secret b", "--header 'Cf-Access-Token: a' --header 'cf-access-token: b'"))
            check(runCatching { CloudflaredOptions.parse(bad) }.isFailure) { "Unsafe parameters accepted" }
        val profile = ConnectionProfile("cloudflared-fixture", "fixture", ConnectionType.RDP, "rdp.example.com", 3389, "fixture", "", proxyType = ProxyType.CLOUDFLARED, cloudflaredParameters = params)
        val json = profile.toJson()
        check(!json.toString().contains("fixture-secret"))
        check(ConnectionProfile.fromJson(json) == profile)
        check(ConnectionProfile.fromJson(profile.copy(cloudflaredParameters = "").toJson()).cloudflaredParameters.isEmpty())
        val valid = "https://rdp.example.com/cdn-cgi/access/cli?token=fixture-key&aud=fixture-aud&edge_token_transfer=true"
        check(CloudflaredTunnel.authenticationUri(valid, profile.host) != null)
        for (bad in listOf(valid.replace("https:", "http:"), valid.replace("rdp.example.com", "other.example.com"), valid.replace("rdp.example.com", "user@rdp.example.com"), valid.replace("rdp.example.com", "rdp.example.com:443"), valid + "#fragment", "javascript:alert(1)", "intent://browser", "log $valid"))
            check(CloudflaredTunnel.authenticationUri(bad, profile.host) == null)
        check(context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(valid)).addCategory(Intent.CATEGORY_BROWSABLE), 0) != null) { "No HTTPS browser available" }
        check(binary.canExecute())
        val cancelled = CloudflaredTunnel(context)
        val cancelResult = runCatching { cancelled.start("localhost", "", { cancelled.close() }, {}) }
        check(cancelResult.exceptionOrNull()?.message == "Cloudflared connection cancelled.") { "Cancellation did not stop authentication startup" }
        check(Os.stat(cancelled.home.absolutePath).st_mode and 511 == 448) { "Cloudflared HOME permissions are not 0700" }
        val work = File(context.cacheDir, "cloudflared-verification").apply { mkdirs() }
        try {
            val version = invoke(work, listOf("--version"))
            check(version.first == 0 && version.second.contains("2026.5.1-farside")) { "Wrong bundled executable" }
            val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            for (command in listOf("rdp", "tcp")) {
            val listener = launch(work, listOf("access", command, "--hostname", "rdp.example.com", "--url", "127.0.0.1:$port"))
            try {
                val output = AtomicReference("")
                val reader = Thread { runCatching { listener.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (it.startsWith("FARSIDE_LISTENER_READY")) output.set(it) } } } }.apply { start() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (output.get().isEmpty() && listener.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
                check(output.get() == "FARSIDE_LISTENER_READY 127.0.0.1:$port") { "$command listener not ready" }
                listener.destroy()
                check(listener.waitFor(3, TimeUnit.SECONDS)) { "Child did not stop" }
                reader.join(1000)
            } finally { listener.destroyForcibly() }
            }
            verifyAuthentication(work)
        } finally { work.deleteRecursively() }
        Log.i("CloudflaredTest", "PASS: argument/hostname allowlist, encrypted profile roundtrip, browser URI validation/resolution, private HOME and cancellation, packaged Android binary, real loopback listener and cleanup, local TLS credential preflight, cached JWT, rejected credentials fall back to browser transfer URL. Fixtures do not establish live Cloudflare sign-in or remote RDP.")
    }

    private fun launch(work: File, args: List<String>, trusted: Boolean = true): Process {
        val builder = ProcessBuilder(listOf(binary.absolutePath) + args).directory(work).redirectErrorStream(true)
        builder.environment()["HOME"] = work.absolutePath
        if (trusted) builder.environment()["SSL_CERT_FILE"] = File(work, "fixture-ca.pem").absolutePath
        builder.environment()["SSL_CERT_DIR"] = File(work, "empty-ca-dir").apply { mkdirs() }.absolutePath
        return builder.start()
    }

    private fun invoke(work: File, args: List<String>, trusted: Boolean = true): Pair<Int, String> {
        val process = launch(work, args, trusted)
        val output = AtomicReference("")
        val reader = Thread { runCatching { output.set(process.inputStream.bufferedReader().readText()) } }.apply { start() }
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Fixture child timed out" }
            reader.join(1000)
            return process.exitValue() to output.get()
        } finally { process.destroyForcibly() }
    }

    private fun verifyAuthentication(work: File) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("cloudflared/localhost.p12").use { load(it, "fixture-password".toCharArray()) }
        }
        instrumentation.context.assets.open("cloudflared/localhost.pem").use { input -> File(work, "fixture-ca.pem").outputStream().use { input.copyTo(it) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "fixture-password".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val server = ssl.serverSocketFactory.createServerSocket(0, 0, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val stopped = AtomicBoolean(false)
        val omitDomain = AtomicBoolean(false)
        val token = "${b64("{\"alg\":\"RS256\"}")}.${b64("{\"aud\":[\"fixture-aud\"],\"exp\":4102444800}")}.${Base64.encodeToString(ByteArray(256) { 1 }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}"
        val worker = Thread {
            while (!stopped.get()) try {
                val socket = server.accept() as SSLSocket
                Thread {
                    try { socket.use {
                        it.soTimeout = 5000
                        val reader = it.inputStream.bufferedReader()
                        val request = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val authorized = headers["cf-access-client-id"] == "fixture-id" && headers["cf-access-client-secret"] == "fixture-secret" || headers["cf-access-token"] == token
                        val loginPage = request.contains("/cdn-cgi/access/login")
                        val response = if (authorized || loginPage) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 302 Found\r\nLocation: https://localhost:${server.localPort}/cdn-cgi/access/login?kid=fixture-aud\r\n"
                        it.outputStream.write((response + (if (omitDomain.get()) "" else "CF-Access-Domain: localhost\r\n") + "CF-Access-Aud: fixture-aud\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    } } catch (_: Exception) { /* Expected TLS rejection/cancellation. */ }
                }.start()
            } catch (_: Exception) { if (!stopped.get()) throw IllegalStateException("Fixture server failed") }
        }.apply { start() }
        val url = "https://localhost:${server.localPort}"
        try {
            val login = listOf("access", "login", "--quiet")
            val creds = listOf("--service-token-id", "fixture-id", "--service-token-secret", "fixture-secret")
            check(invoke(work, login + creds + url, false).first != 0) { "Untrusted TLS certificate accepted" }
            val valid = invoke(work, login + creds + url)
            check(valid.first == 0 && valid.second.contains("FARSIDE_AUTH_CREDENTIALS")) { "Valid service token preflight failed" }
            val headers = invoke(work, login + listOf("--header", "Cf-Access-Client-Id: fixture-id", "--header", "Cf-Access-Client-Secret: fixture-secret") + url)
            check(headers.first == 0 && headers.second.contains("FARSIDE_AUTH_CREDENTIALS"))
            val cache = File(work, ".cloudflared").apply { mkdirs() }
            val tokenFile = File(cache, "localhost-fixture-aud-token").apply { writeText(token); Os.chmod(absolutePath, 384) }
            val cached = invoke(work, login + url)
            check(cached.first == 0 && cached.second.contains("FARSIDE_AUTH_USER") && !cached.second.contains(token)) { "Cached user JWT was not reused quietly" }
            omitDomain.set(true)
            val missingDomain = invoke(work, login + url)
            check(missingDomain.first == 0 && missingDomain.second.contains("FARSIDE_AUTH_USER")) { "Missing Access domain header did not use original application hostname" }
            omitDomain.set(false)
            val fallback = invoke(work, login + listOf("--service-token-id", "wrong", "--service-token-secret", "wrong") + url)
            check(fallback.first == 0 && fallback.second.contains("FARSIDE_AUTH_USER")) { "Invalid service token did not fall back to cached JWT" }
            tokenFile.delete()
            val interactive = launch(work, login + url)
            val sawUrl = AtomicBoolean(false)
            val reader = Thread { runCatching { interactive.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (it.startsWith("https://localhost:") && it.contains("/cdn-cgi/access/cli?") && it.contains("edge_token_transfer=true")) sawUrl.set(true) } } } }.apply { start() }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!sawUrl.get() && interactive.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
                check(sawUrl.get()) { "Missing-token browser transfer URL not produced" }
                interactive.destroy()
                check(interactive.waitFor(3, TimeUnit.SECONDS))
                reader.join(1000)
            } finally { interactive.destroyForcibly() }
        } finally { stopped.set(true); server.close(); worker.join(1000) }
    }

    private fun b64(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
