package com.farside.workspace

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class MacRsaAuthenticationTest {
    companion object { private val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun response(result:Int):ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeInt(6+pair.public.encoded.size);writeShort(0x0100);writeInt(pair.public.encoded.size);write(pair.public.encoded)
            writeInt(0);writeInt(result)
        }
    }.toByteArray()
    @Test fun serverCanDecryptEnvelopeAndCredentialsAndUseReturnedWrapKey() {
        val sent=ByteArrayOutputStream()
        val wrap=MacRsaAuthentication.authenticate(DataInputStream(response(0).inputStream()),DataOutputStream(sent),"Full Test Name","test password")
        val input=DataInputStream(sent.toByteArray().inputStream())
        assertEquals(33,input.readUnsignedByte());assertEquals(10,input.readInt())
        val init=ByteArray(10).also(input::readFully)
        assertArrayEquals(byteArrayOf(1,0,82,83,65,49,0,0,0,0),init)
        val size=input.readInt();assertEquals(6+2+128+2+256,size)
        assertArrayEquals(byteArrayOf(1,0,82,83,65,49),ByteArray(6).also(input::readFully))
        assertEquals(1,input.readUnsignedShort())
        val credentials=ByteArray(128).also(input::readFully)
        assertEquals(1,input.readUnsignedShort())
        val envelope=ByteArray(256).also(input::readFully)
        assertEquals(0,input.available())
        val key=Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE,pair.private) }.doFinal(envelope)
        assertArrayEquals(wrap,key)
        val plain=Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES")) }.doFinal(credentials)
        assertEquals("Full Test Name",plain.copyOfRange(0,64).takeWhile { it!=0.toByte() }.toByteArray().toString(Charsets.UTF_8))
        assertEquals("test password",plain.copyOfRange(64,128).takeWhile { it!=0.toByte() }.toByteArray().toString(Charsets.UTF_8))
    }
    @Test fun rejectionDoesNotAttemptAnotherAuthentication() {
        val sent=ByteArrayOutputStream()
        try {
            MacRsaAuthentication.authenticate(DataInputStream(response(1).inputStream()),DataOutputStream(sent),"test","test")
            fail("Rejected RSA1 authentication accepted")
        } catch(_:VncAuthenticationException) {}
        assertEquals(15+4+394,sent.size())
    }
    @Test fun oversizedKeyResponseStopsBeforeCredentialPacket() {
        val sent=ByteArrayOutputStream()
        val malformed=ByteArrayOutputStream().also { DataOutputStream(it).writeInt(20000) }.toByteArray()
        try { MacRsaAuthentication.authenticate(DataInputStream(malformed.inputStream()),DataOutputStream(sent),"test","test");fail() }
        catch(_:IllegalArgumentException) {}
        assertEquals(15,sent.size())
    }
}
