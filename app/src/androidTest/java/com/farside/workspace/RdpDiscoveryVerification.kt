package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import com.farside.workspace.utils.NetworkScanner
import com.farside.workspace.utils.DiscoveredHost
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.NetworkInterface
import java.net.Socket

class RdpDiscoveryVerification(private val instrumentation: Instrumentation) {
    fun verifySavedSession(profileName: String): String {
        val profile = checkNotNull(com.farside.workspace.data.ProfileManager(instrumentation.targetContext)
            .getProfiles().singleOrNull { it.name == profileName && it.type == ConnectionType.RDP }) {
            "Saved RDP profile missing: $profileName"
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SessionActivity::class.java)
            .putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
        try {
            val deadline = SystemClock.uptimeMillis() + 20000
            while (activity.rdpInstance == 0L && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
            val session = activity.rdpMonitor?.session
            check(activity.rdpInstance != 0L) {
                "Saved RDP session did not connect: $profileName; native=${session?.instance?.value}; " +
                    "failure=${session?.failureReason?.value}; disconnected=${session?.disconnectedReason?.value}; " +
                    "certificatePending=${session?.certificateRequest?.value != null}; surface=${activity.rdpMonitor?.hadSurface}"
            }
            return "PASS: saved RDP session connected: $profileName"
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    fun diagnoseName(name: String): String = runBlocking {
        val reply = runCatching {
            val broadcast = NetworkInterface.getByName("wlan0").interfaceAddresses.firstNotNullOf { it.broadcast }
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1000
                val id = 0x7834
                val query = NetworkScanner.netBiosNameQuery(name, id)
                socket.send(DatagramPacket(query, query.size, broadcast, 137))
                val bytes = ByteArray(512)
                val packet = DatagramPacket(bytes, bytes.size)
                socket.receive(packet)
                "source=${packet.address.hostAddress} payload=${NetworkScanner.parseNetBiosNameQueryResponse(bytes, packet.length, name, id)}"
            }
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        val dns = runCatching { InetAddress.getByName(name).hostAddress }.getOrNull()
        val netbios = NetworkScanner.resolveNetBiosName(name)
        val resolved = NetworkScanner.resolveRdpHost(name)
        val tcp = runCatching {
            Socket().use { it.connect(InetSocketAddress(resolved, 3389), 1500) }
            "connected"
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        "name=$name reply=[$reply] dns=$dns netbios=$netbios resolved=$resolved tcp3389=$tcp"
    }
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

    private fun nameQueryResponse(name: String, transactionId: Int, compressed: Boolean = true): ByteArray {
        val query = NetworkScanner.netBiosNameQuery(name, transactionId)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeShort(transactionId); out.writeShort(0x8500)
            out.writeShort(1); out.writeShort(1); out.writeShort(0); out.writeShort(0)
            out.write(query, 12, 38)
            if (compressed) out.writeShort(0xc00c) else out.write(query, 12, 34)
            out.writeShort(0x20); out.writeShort(1); out.writeInt(60)
            out.writeShort(6); out.writeShort(0)
            out.write(byteArrayOf(172.toByte(), 16, 0, 54))
        }
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
        val query = NetworkScanner.netBiosNameQuery(laptop.name, 0x1234)
        check(query.size == 50 && query[0] == 0x12.toByte() && query[1] == 0x34.toByte())
        val nameResponse = nameQueryResponse(laptop.name, 0x1234)
        check(NetworkScanner.parseNetBiosNameQueryResponse(nameResponse, nameResponse.size, laptop.name, 0x1234) == "172.16.0.54")
        val uncompressed = nameQueryResponse(laptop.name, 0x1234, compressed = false)
        check(NetworkScanner.parseNetBiosNameQueryResponse(uncompressed, uncompressed.size, laptop.name, 0x1234) == "172.16.0.54")
        check(NetworkScanner.parseNetBiosNameQueryResponse(nameResponse, nameResponse.size, "OTHER-PC", 0x1234) == null)
        check(NetworkScanner.parseNetBiosNameQueryResponse(nameResponse, nameResponse.size, laptop.name, 0x1235) == null)
        for (size in 0 until nameResponse.size) {
            check(NetworkScanner.parseNetBiosNameQueryResponse(nameResponse, size, laptop.name, 0x1234) == null) {
                "Accepted truncated name query response of $size bytes"
            }
        }
        val report = StringBuilder("PASS: node status and targeted name-query packets, compressed/uncompressed answers, name/transaction validation and truncation.\n")
        check(DiscoveredHost("192.0.2.1", "Unknown PC", hostname = null).savedRdpHost() == "192.0.2.1")
        val hosts = NetworkScanner.scanLocalNetworkForRdp(instrumentation.targetContext)
        hosts.sortedBy { it.ip }.forEach { report.append("Discovered ${it.ip}: ${it.name}\n") }
        val found = hosts.singleOrNull { it.ip == "172.16.0.54" }
        check(found?.name == laptop.name) { "Live laptop discovery: $found" }
        check(found.savedRdpHost() == found.ip)
        check(found.savedVncHost() == found.ip)
        val profile = ConnectionProfile("discovery-fixture", "Discovered laptop", ConnectionType.RDP,
            found.savedRdpHost(), user = "", pass = "")
        check(ConnectionProfile.fromJson(profile.toJson()).host == found.ip)
        val netbiosEndpoint = NetworkScanner.resolveNetBiosName(laptop.name)
        check(netbiosEndpoint == found.ip) { "Targeted NetBIOS query resolved to $netbiosEndpoint, expected ${found.ip}" }
        val endpoint = NetworkScanner.resolveRdpHost(profile.host)
        check(endpoint == found.ip) { "Saved IP resolved to $endpoint, expected ${found.ip}" }
        Socket().use { it.connect(InetSocketAddress(endpoint, 3389), 1000) }
        report.append("PASS: targeted NetBIOS name query and saved IP resolve to the live RDP endpoint; TCP/3389 accepts connections.\n")
        instrumentation.targetContext.cacheDir.resolve("rdp-discovery-results.txt").writeText(report.toString())
        report.toString()
    }
}
