package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.util.concurrent.TimeUnit

/**
 * Diagnostic only: captures Mac-side tcpdump of the VNC TCP peer and UDP 5900/5901 while the
 * production automatic connection runs. The sudo password is a transient instrumentation arg
 * (`-e diagSudo`), written only to the remote sudo stdin; never logged or stored.
 */
internal class MacUdpDiag(private val test: Instrumentation) {
    fun run(id: String): String {
        val context = test.targetContext
        val pm = ProfileManager(context)
        val profile = pm.getProfiles().single { it.id == id && it.type == ConnectionType.VNC }
        val ssh = pm.getProfiles().single { it.host == profile.host && it.type == ConnectionType.SSH }
        val sudo = checkNotNull(test.arguments?.getString("diagSudo")) { "Missing -e diagSudo" }
        val lease = SshConnectionManager.acquire(context, ssh.id)
        val client = lease.connect(ssh.copy(reconnectAutomatically = false))
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val channel = client.startSession()
        val cmd = channel.exec(
            "exec /usr/bin/sudo -S -p '' /usr/sbin/tcpdump -nn -l -c 4000 -i any " +
                "'(tcp port 5900 and tcp[tcpflags] & tcp-syn != 0) or udp port 5900 or udp port 5901' 2>&1")
        cmd.outputStream.write((sudo + "\n").toByteArray()); cmd.outputStream.flush()
        val reader = Thread({
            runCatching { cmd.inputStream.bufferedReader().forEachLine { lines.add(it) } }
        }, "MacUdpDiagReader").apply { isDaemon = true; start() }
        Thread.sleep(3000)
        val outcome = runCatching { MacAutomaticConnectionVerification(test).run(id) }
            .fold({ it }, { "run threw ${it.javaClass.simpleName}: ${it.message} at ${it.stackTrace.firstOrNull { e -> e.className.startsWith("com.farside") }}" })
        runCatching { client.startSession().use { s -> s.exec("/usr/bin/sudo -S -p '' /usr/bin/pkill -x tcpdump").use { k ->
            k.outputStream.write((sudo + "\n").toByteArray()); k.outputStream.flush(); k.join(10, TimeUnit.SECONDS) } } }
        reader.join(3000)
        runCatching { channel.close() }
        val syn = lines.filter { "Flags [S" in it }
        val udp = lines.filter { "UDP" in it }
        return buildString {
            appendLine("automatic: $outcome")
            appendLine("tcp SYNs: ${syn.size}; udp lines: ${udp.size}")
            syn.take(4).forEach { appendLine(it) }
            udp.take(12).forEach { appendLine(it) }
            appendLine("... tail"); lines.takeLast(8).forEach { appendLine(it) }
        }
    }
}
