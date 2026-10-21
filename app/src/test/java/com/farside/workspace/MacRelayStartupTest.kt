package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MacRelayStartupTest {
    @Test fun acceptsOnlyReadyWithAValidPort() {
        assertEquals(65432,MacRelayStartup.port({"READY 65432"},{fail("Aborted ready relay")}))
        for(line in listOf(null,"ERROR","READY 0","READY 65536")) {
            val aborted=CountDownLatch(1)
            try { MacRelayStartup.port({line},{aborted.countDown()});fail("Accepted $line") }
            catch(_:IOException) { assertTrue(aborted.await(2,TimeUnit.SECONDS)) }
        }
    }
    @Test fun stalledStartupReturnsEvenIfClosingTheSshChannelWouldBlock() {
        val aborted=CountDownLatch(1);val allowClose=CountDownLatch(1)
        try {
            try {
                MacRelayStartup.port({CountDownLatch(1).await();null},{aborted.countDown();allowClose.await()},50)
                fail("Stalled relay accepted")
            } catch(_:IOException) { assertTrue(aborted.await(2,TimeUnit.SECONDS)) }
        } finally { allowClose.countDown() }
    }
}
