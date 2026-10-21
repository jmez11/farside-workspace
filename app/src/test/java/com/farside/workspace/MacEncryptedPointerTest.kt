package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

class MacEncryptedPointerTest {
    @Test fun postToggleKeyWrapsSentinelButtonsAndClampedScreenPixels() {
        val key=ByteArray(16) { it.toByte() }
        val layer=MacHevcRecordLayer(key,ByteArray(16))
        val message=layer.pointerEvent(3000,-5,4,2560,1600)
        assertEquals(18,message.size)
        assertArrayEquals(byteArrayOf(0x10,0),message.copyOfRange(0,2))
        val plain=Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES")) }.doFinal(message.copyOfRange(2,18))
        assertArrayEquals(ByteArray(10),plain.copyOfRange(0,10))
        assertEquals(255,plain[10].toInt() and 255)
        assertEquals(2,plain[11].toInt())
        assertEquals(2559,ByteBuffer.wrap(plain,12,2).short.toInt() and 65535)
        assertEquals(0,ByteBuffer.wrap(plain,14,2).short.toInt())
        assertEquals(0L,layer.sendSeq)
    }
    @Test fun middleAndWheelMasksUseAppleButtonOrder() {
        val key=ByteArray(16)
        val layer=MacHevcRecordLayer(key,ByteArray(16))
        val decrypt=Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES")) }
        val message=layer.pointerEvent(10,20,2 or 8,100,100)
        assertEquals(4 or 8,decrypt.doFinal(message.copyOfRange(2,18))[11].toInt())
    }
}
