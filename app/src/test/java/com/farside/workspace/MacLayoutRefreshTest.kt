package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacLayoutRefreshTest {
    @Test fun firstLayoutAndSameSizeSessionChangesRefreshWithoutAcknowledgementLoops() {
        val state=MacLayoutRefresh()
        val layout=byteArrayOf(7,-128,4,56,1)
        assertTrue(state.changed(layout))
        assertFalse(state.changed(layout.copyOf()))
        val newSession=layout.copyOf().apply { this[4]=2 }
        assertTrue(state.changed(newSession))
        assertFalse(state.changed(newSession))
        assertTrue(state.changed(layout))
    }
}
