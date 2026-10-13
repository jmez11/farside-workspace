package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.ProxyType
import com.farside.workspace.ssh.JumpChainException
import com.farside.workspace.ssh.JumpChains
import com.farside.workspace.ssh.SshConnectionManager
import java.util.UUID

/** Jump host checks: `-e jumpMode resolver|live [-e jumpKey <saved key name>]`. Live mode uses owned temporary profiles removed afterwards. */
class JumpHostVerification(private val test: Instrumentation) {
    private val context = test.targetContext
    private val lines = mutableListOf<String>()
    private fun record(line: String) { lines.add(line) }

    fun run(mode: String, keyName: String?, hop1Host: String, hop2Host: String, deadHost: String): String {
        try {
            when (mode) {
                "resolver" -> resolver()
                "live" -> live(keyName, hop1Host, hop2Host, deadHost)
                "seed" -> seed(keyName, hop1Host, hop2Host, deadHost)
                else -> error("Unknown jumpMode $mode")
            }
        } catch (e: Throwable) {
            record("FAIL: ${e.javaClass.simpleName}: ${e.message}")
            throw AssertionError(lines.joinToString("\n"), e)
        }
        return lines.joinToString("\n")
    }

    private fun p(id: String, jump: String? = null) = ConnectionProfile(id = id, name = id, type = ConnectionType.SSH, host = id, user = "u", pass = "p", jumpHostProfileId = jump)

    private fun resolver() {
        val a = p("a"); val b = p("b", "a"); val c = p("c", "b"); val d = p("d", "c")
        val all = listOf(a, b, c, d)
        check(JumpChains.resolveChain(a, all).hops.map { it.id } == listOf("a"))
        check(JumpChains.resolveChain(c, all).hops.map { it.id } == listOf("a", "b", "c"))
        record("PASS: resolveChain connect order a,b,c")
        try { JumpChains.resolveChain(d, all); error("4-hop chain accepted") } catch (e: JumpChainException) { record("PASS: 3-hop cap: ${e.message}") }
        val cyc = listOf(p("x", "y"), p("y", "x"))
        try { JumpChains.resolveChain(cyc[0], cyc); error("cycle accepted") } catch (e: JumpChainException) { record("PASS: cycle refused: ${e.message}") }
        val dangling = JumpChains.resolveChain(p("z", "gone"), listOf(p("z", "gone")))
        check(dangling.hops.size == 1 && dangling.warning != null)
        record("PASS: dangling reference -> direct plus warning")
        check(JumpChains.candidates(a, all).isEmpty())
        check(JumpChains.candidates(p("n"), all).map { it.id }.toSet() == setOf("a", "b"))
        record("PASS: picker excludes self/cycles/over-cap")
    }

    private fun live(keyName: String?, hop1Host: String, hop2Host: String, deadHost: String) {
        val pm = ProfileManager(context)
        val keys = pm.getSshKeys()
        val key = checkNotNull(if (keyName != null) keys.find { it.name == keyName } else keys.singleOrNull()) { "Saved SSH key not found; pass -e jumpKey <name>" }
        val owned = mutableListOf<String>()
        fun profile(id: String, host: String, user: String, jump: String?, cloudflare: Boolean) = ConnectionProfile(
            id = id, name = id, type = ConnectionType.SSH, host = host, port = 22, user = user, pass = "", sshKeyId = key.id,
            useCloudflare = cloudflare, proxyType = if (cloudflare) ProxyType.CLOUDFLARED else ProxyType.NONE, jumpHostProfileId = jump
        ).also { owned.add(id) }
        val suffix = UUID.randomUUID().toString().take(6)
        val hop1 = profile("jh-bastion-$suffix", hop1Host, "root", null, true)
        val target = profile("jh-target-$suffix", hop2Host, "ubuntu", hop1.id, false)
        val dead = profile("jh-dead-$suffix", deadHost, "ubuntu", hop1.id, false)
        val baseline = pm.getProfiles()
        try {
            pm.saveProfiles(baseline + listOf(hop1, target, dead))
            val status = StringBuilder()
            val worker = Thread {
                val lease = SshConnectionManager.acquire(context, target.id)
                try {
                    val ssh = lease.connect(target, onStatus = { status.append(it) })
                    val session = ssh.startSession()
                    val cmd = session.exec("echo CONN=\$SSH_CONNECTION; hostname")
                    val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                    cmd.join()
                    session.close()
                    status.append("\nEXEC>>").append(out)
                } catch (e: Throwable) { status.append("\nERR>>${e.javaClass.simpleName}: ${e.message}") }
                finally { lease.close() }
            }
            worker.start(); worker.join(120_000)
            record("positive status:\n$status")
            check(status.contains("EXEC>>") && status.contains("CONN=")) { "Chain did not reach target" }
            check(status.contains("Chain: ") && status.contains("✓")) { "Missing per-hop success status" }
            record("PASS: positive chain ${hop1Host} -> ${hop2Host}; CONN= line above shows source address as seen by target sshd")

            val negStatus = StringBuilder()
            var failure: Throwable? = null
            val negative = Thread {
                val lease = SshConnectionManager.acquire(context, dead.id)
                try { lease.connect(dead, onStatus = { negStatus.append(it) }) } catch (e: Throwable) { failure = e } finally { lease.close() }
            }
            negative.start(); negative.join(180_000)
            record("negative status:\n$negStatus\nfailure=${failure?.javaClass?.simpleName}: ${failure?.message}")
            checkNotNull(failure) { "Dead target unexpectedly connected" }
            check(negStatus.contains("${hop1.name} ✓") && negStatus.contains("${dead.name} ✗")) { "Per-hop failure UI text missing" }
            record("PASS: negative per-hop failure names the failed hop")
        } finally {
            pm.saveProfiles(pm.getProfiles().filterNot { it.id in owned })
            context.getSharedPreferences("farside_profiles", android.content.Context.MODE_PRIVATE).edit().commit()
            record("Cleanup: owned profiles removed")
        }
    }

    /** Saves persistent, idempotent demo profiles (fixed ids) so the jump chain can be tried from the launcher. */
    private fun seed(keyName: String?, hop1Host: String, hop2Host: String, deadHost: String) {
        val pm = ProfileManager(context)
        val keys = pm.getSshKeys()
        val key = checkNotNull(if (keyName != null) keys.find { it.name == keyName } else keys.singleOrNull()) { "Saved SSH key not found; pass -e jumpKey <name>" }
        fun profile(id: String, name: String, host: String, user: String, jump: String?, cf: Boolean) = ConnectionProfile(
            id = id, name = name, type = ConnectionType.SSH, host = host, port = 22, user = user, pass = "", sshKeyId = key.id,
            useCloudflare = cf, proxyType = if (cf) ProxyType.CLOUDFLARED else ProxyType.NONE, jumpHostProfileId = jump)
        val bastion = profile("jumptest-bastion", "NanoKVM (jump test)", hop1Host, "root", null, true)
        val seeded = listOf(bastion,
            profile("jumptest-target", "plex-max via NanoKVM", hop2Host, "ubuntu", bastion.id, false),
            profile("jumptest-dead", "dead host via NanoKVM", deadHost, "ubuntu", bastion.id, false))
        pm.saveProfiles(pm.getProfiles().filterNot { it.id.startsWith("jumptest-") } + seeded)
        context.getSharedPreferences("farside_profiles", android.content.Context.MODE_PRIVATE).edit().commit()
        record("PASS: seeded ${seeded.joinToString { it.name }}")
    }}
