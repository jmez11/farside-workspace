package com.farside.workspace

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MacHevcSrtpException(message: String) : Exception(message)

/**
 * RFC 3711 SRTP Decryption Engine for Apple High-Performance Media Transport.
 *
 * Implements AES-256-CM keystream decryption and HMAC-SHA1-80 tag verification.
 * Master key (32 bytes) and master salt (14 bytes) are derived from in-band keying.
 */
class MacHevcSrtp(
    masterKey: ByteArray,
    masterSalt: ByteArray
) {
    init {
        require(masterKey.size == 32) { "Master key must be 32 bytes (AES-256), got ${masterKey.size}" }
        require(masterSalt.size == 14) { "Master salt must be 14 bytes, got ${masterSalt.size}" }
    }

    // Derived keys per RFC 3711 §4.3.1
    val cipherKey: ByteArray = deriveKey(masterKey, masterSalt, label = 0, length = 32)
    val authKey: ByteArray = deriveKey(masterKey, masterSalt, label = 1, length = 20)
    val salt: ByteArray = deriveKey(masterKey, masterSalt, label = 2, length = 14)

    // Per-SSRC state: SSRC -> SrtpStreamState (tracks sequence, ROC)
    class StreamState(
        var maxSeq: Int = -1,
        var roc: Long = 0L,
        var baseSeq: Int = -1
    ) {
        fun updateSeq(seq: Int): Long {
            if (maxSeq == -1) {
                baseSeq = seq
                maxSeq = seq
                return roc
            }
            val delta = seq - maxSeq
            if (delta in 1..32767) {
                // In-order forward
                maxSeq = seq
            } else if (delta < -32768) {
                // Rollover
                roc = (roc + 1) and 0xffffffffL
                maxSeq = seq
            } else if (delta in -32768..-1) {
                // Out-of-order within current window
            } else if (delta > 32767) {
                // Duplicate / out-of-order from previous rollover window
                if (roc > 0) return roc - 1
            }
            return roc
        }
    }

    private val streamStates = mutableMapOf<Long, StreamState>()

    /**
     * Decrypts an incoming SRTP packet and verifies its 80-bit HMAC-SHA1 tag.
     * Returns the decrypted RTP payload (excluding RTP header and auth tag).
     */
    fun decryptPacket(packet: ByteArray, offset: Int = 0, length: Int = packet.size): DecryptedRtpPacket {
        if (length < 12 + 10) {
            throw MacHevcSrtpException("Packet too short for SRTP: $length bytes")
        }

        val buf = ByteBuffer.wrap(packet, offset, length).order(ByteOrder.BIG_ENDIAN)
        val firstByte = buf.get().toInt() and 0xff
        val version = (firstByte ushr 6) and 0x03
        val padding = (firstByte ushr 5) and 0x01
        val extension = (firstByte ushr 4) and 0x01
        val csrcCount = firstByte and 0x0f

        val secondByte = buf.get().toInt() and 0xff
        val marker = (secondByte ushr 7) and 0x01
        val payloadType = secondByte and 0x7f

        val seq = buf.short.toInt() and 0xffff
        val timestamp = buf.int.toLong() and 0xffffffffL
        val ssrc = buf.int.toLong() and 0xffffffffL

        // Skip CSRC identifiers
        var headerLen = 12 + csrcCount * 4
        if (length < headerLen + 10) {
            throw MacHevcSrtpException("Packet too short for CSRC list: $length bytes")
        }

        // Handle RTP header extension if present
        if (extension == 1) {
            if (length < headerLen + 4 + 10) {
                throw MacHevcSrtpException("Packet too short for header extension")
            }
            buf.position(offset + headerLen)
            val extProfile = buf.short.toInt() and 0xffff
            val extLength = (buf.short.toInt() and 0xffff) * 4
            headerLen += 4 + extLength
            if (length < headerLen + 10) {
                throw MacHevcSrtpException("Packet too short for header extension body")
            }
        }

        val tagLen = 10
        val payloadLen = length - headerLen - tagLen
        if (payloadLen < 0) {
            throw MacHevcSrtpException("Negative payload length in SRTP packet")
        }

        val state = streamStates.getOrPut(ssrc) { StreamState() }
        val roc = state.updateSeq(seq)
        val index = ((roc shl 16) or seq.toLong()) and 0xffffffffffffL

        // 1. Verify HMAC-SHA1-80 tag
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(authKey, "HmacSHA1"))
        }
        mac.update(packet, offset, length - tagLen)
        val rocBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((roc and 0xffffffffL).toInt()).array()
        mac.update(rocBytes)
        val fullTag = mac.doFinal()

        val expectedTag = fullTag.copyOfRange(0, tagLen)
        val actualTag = packet.copyOfRange(offset + length - tagLen, offset + length)
        if (!MessageDigest.isEqual(expectedTag, actualTag)) {
            throw MacHevcSrtpException("SRTP HMAC-SHA1 integrity verification failed for SSRC 0x${java.lang.Long.toHexString(ssrc)} seq $seq")
        }

        // 2. AES-256-CTR Decryption
        // IV = (salt << 16) XOR (SSRC << 64) XOR (index << 16)
        val iv = ByteArray(16)
        System.arraycopy(salt, 0, iv, 0, 14)

        // XOR SSRC at bytes 4..7
        iv[4] = (iv[4].toInt() xor ((ssrc ushr 24) and 0xff).toInt()).toByte()
        iv[5] = (iv[5].toInt() xor ((ssrc ushr 16) and 0xff).toInt()).toByte()
        iv[6] = (iv[6].toInt() xor ((ssrc ushr 8) and 0xff).toInt()).toByte()
        iv[7] = (iv[7].toInt() xor (ssrc and 0xff).toInt()).toByte()

        // XOR 48-bit index at bytes 8..13
        iv[8] = (iv[8].toInt() xor ((index ushr 40) and 0xff).toInt()).toByte()
        iv[9] = (iv[9].toInt() xor ((index ushr 32) and 0xff).toInt()).toByte()
        iv[10] = (iv[10].toInt() xor ((index ushr 24) and 0xff).toInt()).toByte()
        iv[11] = (iv[11].toInt() xor ((index ushr 16) and 0xff).toInt()).toByte()
        iv[12] = (iv[12].toInt() xor ((index ushr 8) and 0xff).toInt()).toByte()
        iv[13] = (iv[13].toInt() xor (index and 0xff).toInt()).toByte()

        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        }

        val decryptedPayload = cipher.doFinal(packet, offset + headerLen, payloadLen)

        return DecryptedRtpPacket(
            version = version,
            payloadType = payloadType,
            marker = marker == 1,
            sequenceNumber = seq,
            timestamp = timestamp,
            ssrc = ssrc,
            payload = decryptedPayload
        )
    }

    companion object {
        /**
         * RFC 3711 §4.3.1 Key Derivation Function.
         * IV = (master_salt << 16) XOR (label << 48) XOR (index << 16)
         * Keystream generated via AES-ECB over counter blocks with IV prefix.
         */
        fun deriveKey(masterKey: ByteArray, masterSalt: ByteArray, label: Int, length: Int): ByteArray {
            val iv = ByteArray(16)
            System.arraycopy(masterSalt, 0, iv, 0, 14)
            // XOR label at byte 7
            iv[7] = (iv[7].toInt() xor (label and 0xff)).toByte()

            val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
            }

            val result = ByteArray(length)
            var generated = 0
            var counter = 0
            val block = ByteArray(16)

            while (generated < length) {
                System.arraycopy(iv, 0, block, 0, 14)
                block[14] = ((counter ushr 8) and 0xff).toByte()
                block[15] = (counter and 0xff).toByte()
                val out = cipher.doFinal(block)
                val toCopy = minOf(16, length - generated)
                System.arraycopy(out, 0, result, generated, toCopy)
                generated += toCopy
                counter++
            }
            return result
        }
    }
}

data class DecryptedRtpPacket(
    val version: Int,
    val payloadType: Int,
    val marker: Boolean,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
)

/**
 * RFC 3711 SRTCP Sender for Apple High-Performance Media Transport.
 * Encrypts RTCP compound feedback packets (RR, PLI, FIR) sent to UDP port 5900.
 */
class MacHevcSrtcpEncryptor(
    masterKey: ByteArray,
    masterSalt: ByteArray
) {
    init {
        require(masterKey.size == 32) { "Master key must be 32 bytes (AES-256), got ${masterKey.size}" }
        require(masterSalt.size == 14) { "Master salt must be 14 bytes, got ${masterSalt.size}" }
    }

    private val cipherKey = MacHevcSrtp.deriveKey(masterKey, masterSalt, label = 3, length = 32)
    private val authKey = MacHevcSrtp.deriveKey(masterKey, masterSalt, label = 4, length = 20)
    private val salt = MacHevcSrtp.deriveKey(masterKey, masterSalt, label = 5, length = 14)
    private var index = 0
    private val lock = Any()

    fun protect(rtcpPkt: ByteArray): ByteArray {
        require(rtcpPkt.size >= 8) { "RTCP packet too short: ${rtcpPkt.size}" }
        val currentIndex: Int
        synchronized(lock) {
            currentIndex = index
            index++
        }

        val hdr = rtcpPkt.copyOfRange(0, 8)
        val ssrc = ((hdr[4].toLong() and 0xff) shl 24) or
                   ((hdr[5].toLong() and 0xff) shl 16) or
                   ((hdr[6].toLong() and 0xff) shl 8) or
                   (hdr[7].toLong() and 0xff)

        val iv = ByteArray(16)
        System.arraycopy(salt, 0, iv, 0, 14)
        iv[4] = (iv[4].toInt() xor ((ssrc ushr 24).toInt() and 0xff)).toByte()
        iv[5] = (iv[5].toInt() xor ((ssrc ushr 16).toInt() and 0xff)).toByte()
        iv[6] = (iv[6].toInt() xor ((ssrc ushr 8).toInt() and 0xff)).toByte()
        iv[7] = (iv[7].toInt() xor (ssrc.toInt() and 0xff)).toByte()

        iv[10] = (iv[10].toInt() xor ((currentIndex ushr 24) and 0xff)).toByte()
        iv[11] = (iv[11].toInt() xor ((currentIndex ushr 16) and 0xff)).toByte()
        iv[12] = (iv[12].toInt() xor ((currentIndex ushr 8) and 0xff)).toByte()
        iv[13] = (iv[13].toInt() xor (currentIndex and 0xff)).toByte()

        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        }
        val ciphertext = if (rtcpPkt.size > 8) {
            cipher.doFinal(rtcpPkt, 8, rtcpPkt.size - 8)
        } else {
            ByteArray(0)
        }

        val eIndex = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt((0x80000000L or (currentIndex.toLong() and 0x7fffffffL)).toInt()).array()

        val body = ByteArray(8 + ciphertext.size + 4)
        System.arraycopy(hdr, 0, body, 0, 8)
        System.arraycopy(ciphertext, 0, body, 8, ciphertext.size)
        System.arraycopy(eIndex, 0, body, 8 + ciphertext.size, 4)

        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(authKey, "HmacSHA1"))
        }
        val fullTag = mac.doFinal(body)
        val tag = fullTag.copyOfRange(0, 10)

        val protected = ByteArray(body.size + 10)
        System.arraycopy(body, 0, protected, 0, body.size)
        System.arraycopy(tag, 0, protected, body.size, 10)
        return protected
    }
}

/**
 * RTCP Feedback Packet Builder (RFC 3550, RFC 4585, RFC 2032).
 */
object MacHevcRtcp {
    /** Receiver Report (RFC 3550 §6.4.2, PT=201). 8 bytes: header + sender SSRC. */
    fun buildRr(senderSsrc: Long): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x80.toByte()) // V=2, P=0, RC=0
        buf.put(201.toByte())  // PT=201 (RR)
        buf.putShort(1.toShort()) // length = 1 (1 32-bit word after header)
        buf.putInt(senderSsrc.toInt())
        return buf.array()
    }

    /** Picture Loss Indication (RFC 4585 §6.3.1, PT=206, FMT=1). 12 bytes. */
    fun buildPli(senderSsrc: Long, mediaSsrc: Long): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put((0x80 or 1).toByte()) // V=2, P=0, FMT=1 (PLI)
        buf.put(206.toByte())        // PT=206 (PSFB)
        buf.putShort(2.toShort())    // length = 2 words after header
        buf.putInt(senderSsrc.toInt())
        buf.putInt(mediaSsrc.toInt())
        return buf.array()
    }

    /** Legacy Full-INTRA-frame Request (RFC 2032 §5.2.1, PT=192). 8 bytes. */
    fun buildFirLegacy(targetSsrc: Long): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x80.toByte()) // V=2, P=0
        buf.put(192.toByte())  // PT=192 (Legacy FIR)
        buf.putShort(1.toShort()) // length = 1
        buf.putInt(targetSsrc.toInt())
        return buf.array()
    }

    /** Compound RTCP packet: RR + PLI + Legacy FIR. */
    fun buildCompoundFir(senderSsrc: Long, targetSsrc: Long): ByteArray {
        return buildRr(senderSsrc) + buildPli(senderSsrc, targetSsrc) + buildFirLegacy(targetSsrc)
    }
}

