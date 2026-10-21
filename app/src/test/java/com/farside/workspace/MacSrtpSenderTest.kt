package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacSrtpSenderTest {
    @Test fun senderSurvivesSequenceRolloverAndAuthenticatesHeader() {
        val blob=ByteArray(46) { (it*7+11).toByte() }
        val sender=MacHevcSrtpSender(blob,0x10203040)
        val receiver=MacHevcSrtp(blob.copyOfRange(0,32),blob.copyOfRange(32,46))
        val payload=byteArrayOf(0,0x68,0x34,0)
        repeat(65538) { i ->
            val packet=sender.protect(payload,101,i.toLong()*480)
            val decoded=receiver.decryptPacket(packet,0,packet.size)
            if(i>=65535 || i==0) {
                assertArrayEquals(payload,decoded.payload)
                assertEquals(i and 65535,decoded.sequenceNumber)
                assertEquals(i.toLong()*480,decoded.timestamp)
            }
        }
        val tampered=sender.protect(payload,101,42).also { it[4]=(it[4].toInt() xor 1).toByte() }
        try { receiver.decryptPacket(tampered,0,tampered.size);fail("Tampered RTP timestamp accepted") }
        catch(_:MacHevcSrtpException) {}
    }
}
