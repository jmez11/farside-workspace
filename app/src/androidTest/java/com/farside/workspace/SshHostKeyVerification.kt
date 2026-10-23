package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.ssh.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.common.keyprovider.KeyPairProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Owned loopback server only: verifies trust gates before any password reaches the server. */
internal class SshHostKeyVerification(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        SshConnectionManager.ensureBouncyCastleProvider()
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { context.cacheDir.toPath() }
        val flags = test.uiAutomation.serviceInfo
        flags.flags = flags.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = flags
        val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = checkNotNull(test.waitForMonitorWithTimeout(monitor, 15000)) as MainActivity
        val ui = SetupPhaseOneVerification(test)
        val worker = Executors.newSingleThreadExecutor()
        val store = SshHostKeyStore(context)
        val auth = AtomicInteger()
        fun pair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val first = pair()
        listOf("cs","da","de","el","es","fi","fr","it","ja","ko","nb","nl","pl","pt-BR","pt-PT","ro","ru","sv","tr","zh-CN","zh-HK","zh-TW").forEach { tag ->
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            val localized = context.createConfigurationContext(config)
            check(localized.getString(R.string.ssh_host_key_new_help,"fixture.invalid",22).contains("fixture.invalid:22"))
            check(localized.getString(R.string.ssh_host_key_changed_help,"fixture.invalid",22).contains("fixture.invalid:22"))
            check(localized.getString(R.string.ssh_host_key_previous,"SHA256:owned").contains("SHA256:owned"))
        }

        fun server(key: KeyPair, listen: Int) = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = listen
            keyPairProvider = KeyPairProvider { listOf(key) }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pass, _ ->
                auth.incrementAndGet(); user == "fixture" && pass == "fixture"
            }
            start()
        }
        var remote = server(first, 0)
        val port = remote.port
        val profile = ConnectionProfile("host-key-${UUID.randomUUID()}", "Owned SSH trust", ConnectionType.SSH,
            "127.0.0.1", port, "fixture", "fixture")
        fun attempt() = worker.submit<Boolean> {
            SshConnectionManager.acquire(context, profile.id).use { lease ->
                runCatching { lease.connect(profile).isAuthenticated }.getOrDefault(false)
            }
        }
        fun prompt(id: Int) { ui.find(context.getString(id)); check(auth.get() == 0) { "Credentials sent before host approval" } }
        try {
            check(store.get(profile.host, port) == null)
            var future = attempt()
            prompt(R.string.ssh_host_key_new)
            ui.click(context.getString(R.string.cancel))
            check(!future.get(15, TimeUnit.SECONDS) && store.get(profile.host, port) == null)
            future = attempt()
            prompt(R.string.ssh_host_key_new)
            ui.click(context.getString(R.string.ssh_host_key_save))
            check(future.get(15, TimeUnit.SECONDS))
            check(store.get(profile.host, port) == SshHostKey.from(first.public))
            check(store.get(" 127.0.0.1 ", port) == SshHostKey.from(first.public))
            check(attempt().get(15, TimeUnit.SECONDS))
            check(SshHostKeyPrompts.pending.value.isEmpty()) { "Matching saved key prompted again" }
            remote.stop(true)
            val second = pair()
            remote = server(second, port)
            auth.set(0)
            future = attempt()
            prompt(R.string.ssh_host_key_changed)
            ui.find(SshHostKey.from(second.public).fingerprint)
            ui.click(context.getString(R.string.cancel))
            check(!future.get(15, TimeUnit.SECONDS) && store.get(profile.host, port) == SshHostKey.from(first.public))
            future = attempt()
            prompt(R.string.ssh_host_key_changed)
            ui.click(context.getString(R.string.ssh_host_key_replace))
            check(future.get(15, TimeUnit.SECONDS))
            check(store.get(profile.host, port) == SshHostKey.from(second.public))
            val logical = SavedSshHostKeyVerifier(store, " 127.0.0.1 ", port) { false }
            check(logical.verify("127.0.0.1", 1, second.public)) { "Proxy port affected destination trust" }
            check(store.get(profile.host, port + 1) == null) { "Trust leaked to another port" }
            val generation = store.generation()
            // Isolated namespace: exercise clear/reapproval races without touching any real trust.
            val isolated = object : android.content.ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("owned_ssh_host_key_clear", mode)
            }
            val isolatedStore = SshHostKeyStore(isolated)
            val before = isolatedStore.generation()
            isolatedStore.clear()
            check(!isolatedStore.saveIfUnchanged("fixture.invalid", 22, SshHostKey.from(first.public), null, before))
            check(store.generation() == generation)
            context.deleteSharedPreferences("owned_ssh_host_key_clear")
            // Closing the requesting UI rejects a prompt and leaves saved trust unchanged.
            remote.stop(true); remote = server(first, port); auth.set(0)
            future = attempt(); prompt(R.string.ssh_host_key_changed)
            test.runOnMainSync { activity.finish() }
            check(!future.get(15, TimeUnit.SECONDS) && auth.get() == 0)
            return "PASS actual SSH trust: first-use cancel/save, no credentials before approval, known-key reconnect, changed-key warning/cancel/replace, logical proxy identity, port isolation, clear generation and UI-disposal rejection; owned loopback only"
        } finally {
            SshHostKeyPrompts.cancelAll()
            store.remove(profile.host, port)
            remote.stop(true)
            worker.shutdownNow()
            test.runOnMainSync { if (!activity.isFinishing) activity.finish() }
            test.removeMonitor(monitor)
        }
    }
}
