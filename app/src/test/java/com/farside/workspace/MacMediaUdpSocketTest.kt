package com.farside.workspace

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MacMediaUdpSocketTest {
    @Test fun concurrentSessionsReceiveIndependentlyAndClosingOnePreservesOther() {
        val blobA=ByteArray(46) {it.toByte()};val blobB=ByteArray(46) {(it+75).toByte()}
        val senderA=MacHevcSrtpSender(blobA,10);val senderB=MacHevcSrtpSender(blobB,10)
        fun receiver(blob:ByteArray)=MacHevcSrtp(blob.copyOfRange(0,32),blob.copyOfRange(32,46))
        val a=MacMediaUdpSocket.open(0,2000)
        val b=MacMediaUdpSocket.open(0,2000)
        val port=a.localPort
        try {
            assertEquals(port,b.localPort)
            DatagramSocket().use { sender ->
                val packets=listOf(senderA.protect(byteArrayOf(11),96,1),senderB.protect(byteArrayOf(22),96,2))
                packets.forEach {sender.send(DatagramPacket(it,it.size,InetAddress.getByName("127.0.0.1"),port))}
                listOf(a to blobA,b to blobB).forEachIndexed {index,(socket,blob) ->
                    val auth=receiver(blob);val decoded=mutableListOf<Int>()
                    repeat(2) {
                        val packet=DatagramPacket(ByteArray(2048),2048);socket.receive(packet)
                        runCatching {auth.decryptPacket(packet.data,packet.offset,packet.length)}.onSuccess {decoded.add(it.payload[0].toInt())}
                    }
                    assertEquals(listOf(if(index==0) 11 else 22),decoded)
                }
                a.close()
                val bytes=senderB.protect(byteArrayOf(33),96,3)
                sender.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),port))
                val packet=DatagramPacket(ByteArray(2048),2048);b.receive(packet)
                assertEquals(33,receiver(blobB).decryptPacket(packet.data,0,packet.length).payload[0].toInt())
            }
        } finally {a.close();b.close()}
        DatagramSocket(port).close() // Last owner released the fixed port.
    }
}
