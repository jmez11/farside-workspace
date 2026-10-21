package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacAvcSourceSelectorTest {
    @Test fun missingPacketsRequestRecoveryButHealthyPicturesDoNot() {
        val assembler=MacAvcAssembler()
        repeat(3) { assertNotNull(assembler.accept(byteArrayOf(0x61,1),it,it.toLong(),true));assertFalse(assembler.takeLoss()) }
        assembler.accept(byteArrayOf(0x7c,0x81.toByte(),1),3,4,false)
        assertNull(assembler.accept(byteArrayOf(0x7c,0x41,2),5,4,true))
        assertTrue(assembler.takeLoss())
        assertFalse(assembler.takeLoss())
        assembler.reset()
        assertFalse(assembler.takeLoss())
    }
    @Test fun overlappingRetiredSourceCannotOverwriteCurrentDisplay() {
        val selector=MacAvcSourceSelector()
        assertTrue(selector.accept(10,true))
        assertTrue(selector.accept(10,false))
        assertTrue(selector.accept(20,true))
        assertFalse(selector.accept(10,false))
        assertFalse(selector.accept(10,true))
        assertEquals(20L,selector.active)
        assertTrue(selector.accept(20,false))
    }
    @Test fun unknownPacketsCannotSelectOrReplaceSource() {
        val selector=MacAvcSourceSelector()
        assertFalse(selector.accept(10,false))
        assertNull(selector.active)
        assertTrue(selector.accept(10,true))
        assertFalse(selector.accept(20,false))
        assertEquals(10L,selector.active)
        val assembler=MacAvcAssembler()
        assertNull(assembler.accept(byteArrayOf(0x61,1),1,10,false))
        assembler.reset()
        assertArrayEquals(byteArrayOf(0,0,0,1,0x61,2),assembler.accept(byteArrayOf(0x61,2),9,10,true))
    }
}
