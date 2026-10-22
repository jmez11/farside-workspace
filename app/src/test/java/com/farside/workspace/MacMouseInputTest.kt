package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacMouseInputTest {
    @Test fun dragHoldsUntilExplicitReleaseAndKeepsOtherButtons() {
        val masks=mutableListOf<Int>();val input=MacMouseInput { _,_,mask -> masks+=mask }
        input.mouse(10,10,0x9000);input.mouse(20,20,0x0800);input.mouse(30,30,0x0800)
        input.mouse(30,30,0xa000);input.mouse(30,30,0x1000);input.mouse(30,30,0x0800)
        input.mouse(30,30,0x2000)
        assertEquals(listOf(1,1,1,5,4,0),masks)
    }
    @Test fun identicalHoverIsDeduplicatedWithoutDroppingDragEdgesOrWheelTicks() {
        val sent=mutableListOf<Triple<Int,Int,Int>>()
        val input=MacMouseInput {x,y,mask ->sent+=Triple(x,y,mask)}
        input.mouse(10,20,0x0800)
        repeat(100) {input.mouse(10,20,0x0800)}
        input.mouse(10,20,0x9000)
        repeat(100) {input.mouse(10,20,0x0800)}
        input.mouse(11,20,0x0800);input.mouse(11,20,0x1000)
        repeat(2) {input.mouse(11,20,0x0200)}
        assertEquals(listOf(0,1,1,0,8,0,8,0),sent.map {it.third})
        assertEquals(listOf(10,10,11,11,11,11,11,11),sent.map {it.first})
    }
    @Test fun scrollingPreservesHeldButtonAndExplicitPointerSynchronizesState() {
        val masks=mutableListOf<Int>();val input=MacMouseInput { _,_,mask -> masks+=mask }
        input.pointer(1,1,1);input.mouse(1,1,0x0200);input.mouse(1,1,0x0300)
        input.mouse(1,1,0x0400);input.mouse(1,1,0x0500);input.mouse(1,1,0x1000)
        assertEquals(listOf(1,9,1,17,1,65,1,33,1,0),masks)
    }
}
