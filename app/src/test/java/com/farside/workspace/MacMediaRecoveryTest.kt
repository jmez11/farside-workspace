package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacMediaRecoveryTest {
    @Test fun startupAndHealthyProgressDoNotRefresh() {
        val r=MacMediaRecovery()
        assertNull(r.poll(100000,0))
        assertNull(r.poll(100001,1))
        assertNull(r.poll(103000,1))
        assertNull(r.poll(103001,2))
        assertNull(r.poll(106000,2))
    }
    @Test fun stallEscalatesWithoutFloodAndRepeatsOnlyMedia() {
        val r=MacMediaRecovery();r.poll(0,1)
        assertEquals(MacMediaRecovery.Action.KEYFRAME,r.poll(3000,1))
        assertNull(r.poll(7999,1))
        assertEquals(MacMediaRecovery.Action.MEDIA_OFFER,r.poll(8000,1))
        assertEquals(MacMediaRecovery.Action.DISPLAY,r.poll(15000,1))
        assertNull(r.poll(29999,1))
        assertEquals(MacMediaRecovery.Action.MEDIA_OFFER,r.poll(30000,1))
    }
    @Test fun newFrameResetsEscalation() {
        val r=MacMediaRecovery();r.poll(0,1);r.poll(3000,1);r.poll(8000,1)
        assertNull(r.poll(9000,2))
        assertNull(r.poll(11999,2))
        assertEquals(MacMediaRecovery.Action.KEYFRAME,r.poll(12000,2))
    }
    @Test fun partialStartupArmsRecoveryButNoMediaDoesNot() {
        val r=MacMediaRecovery()
        assertNull(r.poll(100000,0))
        assertNull(r.poll(100100,0,true))
        assertNull(r.poll(103099,0,true))
        assertEquals(MacMediaRecovery.Action.KEYFRAME,r.poll(103100,0,true))
        assertNull(r.poll(104000,1,true))
    }
}
