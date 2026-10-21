package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class MacAvcAssemblerTest {
    @Test fun fragmentedPictureRequiresContiguousSequence() {
        val parser=MacAvcAssembler()
        assertNull(parser.accept(byteArrayOf(0x7c,0x85.toByte(),11),65535,9,false))
        assertArrayEquals(byteArrayOf(0,0,0,1,0x65,11,12),parser.accept(byteArrayOf(0x7c,0x45,12),0,9,true))
        assertNull(parser.accept(byteArrayOf(0x7c,0x85.toByte(),11),1,10,false))
        assertNull(parser.accept(byteArrayOf(0x7c,0x45,12),3,10,true))
        assertArrayEquals(byteArrayOf(0,0,0,1,0x61,13),parser.accept(byteArrayOf(0x61,13),4,11,true))
    }
    @Test fun incompleteFragmentCannotBecomeAnotherPicture() {
        val parser=MacAvcAssembler()
        parser.accept(byteArrayOf(0x7c,0x85.toByte(),11),1,9,false)
        assertNull(parser.accept(byteArrayOf(0x7c,0x45,12),2,10,true))
        assertNull(MacAvcStream.parseConfig(byteArrayOf(0x92.toByte(),0,0,0,8,97,118,99,67)))
    }
}
