package com.farside.workspace
import org.junit.Assert.*
import org.junit.Test
class MacVideoSizeTest {
    @Test fun oddHorizonWindowAlignsBothAvcDimensions() {
        assertEquals(MacVideoSize(996,1052),MacVideoSize.fromWindow(997,1053))
        assertEquals(MacVideoSize(1518,984),MacVideoSize.fromWindow(1519,985))
        assertEquals(MacVideoSize(1280,800),MacVideoSize.fromWindow(1280,800))
    }
    @Test fun alignmentRetainsBounds() {
        assertEquals(MacVideoSize(200,8192),MacVideoSize.fromWindow(1,9999))
        for(i in 200..8192) assertEquals(0,MacVideoSize.fromWindow(i,i).height%2)
    }
}
