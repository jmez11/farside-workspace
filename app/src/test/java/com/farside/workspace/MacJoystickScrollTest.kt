package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacJoystickScrollTest {
    @Test fun lightTiltsHaveASubstantialFloorBeyondTheDeadzone() {
        assertEquals(0,MacJoystickScroll.steps(.2f))
        assertEquals(600,MacJoystickScroll.steps(.21f))
        assertEquals(600,MacJoystickScroll.steps(-.3f))
        assertTrue(MacJoystickScroll.delayMs(.3f,0f) in 70L..74L)
    }
    @Test fun strongTiltsAccelerateWithBoundedBurstsAndIntervals() {
        assertEquals(800,MacJoystickScroll.steps(.6f))
        assertEquals(1600,MacJoystickScroll.steps(1f))
        assertEquals(1600,MacJoystickScroll.steps(-2f))
        assertEquals(20L,MacJoystickScroll.delayMs(1f,0f))
        assertEquals(20L,MacJoystickScroll.delayMs(1f,1f))
        assertEquals(0,MacJoystickScroll.steps(Float.NaN))
        assertEquals(0,MacJoystickScroll.steps(Float.POSITIVE_INFINITY))
    }
}
