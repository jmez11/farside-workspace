package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacVideoStartupTest {
    @Test fun authenticatedVideoGetsDecoderWarmupWithoutWaitingAfterFirstFrame() {
        var time=0L
        assertTrue(MacVideoStartup.await(frame={time>=4800},progress={time>=2760},now={time},pause={time+=it}))
        assertEquals(4800L,time)
    }
    @Test fun missingMediaStillFallsBackQuickly() {
        var time=0L
        assertFalse(MacVideoStartup.await(frame={false},progress={false},now={time},pause={time+=it}))
        assertEquals(3500L,time)
    }
    @Test fun partialMediaCannotExtendTheDeadlineForever() {
        var time=0L
        assertFalse(MacVideoStartup.await(frame={false},progress={true},now={time},pause={time+=it}))
        assertEquals(10000L,time)
    }
    @Test fun closingTheWindowCancelsTheLongerWarmup() {
        var time=0L
        assertFalse(MacVideoStartup.await(frame={false},progress={true},now={time},pause={time+=it},cancelled={time>=1000}))
        assertEquals(1000L,time)
    }
}
