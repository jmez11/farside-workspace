package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacAvcPacketOrderTest {
    private fun packet(seq:Int,ts:Long=1)=MacAvcPacketOrder.Packet(byteArrayOf(0x61,1),seq,ts,true)
    @Test fun repairsAcrossWrapAndPictureBoundaries() {
        val order=MacAvcPacketOrder();order.reset(65534)
        assertTrue(order.accept(packet(0,2),0).isEmpty())
        assertEquals(listOf(65534),order.accept(packet(65534),1).map { it.sequence })
        assertEquals(listOf(65535,0),order.accept(packet(65535),2).map { it.sequence })
        assertFalse(order.takeLoss())
        assertTrue(order.accept(packet(65535),3).isEmpty())
        assertEquals(1,order.duplicates)
    }
    @Test fun missingPacketsHaveBoundedWaitAndRecovery() {
        val order=MacAvcPacketOrder();order.reset(10)
        assertTrue(order.accept(packet(12),5).isEmpty())
        assertTrue(order.drain(304).isEmpty())
        assertEquals(listOf(12),order.drain(305).map { it.sequence })
        assertTrue(order.takeLoss());assertFalse(order.takeLoss())
        assertEquals(2L,order.missingPackets)
        assertEquals(305L,order.lastGapAt)
        assertTrue(order.accept(packet(11),306).isEmpty())
        order.reset(20)
        assertEquals(listOf(20),order.accept(packet(20),307).map { it.sequence })
    }
    @Test fun overlayJitterCanArriveAfterTheOldHundredMillisecondWindow() {
        val order=MacAvcPacketOrder();order.reset(10)
        assertTrue(order.accept(packet(11),0).isEmpty())
        assertTrue(order.drain(150).isEmpty())
        assertEquals(listOf(10,11),order.accept(packet(10),240).map {it.sequence})
        assertFalse(order.takeLoss())
        assertEquals(1L,order.outOfOrder)
    }
    @Test fun packetsQueuedBehindLossAreNotActualReordering() {
        val order=MacAvcPacketOrder();order.reset(10)
        order.accept(packet(12),0);order.accept(packet(13),10);order.accept(packet(14),20)
        assertEquals(0L,order.outOfOrder)
        assertEquals(3L,order.repaired)
        order.drain(300)
        assertEquals(2L,order.missingPackets)
    }
    @Test fun memoryLimitForcesProgressWithoutUnboundedBuffering() {
        val order=MacAvcPacketOrder();order.reset(1)
        val large=MacAvcPacketOrder.Packet(ByteArray(4*1024*1024),2,1,false)
        assertTrue(order.accept(large,0).isEmpty())
        assertEquals(listOf(2,3),order.accept(large.copy(sequence=3),1).map { it.sequence })
        assertTrue(order.takeLoss())
    }
}
