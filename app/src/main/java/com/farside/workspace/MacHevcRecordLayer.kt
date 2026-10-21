package com.farside.workspace

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MacHevcIntegrityException(message: String) : SecurityException(message)
class MacHevcProtocolException(message: String) : IOException(message)

/**
 * Apple VNC High-Performance Extension (apple_vnc_rfc) AES-128-CBC Record Layer.
 *
 * Framed secure transport activated by the 0x44f rekey message (§6.3, §6.4).
 * Outer wire: u16 ciphertext_len || ciphertext (multiple of 16).
 * Plaintext:  u16 body_len || body || filler || byte[20] SHA-1 integrity trailer.
 * Integrity:  SHA-1( u32_be(seq) || plaintext[0 : ciphertext_len - 20] ).
 */
class MacHevcRecordLayer(
    contentKey: ByteArray,
    contentIv: ByteArray,
    val generation: Long = 1L
) {
    init {
        require(contentKey.size == 16) { "AES-128 content key must be 16 bytes" }
        require(contentIv.size == 16) { "AES-128 CBC IV must be 16 bytes" }
    }

    private val sendCipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), IvParameterSpec(contentIv))
    }
    private val recvCipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), IvParameterSpec(contentIv))
    }

    private val random = SecureRandom()
    private val pointerCipher=Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE,SecretKeySpec(contentKey,"AES"))
    }
    var sendSeq: Long = 0L
    var recvSeq: Long = 0L

    /** Apple HP msg 0x10 wraps pointer events under the post-toggle content key. */
    @Synchronized
    fun pointerEvent(x:Int,y:Int,rfbButtons:Int,width:Int,height:Int):ByteArray {
        val appleButtons=(rfbButtons and 0xf9) or ((rfbButtons and 2) shl 1) or ((rfbButtons and 4) ushr 1)
        val plain=java.nio.ByteBuffer.allocate(16).apply {
            position(10);put(0xff.toByte());put(appleButtons.toByte())
            putShort(x.coerceIn(0,(width-1).coerceIn(0,65535)).toShort())
            putShort(y.coerceIn(0,(height-1).coerceIn(0,65535)).toShort())
        }.array()
        return byteArrayOf(0x10,0)+pointerCipher.doFinal(plain)
    }

    /**
     * Encrypt a message body into an outer record (u16 ciphertext_len || ciphertext).
     */
    @Synchronized
    fun encryptRecord(body: ByteArray): ByteArray {
        val bodyLen = body.size
        require(bodyLen <= 0xffff) { "Body exceeds 64KB maximum record size" }
        val headerAndTrailer = 2 + bodyLen + 20
        val rem = headerAndTrailer % 16
        val fillerLen = if (rem == 0) 0 else 16 - rem
        val totalLen = headerAndTrailer + fillerLen

        val plaintext = ByteArray(totalLen)
        plaintext[0] = ((bodyLen ushr 8) and 0xff).toByte()
        plaintext[1] = (bodyLen and 0xff).toByte()
        System.arraycopy(body, 0, plaintext, 2, bodyLen)
        if (fillerLen > 0) {
            val filler = ByteArray(fillerLen).also { random.nextBytes(it) }
            System.arraycopy(filler, 0, plaintext, 2 + bodyLen, fillerLen)
        }

        // Integrity: SHA-1( u32_be(sendSeq) || plaintext[0 : totalLen - 20] )
        val md = MessageDigest.getInstance("SHA-1")
        val seqBytes = ByteArray(4)
        seqBytes[0] = ((sendSeq ushr 24) and 0xff).toByte()
        seqBytes[1] = ((sendSeq ushr 16) and 0xff).toByte()
        seqBytes[2] = ((sendSeq ushr 8) and 0xff).toByte()
        seqBytes[3] = (sendSeq and 0xff).toByte()
        md.update(seqBytes)
        md.update(plaintext, 0, totalLen - 20)
        val integrity = md.digest()
        System.arraycopy(integrity, 0, plaintext, totalLen - 20, 20)

        val ciphertext = sendCipher.update(plaintext) ?: ByteArray(0)
        check(ciphertext.size == totalLen) { "AES CBC encrypt output size mismatch" }
        sendSeq++

        val outer = ByteArray(2 + ciphertext.size)
        outer[0] = ((ciphertext.size ushr 8) and 0xff).toByte()
        outer[1] = (ciphertext.size and 0xff).toByte()
        System.arraycopy(ciphertext, 0, outer, 2, ciphertext.size)
        return outer
    }

    /**
     * Decrypt an outer record's ciphertext into the higher-level message body.
     */
    @Synchronized
    fun decryptRecord(ciphertext: ByteArray): ByteArray {
        if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) {
            throw MacHevcProtocolException("Ciphertext length ${ciphertext.size} not non-zero multiple of 16")
        }
        val plaintext = recvCipher.update(ciphertext) ?: ByteArray(0)
        if (plaintext.size != ciphertext.size) {
            throw MacHevcProtocolException("Decrypted plaintext size ${plaintext.size} != ciphertext size ${ciphertext.size}")
        }
        val md = MessageDigest.getInstance("SHA-1")
        val seqBytes = ByteArray(4)
        seqBytes[0] = ((recvSeq ushr 24) and 0xff).toByte()
        seqBytes[1] = ((recvSeq ushr 16) and 0xff).toByte()
        seqBytes[2] = ((recvSeq ushr 8) and 0xff).toByte()
        seqBytes[3] = (recvSeq and 0xff).toByte()
        md.update(seqBytes)
        md.update(plaintext, 0, plaintext.size - 20)
        val expectedIntegrity = md.digest()

        val actualIntegrity = ByteArray(20)
        System.arraycopy(plaintext, plaintext.size - 20, actualIntegrity, 0, 20)

        if (!MessageDigest.isEqual(expectedIntegrity, actualIntegrity)) {
            throw MacHevcIntegrityException("Record integrity verification failed at recvSeq $recvSeq")
        }
        recvSeq++

        val bodyLen = ((plaintext[0].toInt() and 0xff) shl 8) or (plaintext[1].toInt() and 0xff)
        if (2 + bodyLen + 20 > plaintext.size) {
            throw MacHevcProtocolException("Invalid body length $bodyLen in record of size ${plaintext.size}")
        }

        val body = ByteArray(bodyLen)
        System.arraycopy(plaintext, 2, body, 0, bodyLen)
        return body
    }

    fun writeRecord(out: DataOutputStream, body: ByteArray) {
        val outer = encryptRecord(body)
        out.write(outer)
        out.flush()
    }

    fun readRecord(input: DataInputStream): ByteArray {
        val ciphertextLen = input.readUnsignedShort()
        if (ciphertextLen == 0 || ciphertextLen % 16 != 0) {
            throw MacHevcProtocolException("Received invalid ciphertext length $ciphertextLen")
        }
        val ciphertext = ByteArray(ciphertextLen)
        input.readFully(ciphertext)
        return decryptRecord(ciphertext)
    }

    /**
     * Creates an InputStream that reassembles continuous higher-level RFB stream
     * by decrypting successive records (§6.4.6).
     */
    fun createInputStream(rawInput: DataInputStream): InputStream {
        return object : InputStream() {
            private var currentRecord: ByteArray? = null
            private var recordPos = 0

            override fun read(): Int {
                if (!ensureData()) return -1
                return currentRecord!![recordPos++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!ensureData()) return -1
                val available = currentRecord!!.size - recordPos
                val toRead = minOf(len, available)
                System.arraycopy(currentRecord!!, recordPos, b, off, toRead)
                recordPos += toRead
                return toRead
            }

            private fun ensureData(): Boolean {
                while (currentRecord == null || recordPos >= currentRecord!!.size) {
                    val rec = try {
                        readRecord(rawInput)
                    } catch (e: java.io.EOFException) {
                        return false
                    }
                    currentRecord = rec
                    recordPos = 0
                    if (rec.isNotEmpty()) return true
                }
                return true
            }
        }
    }

    /**
     * Creates an OutputStream that encrypts higher-level RFB payloads into records.
     */
    fun createOutputStream(rawOutput: DataOutputStream): OutputStream {
        return object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                val chunk = ByteArray(len)
                System.arraycopy(b, off, chunk, 0, len)
                writeRecord(rawOutput, chunk)
            }

            override fun flush() {
                rawOutput.flush()
            }
        }
    }

    companion object {
        /**
         * Unwrap 36-byte 0x44f Rekey payload using AES-128-ECB single-block decryption (§6.2.1).
         * Framing: u32 generation || 16B encrypted_key || 16B encrypted_iv.
         */
        fun unwrapRekey(wrapKey: ByteArray, payload: ByteArray): MacHevcRecordLayer {
            require(wrapKey.size == 16) { "Wrap key must be 16 bytes" }
            require(payload.size == 36) { "Rekey payload must be exactly 36 bytes, got ${payload.size}" }

            val generation = ((payload[0].toLong() and 0xff) shl 24) or
                    ((payload[1].toLong() and 0xff) shl 16) or
                    ((payload[2].toLong() and 0xff) shl 8) or
                    (payload[3].toLong() and 0xff)

            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"))
            }

            val encryptedKey = payload.copyOfRange(4, 20)
            val encryptedIv = payload.copyOfRange(20, 36)

            val contentKey = ecb.doFinal(encryptedKey)
            val contentIv = ecb.doFinal(encryptedIv)

            return MacHevcRecordLayer(contentKey, contentIv, generation)
        }
    }
}
