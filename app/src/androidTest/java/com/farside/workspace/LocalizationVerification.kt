package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import com.farside.workspace.data.*
import com.farside.workspace.ssh.JumpChains
import com.farside.workspace.ssh.SshConnectionManager
import com.farside.workspace.tunnel.*
import com.termux.view.TerminalView
import org.apache.sshd.server.SshServer
import java.io.File
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual resource decoding, typed failures and visible UI errors; no saved credentials are changed. */
class LocalizationVerification(private val test: Instrumentation) {
    private val context = test.targetContext
    private val evidence = File(context.cacheDir, "localization-results.txt")
    private fun record(text: String) { evidence.appendText("$text\n") }
    private fun same(actual: String?, expected: String) {
        check(actual == expected) { "English mismatch: ${actual?.toCharArray()?.map { it.code }} vs ${expected.toCharArray().map { it.code }}" }
    }
    private fun error(expected: String, block: () -> Unit) {
        val failure = checkNotNull(runCatching(block).exceptionOrNull()) { "Invalid input accepted" }
        check(failure is LocalizableError)
        same(failure.localizedMessage(context), expected)
    }
    private fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        val windows = test.uiAutomation.windowsOnAllDisplays
        return (0 until windows.size()).flatMap { i -> windows.valueAt(i).flatMap {
            it.root?.takeIf { root -> root.packageName == context.packageName }?.let(::visit).orEmpty()
        } }
    }
    private fun visible(label: String): android.view.accessibility.AccessibilityNodeInfo {
        repeat(60) {
            val available = nodes()
            available.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            if (label.startsWith("Unsupported Cloudflared") || label.startsWith("Source port must")) available.filter { it.isScrollable }.forEach {
                it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            Thread.sleep(200)
        }
        error("Missing English UI text: $label")
    }
    private fun click(label: String) {
        var node = visible(label)
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun capture(activity: android.app.Activity, name: String, dialog: Boolean = false) {
        var window = activity.window
        if (dialog) test.runOnMainSync {
            fun find(view: View): android.view.Window? = when (view) {
                is androidx.compose.ui.window.DialogWindowProvider -> view.window
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                else -> null
            }
            window = checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull(::find))
        }
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    fun run(): String {
        evidence.writeText("")
        val setupStrings = R.string::class.java.fields.filter { it.name.startsWith("setup_") }
        listOf("cs", "da", "de", "el", "es", "fi", "fr", "it", "ja", "ko", "nb", "nl", "pl", "pt-BR", "pt-PT", "ro", "ru", "sv", "tr", "zh-CN", "zh-HK", "zh-TW").forEach { tag ->
            val configuration = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(configuration)
            check(localized.getString(R.string.setup_new_connection) != context.getString(R.string.setup_new_connection)) { "Setup locale fell back: $tag" }
            setupStrings.forEach { field ->
                val id = field.getInt(null)
                val source = context.getString(id)
                val arguments = Regex("%(\\d+)\\$([ds])").findAll(source).associate {
                    it.groupValues[1].toInt() to if (it.groupValues[2] == "d") 42 else "fixture"
                }
                val formatted = if (arguments.isEmpty()) localized.getString(id) else {
                    localized.getString(id, *(1..arguments.keys.max()).map { arguments.getValue(it) }.toTypedArray<Any>())
                }
                check(formatted.isNotBlank() && !Regex("%\\d+\\$[ds]").containsMatchIn(formatted)) { "Setup format failed: $tag/${field.name}" }
                arguments.values.forEach { argument -> check(formatted.contains(argument.toString())) { "Setup argument lost: $tag/${field.name}" } }
            }
        }
        record("PASS compiled setup resources and positional formatting in 22 locales (${setupStrings.size} strings each)")
        val cleanupManager = ProfileManager(context)
        val staleFixtures = cleanupManager.getProfiles().filter {
            it.id.startsWith("l10n-") && it.name == "a" && it.type == ConnectionType.SSH && it.host == "127.0.0.1" && it.user == "user" && it.pass == "bad"
        }
        staleFixtures.forEach { cleanupManager.deleteProfile(it.id) }
        check(context.getSharedPreferences("farside_profiles", 0).edit().commit())
        record("Owned stale localization fixtures removed: ${staleFixtures.size}")
        same(context.getString(R.string.ssh_tunnel_process_exited), "\r\n[Cloudflared tunnel process exited]\r\n")
        same(context.getString(R.string.ssh_connected_host, "host"), "\r\n[SSH Connected to host]\r\n")
        same(context.getString(R.string.ssh_terminal_error, "reason"), "\r\n[SSH Error: reason]\r\n")
        same(context.getString(R.string.ssh_login_prompt), "login as: ")
        same(context.getString(R.string.ssh_password_prompt, "user", "host"), "user@host's password: ")
        same(context.getString(R.string.ssh_allowed_methods, "[publickey]"), " (allowed methods: [publickey])")
        same(context.getString(R.string.ssh_key_offering, "key", "user"), "[Offering SSH key 'key' for user 'user'...]\r\n")
        same(context.getString(R.string.ssh_chain_failure, "a ✓ → b ✗", "b", "auth failed"), "[Chain: a ✓ → b ✗ (b: auth failed)]\r\n")
        same(context.getString(R.string.ssh_hop_prefix, "hop"), "[hop: ")
        check(ProxyType.entries.map { context.getString(it.labelResId) } == listOf("None", "SOCKS4", "SOCKS5", "HTTP", "LOCAL", "TELNET", "Cloudflared"))
        check(CameraResolution.entries.map { context.getString(it.labelResId) } == listOf("SD (480p)", "720p", "Highest native resolution"))
        check(ForwardKind.entries.map { context.getString(it.labelResId) } == listOf("Local", "Remote", "Dynamic SOCKS"))
        check(ForwardFamily.entries.map { context.getString(it.labelResId) } == listOf("Auto", "IPv4", "IPv6"))
        record("PASS: Android resource CR/LF, quotes, prompt/suffix whitespace, positional formatting and chain glyphs")
        error("Use a port between 1024 and 65535.") { AdbTunnelConfig("", "host", 1).validate() }
        error("Enter only the tunnel token, without the command.") { AdbTunnelConfig("short", "host", 5555).validate() }
        error("Invalid Cloudflare tunnel token.") { AdbTunnelConfig("A".repeat(40), "host", 5555).validate() }
        error("Cloudflared needs a DNS hostname without a URL, port or path.") { CloudflaredOptions.hostname("https://host/path") }
        error("Unsupported Cloudflared option. Allowed: --service-token-id, --service-token-secret, --header (-H).") { CloudflaredOptions.parse("--bad x") }
        error("Close the quote in Cloudflared parameters.") { CloudflaredOptions.parse("-H 'unclosed") }
        error("Each Cloudflared option needs a value.") { CloudflaredOptions.parse("--id") }
        error("Supply both the service token ID and secret.") { CloudflaredOptions.parse("--id id") }
        error("Source port must be 1–65535") { SshForward(sourcePort = 0).validate() }
        error("Destination port must be 1–65535") { SshForward(destinationPort = 0).validate() }
        error("Enter a destination host without spaces") { SshForward(destinationHost = "bad host").validate() }
        error("Enabled tunnels on the same side need different source ports") { SshForward.validateList(listOf(SshForward(), SshForward())) }
        val dynamic = SshForward(kind = ForwardKind.DYNAMIC)
        same(context.getString(dynamic.routeResId, *dynamic.routeFormatArgs), "Quest :8080 → SOCKS5 proxy")
        val a = ConnectionProfile("a", "a", ConnectionType.SSH, "host", user = "user", pass = "")
        val cycle = a.copy(jumpHostProfileId = "a")
        error("Jump host cycle: a appears twice in the chain.") { JumpChains.resolveChain(cycle, listOf(cycle)) }
        val cycleError = checkNotNull(runCatching { JumpChains.resolveChain(cycle, listOf(cycle)) }.exceptionOrNull())
        same(com.farside.workspace.sftp.sftpError(context, cycleError), context.getString(R.string.sftp_operation_failed))
        val parserError = checkNotNull(runCatching { CloudflaredOptions.parse("--bad x") }.exceptionOrNull())
        same(com.farside.workspace.sftp.sftpError(context, parserError), "Unsupported Cloudflared option. Allowed: --service-token-id, --service-token-secret, --header (-H).")
        val chain = (0..3).map { i -> a.copy(id = "$i", name = "$i", jumpHostProfileId = if (i == 3) null else "${i + 1}") }
        error("Jump chain is longer than 3 hops (3 → 2 → 1 → 0).") { JumpChains.resolveChain(chain[0], chain) }
        same(JumpChains.preview(context, a.copy(jumpHostProfileId = "missing"), listOf(a)).second, "Jump host no longer exists; connecting directly.")
        val texture = android.graphics.SurfaceTexture(false)
        val surface = android.view.Surface(texture)
        try { error("VNC password must contain 1 to 8 Latin-1 characters.") { VncBridge().connect("host", 5900, "", surface) } }
        finally { surface.release(); texture.release() }
        record("PASS: ADB/VNC/Cloudflared/forwarding/jump invalid inputs resolve original English; route/plural formatting")
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val pm = ProfileManager(context)
        try {
            test.runOnMainSync { activity.setContent { key("cloudflared-error") { MaterialTheme {
                com.farside.workspace.ui.setup.ConnectionSetupFlow(a.copy(proxyType = ProxyType.CLOUDFLARED, cloudflaredParameters = "--bad x"),
                    onSave = { error("Invalid Cloudflared form saved") }, onCancel = {}, profileManager = pm)
            } } } }
            click("4 " + context.getString(R.string.setup_review))
            click(context.getString(R.string.save_changes))
            visible("Unsupported Cloudflared option. Allowed: --service-token-id, --service-token-secret, --header (-H).")
            capture(activity, "localization-cloudflared.png")
            test.runOnMainSync { activity.setContent { key("forward-error") { MaterialTheme {
                com.farside.workspace.ui.SshForwardingDialog(listOf(SshForward(sourcePort = 0)), onChange = { error("Invalid forward saved") }, onDismiss = {})
            } } } }
            click("Edit tunnel"); click("Save tunnel")
            visible("Source port must be 1–65535")
            capture(activity, "localization-forward.png", dialog = true)
            record("PASS: actual Cloudflared profile form and forwarding editor show English validation errors with synthetic accessibility clicks")
        } finally { test.runOnMainSync { activity.finish() } }
        SshConnectionManager.ensureBouncyCastleProvider()
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { context.cacheDir.toPath() }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = org.apache.sshd.common.keyprovider.KeyPairProvider { listOf(pair) }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, _, _ -> false }
        }
        server.start()
        val hostKeys=com.farside.workspace.ssh.SshHostKeyStore(context)
        check(hostKeys.save("127.0.0.1",server.port,com.farside.workspace.ssh.SshHostKey.from(pair.public)))
        val profile = a.copy(id = "l10n-${java.util.UUID.randomUUID()}", host = "127.0.0.1", port = server.port, pass = "bad")
        pm.addProfile(profile)
        var session: SessionActivity? = null
        try {
            SshConnectionManager.acquire(context, "${profile.id}-headless").use { lease ->
                val failure = checkNotNull(runCatching { lease.connect(profile) }.exceptionOrNull())
                check(failure is net.schmizz.sshj.userauth.UserAuthException)
                same(failure.message, "Check the saved credential or SSH key.")
            }
            session = test.startActivitySync(Intent(context, SessionActivity::class.java).putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
            fun terminal(view: View): TerminalView? = if (view is TerminalView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { terminal(group.getChildAt(it)) }
            }
            var view: TerminalView? = null
            repeat(50) {
                test.runOnMainSync { view = terminal(session!!.window.decorView) }
                if (view?.mEmulator != null) return@repeat
                Thread.sleep(100)
            }
            val term = checkNotNull(view)
            check((term.currentSession as com.termux.terminal.SshTerminalSession).context === context.applicationContext)
            test.runOnMainSync { term.currentSession.write("bad\rbad\rbad\r") }
            var transcript = ""
            repeat(100) {
                test.runOnMainSync { transcript = term.mEmulator?.screen?.transcriptText.orEmpty() }
                if (transcript.contains("[SSH Error: Check the saved credential or SSH key.]")) return@repeat
                Thread.sleep(100)
            }
            capture(session!!, "localization-ssh-error.png")
            val rendered = transcript.replace(Regex("\\s+"), " ")
            record("Fixture terminal transcript: $rendered")
            check(rendered.contains("user@127.0.0.1's password:"))
            check(rendered.contains("Permission denied, please try again."))
            check(rendered.contains("[SSH Error: Check the saved credential or SSH key.]"))
            record("PASS: rejecting SSHJ fixture, original UserAuthException category, actual terminal password prompts/retries/error text")
        } finally {
            session?.let { test.runOnMainSync { it.finish() } }
            test.waitForIdleSync()
            pm.deleteProfile(profile.id)
            check(context.getSharedPreferences("farside_profiles", 0).edit().commit())
            hostKeys.remove("127.0.0.1",server.port); server.stop(true)
        }
        return evidence.readText()
    }
}
