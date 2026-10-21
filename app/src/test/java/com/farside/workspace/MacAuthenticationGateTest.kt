package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacAuthenticationGateTest {
    @Test(timeout=1000) fun successfulStagesHaveNoArtificialCooldown() {
        var checks=0
        val gate=MacAuthenticationGate { checks++;false }
        repeat(100) { gate.await() }
        assertEquals(100,checks)
    }
    @Test fun cancellationStopsTheNextStage() {
        var cancelled=false
        val gate=MacAuthenticationGate { cancelled }
        gate.await();cancelled=true
        try { gate.await();fail("Cancelled authentication proceeded") }
        catch(_:IllegalStateException) { }
    }
}
