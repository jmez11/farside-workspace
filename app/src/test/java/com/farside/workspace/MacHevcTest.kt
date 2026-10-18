package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MacHevcTest {

    @Test
    fun testBuildDisplayConfigurationFraming() {
        // Create dummy session to access buildDisplayConfiguration
        val session = MacHevcSession("127.0.0.1", 5900, "user", "pass")
        val config = session.buildDisplayConfiguration(1920, 1080, displayType = 4)

        assertEquals(196, config.size)
        val buf = ByteBuffer.wrap(config).order(ByteOrder.BIG_ENDIAN)

        // Front header (12 bytes)
        assertEquals(0x1d.toByte(), buf.get()) // type
        assertEquals(0.toByte(), buf.get())    // reserved
        assertEquals(192.toShort(), buf.short) // message_size = 196 - 4
        assertEquals(1.toShort(), buf.short)   // version = 1
        assertEquals(1.toShort(), buf.short)   // display_count = 1
        assertEquals(0, buf.int)               // flags = 0

        // Display descriptor
        assertEquals(184.toShort(), buf.short) // display_info_size
        buf.position(buf.position() + 120)     // display_info_region (120 bytes)
        assertEquals(1, buf.int)               // display_flags (bit 0 = dynamic resolution)
        assertEquals(4, buf.int)               // display_type (4 = virtual display)
        assertEquals(369.45f, buf.float, 0.01f) // physical_width_mm
        assertEquals(207.82f, buf.float, 0.01f) // physical_height_mm
        assertEquals(1920, buf.int)            // max_width
        assertEquals(1080, buf.int)            // max_height
        assertEquals(0.toShort(), buf.short)   // current_mode_index
        assertEquals(0.toShort(), buf.short)   // preferred_mode_index
        assertEquals(0, buf.int)               // rotations
        assertEquals(1.toShort(), buf.short)   // mode_count

        // Mode table entry (28 bytes)
        assertEquals(1920, buf.int)            // width
        assertEquals(1080, buf.int)            // height
        assertEquals(1920, buf.int)            // scaled_width
        assertEquals(1080, buf.int)            // scaled_height
        assertEquals(60.0, buf.double, 0.001)  // refresh_rate_hz
        assertEquals(0, buf.int)               // flags
    }

    @Test
    fun testParseAppleDisplayLayout() {
        val payload = ByteArray(20)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(5.toShort())    // version = 5
        buf.putShort(1920.toShort()) // scaled_w
        buf.putShort(1080.toShort()) // scaled_h
        buf.putShort(3840.toShort()) // backing_w (2x HiDPI)
        buf.putShort(2160.toShort()) // backing_h

        val res = MacHevcSession.parseAppleDisplayLayout(payload)
        assertNotNull(res)
        assertEquals(3840, res!!.first)
        assertEquals(2160, res.second)
    }

    @Test
    fun testSrtpKeyDerivationAndPacketDecryptionRoundTrip() {
        val random = SecureRandom()
        val masterKey = ByteArray(32).also { random.nextBytes(it) }
        val masterSalt = ByteArray(14).also { random.nextBytes(it) }

        val srtp = MacHevcSrtp(masterKey, masterSalt)
        assertEquals(32, srtp.cipherKey.size)
        assertEquals(20, srtp.authKey.size)
        assertEquals(14, srtp.salt.size)

        // Build a synthetic RTP packet with payload
        val ssrc = 0x12345678L
        val seq = 100
        val payloadData = "Synthetic HEVC NAL data stream 0123456789".toByteArray(Charsets.UTF_8)

        // Encrypt payload using AES-256-CTR
        val iv = ByteArray(16)
        System.arraycopy(srtp.salt, 0, iv, 0, 14)
        iv[4] = (iv[4].toInt() xor ((ssrc ushr 24) and 0xff).toInt()).toByte()
        iv[5] = (iv[5].toInt() xor ((ssrc ushr 16) and 0xff).toInt()).toByte()
        iv[6] = (iv[6].toInt() xor ((ssrc ushr 8) and 0xff).toInt()).toByte()
        iv[7] = (iv[7].toInt() xor (ssrc and 0xff).toInt()).toByte()
        iv[12] = (iv[12].toInt() xor ((seq ushr 8) and 0xff).toInt()).toByte()
        iv[13] = (iv[13].toInt() xor (seq and 0xff).toInt()).toByte()

        val encCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(srtp.cipherKey, "AES"), IvParameterSpec(iv))
        }
        val encPayload = encCipher.doFinal(payloadData)

        // Construct RTP packet: 12 bytes header + encrypted payload + 10 bytes HMAC-SHA1 tag
        val rtpHeader = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        rtpHeader.put(0x80.toByte()) // V=2, P=0, X=0, CC=0
        rtpHeader.put(100.toByte())  // M=0, PT=100 (HEVC)
        rtpHeader.putShort(seq.toShort())
        rtpHeader.putInt(123456)     // timestamp
        rtpHeader.putInt(ssrc.toInt())

        val unauthedPacket = ByteBuffer.allocate(12 + encPayload.size).order(ByteOrder.BIG_ENDIAN)
        unauthedPacket.put(rtpHeader.array())
        unauthedPacket.put(encPayload)

        // Calculate HMAC-SHA1-80 tag over packet + ROC(0)
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(srtp.authKey, "HmacSHA1"))
        }
        mac.update(unauthedPacket.array())
        mac.update(byteArrayOf(0, 0, 0, 0)) // ROC = 0
        val fullTag = mac.doFinal()
        val tag80 = fullTag.copyOfRange(0, 10)

        val fullSrtpPacket = ByteBuffer.allocate(12 + encPayload.size + 10).order(ByteOrder.BIG_ENDIAN)
        fullSrtpPacket.put(unauthedPacket.array())
        fullSrtpPacket.put(tag80)

        // Decrypt with MacHevcSrtp
        val decrypted = srtp.decryptPacket(fullSrtpPacket.array())
        assertEquals(100, decrypted.payloadType)
        assertEquals(seq, decrypted.sequenceNumber)
        assertEquals(ssrc, decrypted.ssrc)
        assertArrayEquals(payloadData, decrypted.payload)
    }

    @Test
    fun testRtpParserSingleNalWithDonl() {
        val parser = MacHevcRtpParser()

        // Single NAL unit: type 1 (TRAIL_R), 2-byte header = 0x02 0x01
        // DONL = 0x00 0x42
        val payload = byteArrayOf(
            0x02, 0x01,       // NAL header (type = (0x02 >> 1) = 1)
            0x00, 0x42,       // 2-byte Apple DONL
            0x11, 0x22, 0x33  // NAL body
        )

        val nals = parser.parseRtpPayload(payload, ssrc = 1L)
        assertEquals(1, nals.size)
        val nal = nals[0]

        // Must start with Annex B start code 00 00 00 01
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), nal.copyOfRange(0, 4))
        // NAL header preserved
        assertEquals(0x02.toByte(), nal[4])
        assertEquals(0x01.toByte(), nal[5])
        // DONL stripped, body present
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), nal.copyOfRange(6, nal.size))
    }

    @Test
    fun testRtpParserFragmentationUnitWithDonl() {
        val parser = MacHevcRtpParser()

        // Fragmented IDR NAL unit (actual type 19)
        // FU header type 49: header0 = (49 << 1) = 98 (0x62), header1 = 1
        // Fragment 1: S=1, E=0, actual type = 19 (0x13). FU header = 0x80 | 19 = 0x93
        val frag1 = byteArrayOf(
            0x62, 0x01,       // NAL header (FU type 49)
            0x93.toByte(),     // FU header (S=1, E=0, type=19)
            0x00, 0x01,       // DONL
            0xaa.toByte(), 0xbb.toByte() // payload part 1
        )

        val out1 = parser.parseRtpPayload(frag1, ssrc = 2L)
        assertTrue(out1.isEmpty()) // intermediate fragment, not complete

        // Fragment 2: S=0, E=1, FU header = 0x40 | 19 = 0x53
        val frag2 = byteArrayOf(
            0x62, 0x01,       // NAL header (FU type 49)
            0x53.toByte(),     // FU header (S=0, E=1, type=19)
            0x00, 0x02,       // DONL
            0xcc.toByte(), 0xdd.toByte() // payload part 2
        )

        val out2 = parser.parseRtpPayload(frag2, ssrc = 2L)
        assertEquals(1, out2.size)
        val fullNal = out2[0]

        // Annex B start code
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), fullNal.copyOfRange(0, 4))
        // Reconstructed NAL header: type 19 -> (19 << 1) = 38 (0x26), header1 = 1
        assertEquals(0x26.toByte(), fullNal[4])
        assertEquals(0x01.toByte(), fullNal[5])
        // Combined payload without DONLs
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte()), fullNal.copyOfRange(6, fullNal.size))
    }
}
