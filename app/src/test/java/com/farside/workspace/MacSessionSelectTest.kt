package com.farside.workspace

import java.io.DataInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class MacSessionSelectTest {
    @Test fun savedFullAliasAndUtf8PasswordSurviveEncryptedLogin() {
        val key=ByteArray(16) { it.toByte() }
        val wire=MacSessionSelect.virtualLogin("Test Account","test-\u00e9",key)
        DataInputStream(wire.inputStream()).use { input ->
            assertEquals(200,input.readUnsignedShort());assertEquals(2,input.readUnsignedShort())
            assertEquals(0,input.readUnsignedShort());assertEquals(0,input.readUnsignedShort())
            assertEquals(2,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
            assertArrayEquals(ByteArray(64),ByteArray(64).also(input::readFully))
            val cipher=Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES")) }
            fun value()=String(cipher.doFinal(ByteArray(64).also(input::readFully)),Charsets.UTF_8).trimEnd('\u0000')
            assertEquals("Test Account",value());assertEquals("test-\u00e9",value());assertEquals(0,input.available())
        }
    }
    @Test fun overlongAndEmbeddedNulIdentitiesFailWithoutTruncatingCredentials() {
        listOf("x".repeat(64),"\u00e9".repeat(32),"user\u0000alias").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { MacSessionSelect.virtualLogin(name,"password",ByteArray(16)) }
        }
        assertThrows(IllegalArgumentException::class.java) { MacSessionSelect.virtualLogin("short-user","p".repeat(64),ByteArray(16)) }
        val console=MacSessionSelect.consoleLogin(0,"Test Account")
        assertEquals(74,console.size);assertEquals(0,console[8].toInt())
        assertEquals("Test Account",String(console.copyOfRange(10,74)).trimEnd('\u0000'))
    }
}
