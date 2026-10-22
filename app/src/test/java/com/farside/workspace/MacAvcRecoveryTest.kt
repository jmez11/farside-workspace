package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacAvcRecoveryTest {
    private fun nal(type:Int)=byteArrayOf(0,0,0,1,type.toByte(),42)
    @Test fun damagedReferenceHoldsCleanImageUntilIdr() {
        val recovery=MacAvcRecovery()
        assertFalse(recovery.accept(nal(1)))
        assertTrue(recovery.accept(nal(5)))
        assertTrue(recovery.accept(nal(1)))
        recovery.lost()
        repeat(100) {assertFalse(recovery.accept(nal(1)))}
        assertFalse(recovery.accept(nal(7)+nal(8)))
        assertTrue(recovery.accept(nal(7)+nal(8)+nal(5)))
        assertTrue(recovery.accept(nal(1)))
        assertEquals(102L,recovery.skipped)
    }
    @Test fun lossRequestsArePromptButNeverFloodOrRepeatOnIdle() {
        val requests=MacKeyframeRequests()
        assertTrue(requests.due(0,false,false))
        assertFalse(requests.due(499,false,false))
        assertTrue(requests.due(500,false,false))
        assertFalse(requests.due(1000,true,false))
        assertTrue(requests.due(1001,true,true))
        repeat(499) {assertFalse(requests.due(1002L+it,true,true))}
        assertTrue(requests.due(1501,true,true))
        assertFalse(requests.due(4000,true,false))
    }
}
