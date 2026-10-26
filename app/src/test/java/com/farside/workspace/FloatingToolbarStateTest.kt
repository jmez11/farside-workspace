package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class FloatingToolbarStateTest {
    @Test fun collapseChoosesSideFromMidpointAndRetainsItUntilNextCollapse() {
        val state = FloatingToolbarState()
        state.collapse(20f)
        assertFalse(state.dockRight)
        state.restore()
        state.offsetX = 100f
        state.collapse(30f)
        assertTrue(state.dockRight)
        state.dragNub(40f, 400f)
        assertTrue(state.dockRight)
        state.restore()
        assertEquals(70f, state.offsetY, 0f)
        assertEquals(100f, state.offsetX, 0f)
        state.offsetX = -100f
        state.collapse(70f)
        assertFalse(state.dockRight)
    }
    @Test fun expandedDragCannotLeaveTheWindowOrAccumulateOffscreenOvershoot() {
        val state = FloatingToolbarState()
        state.dragExpanded(-5000f, -5000f, 1000f, 700f, 300f, 60f)
        assertEquals(-350f, state.offsetX, 0f)
        assertEquals(0f, state.offsetY, 0f)
        state.dragExpanded(10f, 10f, 1000f, 700f, 300f, 60f)
        assertEquals(-340f, state.offsetX, 0f)
        assertEquals(10f, state.offsetY, 0f)
        state.dragExpanded(5000f, 5000f, 1000f, 700f, 300f, 60f)
        assertEquals(350f, state.offsetX, 0f)
        assertEquals(640f, state.offsetY, 0f)
        state.dragExpanded(100f, 100f, 200f, 30f, 300f, 60f)
        assertEquals(0f, state.offsetX, 0f)
        assertEquals(0f, state.offsetY, 0f)
    }
    @Test fun restoreUsesMovedNubHeightAndPreservesHorizontalPlacement() {
        val state = FloatingToolbarState(16f)
        state.offsetX = 100f
        state.offsetY = 150f
        state.collapse(150f)
        repeat(10) { state.dragNub(10f, 500f) }
        assertEquals(250f, state.nubY, 0f)
        state.restore()
        assertFalse(state.collapsed)
        assertEquals(100f, state.offsetX, 0f)
        assertEquals(250f, state.offsetY, 0f)
        state.collapse(state.offsetY)
        state.dragNub(-50f, 500f)
        state.restore()
        assertEquals(200f, state.offsetY, 0f)
        assertEquals(100f, state.offsetX, 0f)
    }
    @Test fun nubClampsVerticallyToWindow() {
        val state = FloatingToolbarState()
        state.collapse(10f)
        state.dragNub(900f, 400f)
        assertEquals(400f, state.nubY, 0f)
        state.dragNub(-900f, 400f)
        assertEquals(0f, state.nubY, 0f)
        assertFalse(FloatingToolbarState().collapsed)
        assertEquals(0f, FloatingToolbarState().offsetX, 0f)
    }
}
