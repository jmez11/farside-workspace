package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.utils.NetworkScanner
import com.farside.workspace.utils.DiscoveredHost
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class RdpDiscoveryVerification(private val instrumentation: Instrumentation) {
    private data class Entry(val name: String, val suffix: Int = 0, val flags: Int = 0x0400)

    private fun packet(entries: List<Entry>, compressed: Boolean = false): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeShort(0x8228); out.writeShort(0x8400)
        out.writeShort(if (compressed) 1 else 0); out.writeShort(1)
        out.writeShort(0); out.writeShort(0)
        fun name() { out.writeByte(32); out.writeBytes("CK" + "A".repeat(30)); out.writeByte(0) }
        if (compressed) { name(); out.writeShort(0x21); out.writeShort(1); out.writeShort(0xc00c) }
        else name()
        out.writeShort(0x21); out.writeShort(1); out.writeInt(0)
        out.writeShort(1 + 18 * entries.size + 6); out.writeByte(entries.size)
        entries.forEach {
            out.writeBytes(it.name.padEnd(15, ' ')); out.writeByte(it.suffix); out.writeShort(it.flags)
        }
        out.write(ByteArray(6))
        return buffer.toByteArray()
    }

    fun run(): String = runBlocking {
        val laptop = Entry("LAPTOP-BBJV33M6")
        val group = Entry("WORKGROUP", flags = 0x8400)
        val response = packet(listOf(group, laptop, laptop.copy(suffix = 0x20)))
        check(NetworkScanner.parseNetBiosName(response) == laptop.name) { "Group-first response selected wrong name" }
        check(NetworkScanner.parseNetBiosName(packet(listOf(Entry("NUVBOX"), group))) == "NUVBOX")
        check(NetworkScanner.parseNetBiosName(packet(listOf(group))) == null)
        check(NetworkScanner.parseNetBiosName(packet(listOf(group, laptop.copy(suffix = 0x20)))) == laptop.name)
        check(NetworkScanner.parseNetBiosName(packet(listOf(Entry("OTHER", suffix = 3), laptop))) == laptop.name)
        check(NetworkScanner.parseNetBiosName(packet(listOf(group, laptop), compressed = true)) == laptop.name)
        for (size in 0 until response.size) {
            check(NetworkScanner.parseNetBiosName(response, size) == null) { "Accepted truncated response of $size bytes" }
        }
        val wrongId = response.copyOf().also { it[0] = 0 }
        check(NetworkScanner.parseNetBiosName(wrongId) == null)
        val error = response.copyOf().also { it[3] = 3 }
        check(NetworkScanner.parseNetBiosName(error) == null)
        val report = StringBuilder("PASS: group-first, computer-first, group-only, server fallback, service filtering, compressed names, truncation, transaction and response errors.\n")
        check(DiscoveredHost("192.0.2.1", "Unknown PC", hostname = null).savedRdpHost() == "192.0.2.1")
        val hosts = NetworkScanner.scanLocalNetworkForRdp(instrumentation.targetContext)
        hosts.sortedBy { it.ip }.forEach { report.append("Discovered ${it.ip}: ${it.name}\n") }
        val found = hosts.singleOrNull { it.ip == "172.16.0.54" }
        check(found?.name == laptop.name) { "Live laptop discovery: $found" }
        check(found.savedRdpHost() == laptop.name)
        val profile = ConnectionProfile("discovery-fixture", "Discovered laptop", ConnectionType.RDP,
            found.savedRdpHost(), user = "", pass = "")
        check(ConnectionProfile.fromJson(profile.toJson()).host == laptop.name)
        val endpoint = NetworkScanner.resolveRdpHost(instrumentation.targetContext, profile.host)
        check(endpoint == found.ip) { "Saved hostname resolved to $endpoint, expected ${found.ip}" }
        Socket().use { it.connect(InetSocketAddress(endpoint, 3389), 1000) }
        report.append("PASS: saved hostname resolves to the discovered live RDP endpoint and accepts TCP/3389.\n")
        instrumentation.targetContext.cacheDir.resolve("rdp-discovery-results.txt").writeText(report.toString())
        report.toString()
    }
}
