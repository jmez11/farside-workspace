package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacRtcpDiagnosticsTest {
    private val video=ByteArray(46) {it.toByte()}
    private val audio=ByteArray(46) {(it+60).toByte()}
    private fun protect(blob:ByteArray,payload:ByteArray)=MacHevcSrtcpEncryptor(blob.copyOfRange(0,32),blob.copyOfRange(32,46)).protect(payload)
    @Test fun authenticatesBothKeysAndPortsAndDeduplicatesWithoutCountingOtherSessions() {
        val d=MacRtcpDiagnostics(listOf(video,audio))
        val compound=protect(video,MacHevcRtcp.buildCompoundFir(10,20))
        assertTrue(d.receive(5900,compound));assertTrue(d.receive(5901,compound))
        assertTrue(d.receive(5900,protect(audio,MacHevcRtcp.buildRr(30))))
        val wrong=protect(ByteArray(46) {99},MacHevcRtcp.buildRr(40))
        assertTrue(d.receive(5900,wrong))
        val s=d.snapshot()
        assertEquals(3L,s.control);assertEquals(1L,s.video);assertEquals(2L,s.authenticated)
        assertEquals(1L,s.unverified);assertEquals(1L,s.duplicates);assertEquals(2L,s.rr);assertEquals(2L,s.feedback)
    }
    @Test fun excludesAudioIncludingMarkerBitAndRejectsTamperingAndMalformedCompound() {
        val d=MacRtcpDiagnostics(listOf(video))
        assertFalse(d.receive(5900,MacHevcSrtpSender(video,1).protect(ByteArray(4),229,0)))
        assertFalse(d.receive(5901,byteArrayOf(0)))
        val valid=protect(video,MacHevcRtcp.buildRr(10))
        val wrapped=ByteArray(5)+valid+ByteArray(3)
        assertTrue(d.receive(5900,wrapped,5,valid.size))
        valid[valid.lastIndex]=(valid.last().toInt() xor 1).toByte()
        assertTrue(d.receive(5900,valid))
        val bad=MacHevcRtcp.buildRr(11).apply {this[3]=100}
        assertTrue(d.receive(5901,protect(video,bad)))
        assertEquals(1L,d.snapshot().authenticated);assertEquals(1L,d.snapshot().unverified);assertEquals(1L,d.snapshot().malformed)
    }
    @Test fun countsSenderReportsAndKeepsUnprotectedPacketsUnverified() {
        val d=MacRtcpDiagnostics(listOf(video))
        val sr=java.nio.ByteBuffer.allocate(28).put(0x80.toByte()).put(200.toByte()).putShort(6).putInt(7).array()
        assertTrue(d.receive(5901,protect(video,sr)))
        assertTrue(d.receive(5900,MacHevcRtcp.buildRr(5)))
        assertEquals(1L,d.snapshot().sr);assertEquals(1L,d.snapshot().unverified)
    }
}
