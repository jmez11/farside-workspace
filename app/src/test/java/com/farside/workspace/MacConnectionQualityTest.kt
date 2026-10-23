package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacConnectionQualityTest {
    @Test fun distinguishesStartupStallRecentLossAndFallback() {
        assertEquals(MacConnectionQuality.State.STARTING,MacConnectionQuality().state)
        val q=MacConnectionQuality(frames=10,frameAgeMs=100)
        assertEquals(MacConnectionQuality.State.RECEIVING,q.state)
        assertEquals(MacConnectionQuality.State.DEGRADED,q.copy(recentGap=true).state)
        assertEquals(MacConnectionQuality.State.STALLED,q.copy(frameAgeMs=3000).state)
        assertEquals(MacConnectionQuality.State.DEGRADED,MacConnectionQuality(gaps=2).state)
        assertEquals(MacConnectionQuality.State.FALLBACK,q.copy(fallback=true).state)
    }
    @Test fun oldLossDoesNotKeepHealthyStreamDegraded() {
        assertEquals(MacConnectionQuality.State.RECEIVING,MacConnectionQuality(frames=20,frameAgeMs=100,gaps=3,missing=5).state)
    }
}
