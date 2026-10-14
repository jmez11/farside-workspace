package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MacHevcRecordLayerTest {

    @Test
    fun testRekeyUnwrap() {
        val random = SecureRandom()
        val wrapKey = ByteArray(16).also { random.nextBytes(it) }
        val contentKey = ByteArray(16).also { random.nextBytes(it) }
        val contentIv = ByteArray(16).also { random.nextBytes(it) }

        val ecbEncrypt = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"))
        }
        val encKey = ecbEncrypt.doFinal(contentKey)
        val encIv = ecbEncrypt.doFinal(contentIv)

        val payload = ByteArray(36)
        payload[0] = 0; payload[1] = 0; payload[2] = 0; payload[3] = 42 // generation 42
        System.arraycopy(encKey, 0, payload, 4, 16)
        System.arraycopy(encIv, 0, payload, 20, 16)

        val recordLayer = MacHevcRecordLayer.unwrapRekey(wrapKey, payload)
        assertEquals(42L, recordLayer.generation)
        assertEquals(0L, recordLayer.sendSeq)
        assertEquals(0L, recordLayer.recvSeq)
    }

    @Test
    fun testRecordEncryptDecryptRoundTrip() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val sender = MacHevcRecordLayer(key.clone(), iv.clone())
        val receiver = MacHevcRecordLayer(key.clone(), iv.clone())

        val message = "Hello Apple VNC Record Layer".toByteArray(Charsets.UTF_8)
        val outer = sender.encryptRecord(message)

        val cipherLen = ((outer[0].toInt() and 0xff) shl 8) or (outer[1].toInt() and 0xff)
        assertEquals(outer.size - 2, cipherLen)
        assertEquals(0, cipherLen % 16)
        assertEquals(1L, sender.sendSeq)

        val ciphertext = outer.copyOfRange(2, outer.size)
        val decrypted = receiver.decryptRecord(ciphertext)

        assertArrayEquals(message, decrypted)
        assertEquals(1L, receiver.recvSeq)
    }

    @Test
    fun testMultiRecordSequenceAndCbcChaining() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val sender = MacHevcRecordLayer(key.clone(), iv.clone())
        val receiver = MacHevcRecordLayer(key.clone(), iv.clone())

        val messages = (1..10).map { "Record $it with variable payload ${random.nextInt()}".toByteArray() }

        for ((idx, msg) in messages.withIndex()) {
            val outer = sender.encryptRecord(msg)
            assertEquals((idx + 1).toLong(), sender.sendSeq)

            val ciphertext = outer.copyOfRange(2, outer.size)
            val decrypted = receiver.decryptRecord(ciphertext)
            assertArrayEquals(msg, decrypted)
            assertEquals((idx + 1).toLong(), receiver.recvSeq)
        }
    }

    @Test
    fun testIntegrityFailureOnCorruptCiphertext() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val sender = MacHevcRecordLayer(key.clone(), iv.clone())
        val receiver = MacHevcRecordLayer(key.clone(), iv.clone())

        val outer = sender.encryptRecord("Sensitive data".toByteArray())
        val ciphertext = outer.copyOfRange(2, outer.size)

        // Corrupt one bit
        ciphertext[ciphertext.size - 5] = (ciphertext[ciphertext.size - 5].toInt() xor 0x01).toByte()

        assertThrows(MacHevcIntegrityException::class.java) {
            receiver.decryptRecord(ciphertext)
        }
    }

    @Test
    fun testIntegrityFailureOnSequenceMismatch() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val sender = MacHevcRecordLayer(key.clone(), iv.clone())
        val receiver = MacHevcRecordLayer(key.clone(), iv.clone())

        val outer = sender.encryptRecord("Seq test".toByteArray())
        val ciphertext = outer.copyOfRange(2, outer.size)

        receiver.recvSeq = 42L // sequence desync

        assertThrows(MacHevcIntegrityException::class.java) {
            receiver.decryptRecord(ciphertext)
        }
    }

    @Test
    fun testProtocolFailureOnInvalidCiphertextLength() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val receiver = MacHevcRecordLayer(key, iv)

        assertThrows(MacHevcProtocolException::class.java) {
            receiver.decryptRecord(ByteArray(15))
        }

        assertThrows(MacHevcProtocolException::class.java) {
            receiver.decryptRecord(ByteArray(0))
        }
    }

    @Test
    fun testStreamingInputAndOutputStreamAcrossRecords() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val sender = MacHevcRecordLayer(key.clone(), iv.clone())
        val receiver = MacHevcRecordLayer(key.clone(), iv.clone())

        val pipeOut = java.io.ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(pipeOut)
        val streamOut = sender.createOutputStream(dataOut)

        val largePayload = ByteArray(100_000).also { random.nextBytes(it) }
        var written = 0
        while (written < largePayload.size) {
            val chunk = minOf(7319, largePayload.size - written)
            streamOut.write(largePayload, written, chunk)
            written += chunk
        }
        streamOut.flush()

        val pipeIn = java.io.ByteArrayInputStream(pipeOut.toByteArray())
        val dataIn = java.io.DataInputStream(pipeIn)
        val streamIn = receiver.createInputStream(dataIn)

        val received = ByteArray(largePayload.size)
        var totalRead = 0
        while (totalRead < received.size) {
            val n = streamIn.read(received, totalRead, received.size - totalRead)
            if (n < 0) break
            totalRead += n
        }

        assertEquals(largePayload.size, totalRead)
        assertArrayEquals(largePayload, received)
    }
}
