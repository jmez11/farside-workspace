package com.farside.workspace

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MacDisplaySelectionTest {
    private fun layout():ByteArray=ByteBuffer.allocate(76).apply {
        putShort(0,1);putShort(2,1920);putShort(4,1080);putShort(6,3840);putShort(8,2160)
        putShort(18,1);putInt(36,1234);putShort(52,2160);putShort(54,3840)
    }.array()
    @Test fun soleDisplayKeepsItsActualIdAndBoundsOnTheWire() {
        val selection=checkNotNull(MacDisplaySelection.parse(layout()))
        val wire=ByteBuffer.wrap(selection.request())
        assertEquals(9,wire.get().toInt());assertEquals(0,wire.get().toInt());assertEquals(1,wire.short.toInt())
        assertEquals(1234,wire.int);assertEquals(0,wire.short.toInt());assertEquals(0,wire.short.toInt())
        assertEquals(3840,wire.short.toInt());assertEquals(2160,wire.short.toInt());assertFalse(wire.hasRemaining())
    }
    @Test fun ambiguousOrPartialLayoutsCannotSelectAnUnrelatedScreen() {
        assertNull(MacDisplaySelection.parse(layout().copyOf(75)))
        for(offset in listOf(18,48,50,52,54)) {
            val data=layout();ByteBuffer.wrap(data).putShort(offset,2)
            assertNull(MacDisplaySelection.parse(data))
        }
        for(id in listOf(0,-1)) {
            val data=layout();ByteBuffer.wrap(data).putInt(36,id)
            assertNull(MacDisplaySelection.parse(data))
        }
    }
    @Test fun documentedFirstScreenSelectorPreservesNegotiatedDimensions() {
        val selection=checkNotNull(MacDisplaySelection.parse(layout()))
        val wire=ByteBuffer.wrap(selection.request(firstIndex=true))
        assertEquals(0,wire.getInt(4));assertEquals(3840,wire.getShort(12).toInt());assertEquals(2160,wire.getShort(14).toInt())
    }
}
