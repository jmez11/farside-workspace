package com.farside.workspace.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.NetworkInterface
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class DiscoveredHost(val ip: String, val name: String, val port: Int = 3389, val hostname: String? = null) {
    fun savedRdpHost(): String = hostname ?: ip
    fun savedVncHost(): String = name
}

object NetworkScanner {
    /** Only Screen-Sharing-enabled Macs advertise _rfb._tcp. mDNS is link-local:
     * same LAN only, not across VLANs or Tailscale; it never supplies credentials. */
    @Suppress("DEPRECATION")
    suspend fun scanLocalNetworkForVnc(context: Context): List<DiscoveredHost> = withContext(Dispatchers.Main.immediate) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        val results = linkedMapOf<String, DiscoveredHost>()
        val pending = java.util.ArrayDeque<android.net.nsd.NsdServiceInfo>()
        val present = mutableSetOf<String>()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var active = true
        var resolving = false
        var failure: String? = null
        fun resolveNext() {
            if (!active || resolving || pending.isEmpty()) return
            val service = pending.removeFirst()
            resolving = true
            try {
                nsd.resolveService(service, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(info: android.net.nsd.NsdServiceInfo, error: Int) {
                        handler.post { resolving = false; resolveNext() }
                    }
                    override fun onServiceResolved(info: android.net.nsd.NsdServiceInfo) {
                        handler.post {
                            if (active && service.serviceName in present) info.host?.hostAddress?.let { address ->
                                if (info.port in 1..65535) results[service.serviceName] = DiscoveredHost(address, info.serviceName, info.port)
                            }
                            resolving = false; resolveNext()
                        }
                    }
                })
            } catch (_: IllegalArgumentException) { resolving = false; resolveNext() }
        }
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { handler.post { failure = context.getString(com.farside.workspace.R.string.vnc_discovery_failed, error) } }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) { handler.post {
                if (active && present.add(info.serviceName)) { pending.add(info); resolveNext() }
            } }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) { handler.post {
                present.remove(info.serviceName); results.remove(info.serviceName)
            } }
        }
        try {
            nsd.discoverServices("_rfb._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
            kotlinx.coroutines.delay(5000)
            failure?.let { throw IllegalStateException(it) }
            results.values.sortedBy { it.name.lowercase() }
        } finally {
            active = false
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    suspend fun scanLocalNetworkForRdp(context: Context): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val ipAddress = getLocalIpAddress()
        
        if (ipAddress == "0.0.0.0") return@withContext emptyList()
        
        val prefix = ipAddress.substringBeforeLast(".") + "."
        val activeHosts = mutableListOf<DiscoveredHost>()
        
        val jobs = (1..254).map { i ->
            async {
                val host = "$prefix$i"
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, 3389), 300)
                    socket.close()
                    
                    val inetAddress = InetAddress.getByName(host)
                    var resolvedName = inetAddress.hostName
                    if (resolvedName == host || resolvedName.isBlank()) {
                        val netbios = getNetBiosName(host)
                        if (netbios != null && netbios.isNotBlank()) {
                            resolvedName = netbios
                        }
                    }
                    val hostname = resolvedName.takeUnless { it == host || it.isBlank() }
                    val finalName = hostname ?: context.getString(com.farside.workspace.R.string.unknown_pc, host)
                    
                    synchronized(activeHosts) {
                        activeHosts.add(DiscoveredHost(ip = host, name = finalName, hostname = hostname))
                    }
                } catch (e: Exception) {
                    // Timeout or unreachable
                }
            }
        }
        jobs.awaitAll()
        activeHosts.toList()
    }

    /** Resolve only the saved name. A failed DNS lookup must not scan the LAN on connect. */
    suspend fun resolveRdpHost(hostname: String): String {
        if (runCatching { InetAddress.getByName(hostname) }.isSuccess) return hostname
        return resolveNetBiosName(hostname) ?: hostname
    }

    /** A NetBIOS name query asks for one computer's current address using UDP/137 broadcast. */
    internal suspend fun resolveNetBiosName(hostname: String): String? = withContext(Dispatchers.IO) {
        if (hostname.length !in 1..15 || !hostname.all { it.code in 0x21..0x7e && it != '.' }) {
            return@withContext null
        }
        val broadcasts = linkedSetOf<InetAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return@withContext null
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            runCatching {
                if (network.isUp && !network.isLoopback) {
                    network.interfaceAddresses.mapNotNullTo(broadcasts) { it.broadcast }
                }
            }
        }
        for (broadcast in broadcasts) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 750
                    val transactionId = kotlin.random.Random.nextInt(0x10000)
                    val query = netBiosNameQuery(hostname, transactionId)
                    socket.send(DatagramPacket(query, query.size, broadcast, 137))
                    val deadline = android.os.SystemClock.elapsedRealtime() + 750
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        val response = ByteArray(512)
                        val packet = DatagramPacket(response, response.size)
                        socket.soTimeout = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()
                        socket.receive(packet)
                        parseNetBiosNameQueryResponse(response, packet.length, hostname, transactionId)?.let {
                            // Multi-homed PCs can advertise an unreachable adapter first. The
                            // responding socket address is the interface that answered this LAN query.
                            return@withContext packet.address.hostAddress
                        }
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // No host with this name answered on this link.
            } catch (_: Exception) {
                // A blocked or unavailable interface must not prevent other links from resolving.
            }
        }
        null
    }

    internal fun netBiosNameQuery(hostname: String, transactionId: Int): ByteArray {
        val name = hostname.uppercase(java.util.Locale.ROOT).padEnd(15, ' ')
        val buffer = ByteArrayOutputStream(50)
        DataOutputStream(buffer).use { out ->
            out.writeShort(transactionId)
            out.writeShort(0x0110) // Recursion desired; broadcast query.
            out.writeShort(1)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeByte(32)
            (name + '\u0000').forEach { char ->
                val value = char.code
                out.writeByte('A'.code + (value ushr 4))
                out.writeByte('A'.code + (value and 15))
            }
            out.writeByte(0)
            out.writeShort(0x20) // NB name record.
            out.writeShort(1)
        }
        return buffer.toByteArray()
    }

    internal fun parseNetBiosNameQueryResponse(
        response: ByteArray, length: Int, hostname: String, transactionId: Int
    ): String? {
        if (length !in 12..response.size) return null
        fun byte(offset: Int): Int = response[offset].toInt() and 0xff
        fun word(offset: Int): Int = (byte(offset) shl 8) or byte(offset + 1)
        if (word(0) != transactionId || word(2) and 0xf80f != 0x8000) return null
        val expectedName = netBiosNameQuery(hostname, transactionId).copyOfRange(13, 45)
        fun readName(start: Int): Int? {
            var offset = start
            var end = -1
            var firstLabel: ByteArray? = null
            val visited = mutableSetOf<Int>()
            while (offset < length && visited.add(offset)) {
                val size = byte(offset++)
                if (size == 0) return if (firstLabel?.contentEquals(expectedName) == true) {
                    if (end >= 0) end else offset
                } else null
                if (size and 0xc0 == 0xc0) {
                    if (offset >= length) return null
                    if (end < 0) end = offset + 1
                    offset = ((size and 0x3f) shl 8) or byte(offset)
                    continue
                }
                if (size > 63 || offset + size > length) return null
                if (firstLabel == null) firstLabel = response.copyOfRange(offset, offset + size)
                offset += size
            }
            return null
        }
        var offset = 12
        repeat(word(4)) {
            offset = readName(offset) ?: return null
            if (offset + 4 > length) return null
            offset += 4
        }
        repeat(word(6)) {
            offset = readName(offset) ?: return null
            if (offset + 10 > length) return null
            val type = word(offset)
            val recordClass = word(offset + 2)
            val dataLength = word(offset + 8)
            offset += 10
            if (offset + dataLength > length) return null
            if (type == 0x20 && recordClass == 1 && dataLength >= 6) {
                var addressOffset = offset
                while (addressOffset + 6 <= offset + dataLength) {
                    val address = response.copyOfRange(addressOffset + 2, addressOffset + 6)
                    if (address.any { it.toInt() != 0 }) return InetAddress.getByAddress(address).hostAddress
                    addressOffset += 6
                }
            }
            offset += dataLength
        }
        return null
    }

    /** Bonjour service names are display names, not DNS hostnames; browse the service again
     * at connect time to follow its current address. */
    suspend fun resolveVncHost(context: Context, serviceName: String, port: Int): String {
        if (runCatching { InetAddress.getByName(serviceName) }.isSuccess) return serviceName
        return scanLocalNetworkForVnc(context).firstOrNull {
            it.name == serviceName && it.port == port
        }?.ip ?: serviceName
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            // Ignore
        }
        return "0.0.0.0"
    }

    private suspend fun getNetBiosName(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(ip), 137)
            socket.soTimeout = 500
            val request = byteArrayOf(
                0x82.toByte(), 0x28.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x20.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x00.toByte(), 0x00.toByte(), 0x21.toByte(),
                0x00.toByte(), 0x01.toByte()
            )
            val packet = DatagramPacket(request, request.size, InetAddress.getByName(ip), 137)
            socket.send(packet)
            
            val response = ByteArray(1024)
            val rxPacket = DatagramPacket(response, response.size)
            socket.receive(rxPacket)
            parseNetBiosName(response, rxPacket.length)
            }
        } catch (e: Exception) {
            null
        }
    }
    /** Node-status names have a service suffix and flags; group names are not PCs. */
    internal fun parseNetBiosName(response: ByteArray, length: Int = response.size): String? {
        if (length !in 12..response.size) return null
        fun byte(offset: Int) = response[offset].toInt() and 0xff
        fun word(offset: Int) = (byte(offset) shl 8) or byte(offset + 1)
        fun skipName(start: Int): Int? {
            var offset = start
            while (offset < length) {
                val size = byte(offset++)
                if (size == 0) return offset
                if (size and 0xc0 == 0xc0) return if (offset < length) offset + 1 else null
                if (size > 63 || offset + size > length) return null
                offset += size
            }
            return null
        }
        if (word(0) != 0x8228 || word(2) and 0x800f != 0x8000) return null
        var offset = 12
        repeat(word(4)) {
            offset = skipName(offset) ?: return null
            if (offset + 4 > length) return null
            offset += 4
        }
        repeat(word(6)) {
            offset = skipName(offset) ?: return null
            if (offset + 10 > length) return null
            val type = word(offset)
            val recordClass = word(offset + 2)
            val dataLength = word(offset + 8)
            offset += 10
            if (offset + dataLength > length) return null
            if (type == 0x21 && recordClass == 1 && dataLength > 0) {
                val count = byte(offset)
                if (1 + count * 18 > dataLength) return null
                var workstation: String? = null
                var server: String? = null
                repeat(count) { index ->
                    val entry = offset + 1 + index * 18
                    val suffix = byte(entry + 15)
                    val flags = word(entry + 16)
                    val name = String(response, entry, 15, Charsets.US_ASCII).trim(' ', '\u0000')
                    if (flags and 0x8000 == 0 && name.isNotBlank()) {
                        if (suffix == 0x00 && workstation == null) workstation = name
                        if (suffix == 0x20 && server == null) server = name
                    }
                }
                return workstation ?: server
            }
            offset += dataLength
        }
        return null
    }

}
