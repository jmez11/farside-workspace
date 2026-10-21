package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacHevcDonlTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    // Complete AP of three individually length-delimited parameter-set NALs, without DONL.
    private val ap = bytes(0x60,1, 0,3,0x40,1,0x80, 0,3,0x42,1,0x80, 0,3,0x44,1,0x80)

    @Test fun noDonlAggregationEstablishesCorrectFragmentOffsets() {
        val parser = MacHevcRtpParser()
        val sets = parser.parseRtpPayload(ap, 1)
        assertEquals(3, sets.size)
        assertEquals(false, parser.usesDonl(1))
        assertArrayEquals(bytes(0,0,0,1,0x40,1,0x80), sets[0])
        assertTrue(parser.parseRtpPayload(bytes(0x62,1,0x93,0xaa,0xbb),1).isEmpty())
        val frame = parser.parseRtpPayload(bytes(0x62,1,0x53,0xcc,0xdd),1).single()
        assertArrayEquals(bytes(0,0,0,1,0x26,1,0xaa,0xbb,0xcc,0xdd), frame)
        assertArrayEquals(bytes(0,0,0,1,0x26,1,0x80,0x44), parser.parseRtpPayload(bytes(0x26,1,0x80,0x44),1).single())
    }

    @Test fun donlAndNoDonlSsrcsStayIndependent() {
        val parser = MacHevcRtpParser()
        assertEquals(3, parser.parseRtpPayload(ap, 1).size)
        val legacy = ap.copyOfRange(0,2) + bytes(0,7) + ap.copyOfRange(2,ap.size)
        assertEquals(3, parser.parseRtpPayload(legacy, 2).size)
        assertEquals(false, parser.usesDonl(1))
        assertEquals(true, parser.usesDonl(2))
        assertArrayEquals(bytes(0,0,0,1,0x26,1,0x80,0x44), parser.parseRtpPayload(bytes(0x26,1,0,8,0x80,0x44),2).single())
    }

    @Test fun truncatedAggregationDoesNotEstablishDialect() {
        val parser = MacHevcRtpParser()
        assertTrue(parser.parseRtpPayload(ap.copyOf(ap.size-1),1).isEmpty())
        assertNull(parser.usesDonl(1))
        parser.parseRtpPayload(ap,1)
        parser.reset()
        assertNull(parser.usesDonl(1))
    }
}
