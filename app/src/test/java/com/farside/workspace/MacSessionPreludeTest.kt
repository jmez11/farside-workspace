package com.farside.workspace

import java.io.DataInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class MacSessionPreludeTest {
    @Test fun absentPromptRestoresTimeout() {
        val changes=mutableListOf<Int>()
        val input=DataInputStream(object:InputStream(){ override fun read():Int=throw SocketTimeoutException() })
        assertNull(MacSessionPrelude.optionalHeader(input,60000,changes::add))
        assertEquals(listOf(500,60000),changes)
    }
    @Test fun headerConsumesOnlyTwoBytes() {
        val input=DataInputStream(byteArrayOf(0,74,0,1).inputStream())
        assertArrayEquals(byteArrayOf(0,74),MacSessionPrelude.optionalHeader(input,60000) {})
        assertEquals(1,input.readUnsignedShort())
    }
    @Test fun partialHeaderIsNotAbsentPrompt() {
        var reads=0
        val input=DataInputStream(object:InputStream(){ override fun read():Int=if(reads++==0) 0 else throw SocketTimeoutException() })
        try { MacSessionPrelude.optionalHeader(input,60000) {};fail("Partial header accepted as absence") }
        catch(_:SocketTimeoutException) { }
    }
}
