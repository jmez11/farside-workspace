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

data class DiscoveredHost(val ip: String, val name: String)

object NetworkScanner {
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
                    val finalName = if (resolvedName == host || resolvedName.isBlank()) "Unknown PC ($host)" else resolvedName
                    
                    synchronized(activeHosts) {
                        activeHosts.add(DiscoveredHost(ip = host, name = finalName))
                    }
                } catch (e: Exception) {
                    // Timeout or unreachable
                }
            }
        }
        jobs.awaitAll()
        activeHosts.toList()
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
            val socket = DatagramSocket()
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
            socket.close()
            
            val nameBytes = response.copyOfRange(57, 57 + 15)
            String(nameBytes, Charsets.US_ASCII).trim()
        } catch (e: Exception) {
            null
        }
    }
}
