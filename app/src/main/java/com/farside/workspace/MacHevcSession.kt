package com.farside.workspace

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.SecretKeySpec

data class MacHevcServerInit(
    val width: Int,
    val height: Int,
    val pixelFormat: ByteArray,
    val name: ByteArray,
    val flags: Int
)

/**
 * Apple VNC High-Performance Extension Session (apple_vnc_rfc S0-S9).
 *
 * Implements cleartext Apple prelude, 0x44f rekey unwrapping, and AES-128-CBC
 * record layer encrypted control exchange.
 */
class MacHevcSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val logCallback: (String) -> Unit = {}
) : Closeable {

    private var socket: Socket? = null
    var recordLayer: MacHevcRecordLayer? = null
        private set
    var serverInit: MacHevcServerInit? = null
        private set
    var stage: String = "S0"
        private set

    private fun log(message: String) {
        Log.i("MacHevcSession", message)
        logCallback(message)
    }

    /**
     * Executes the bootstrap through S8 (Record Layer Active) and reads the first encrypted record.
     * Throws an exception on any protocol or integrity failure, closing the socket immediately.
     */
    fun start(): ByteArray {
        val s = Socket()
        socket = s
        try {
            stage = "S0"
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 10000
            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(java.io.BufferedOutputStream(s.getOutputStream()))

            // S0: Server ProtocolVersion
            val serverBanner = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            log("S0: Server banner: ${serverBanner.trim()}")
            require(serverBanner.startsWith("RFB 003.")) { "Unexpected banner: $serverBanner" }

            // S1: Client ProtocolVersion (RFB 003.889)
            stage = "S1"
            output.write("RFB 003.889\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            log("S1: Sent RFB 003.889")

            // S2: Server Security-Type Advertisement
            stage = "S2"
            val count = input.readUnsignedByte()
            check(count > 0) { "Server sent failure list (count=0)" }
            val types = ByteArray(count).also { input.readFully(it) }
            val typeList = types.map { it.toInt() and 0xff }
            log("S2: Security types offered: $typeList")
            check(typeList.contains(30)) { "Server did not offer Apple DH type 30" }

            // S3: Select Type 30
            stage = "S3"
            output.writeByte(30)
            output.flush()
            log("S3: Selected security type 30 (DH)")

            // S4: Authentication (DH Key Agreement + Credential Encrypt)
            stage = "S4"
            val generator = input.readUnsignedShort()
            val keyLength = input.readUnsignedShort()
            check(generator > 1 && keyLength in 128..512) { "Invalid DH parameters: g=$generator, len=$keyLength" }
            val primeBytes = ByteArray(keyLength).also { input.readFully(it) }
            val serverBytes = ByteArray(keyLength).also { input.readFully(it) }
            val prime = BigInteger(1, primeBytes)
            val serverPublic = BigInteger(1, serverBytes)

            val dhSpec = DHParameterSpec(prime, BigInteger.valueOf(generator.toLong()))
            val keyPair = KeyPairGenerator.getInstance("DH").apply { initialize(dhSpec) }.generateKeyPair()
            val peerKey = KeyFactory.getInstance("DH").generatePublic(DHPublicKeySpec(serverPublic, dhSpec.p, dhSpec.g))
            val agreement = KeyAgreement.getInstance("DH").apply { init(keyPair.private); doPhase(peerKey, true) }
            val rawShared = agreement.generateSecret()
            val shared = if (rawShared.size == keyLength) rawShared else ByteArray(keyLength).also { dest ->
                check(rawShared.size <= keyLength)
                rawShared.copyInto(dest, keyLength - rawShared.size)
            }

            // Wrap key for type 30 is MD5(shared)[0:16] (§4.2.3, §6.2.2)
            val wrapKey = MessageDigest.getInstance("MD5").digest(shared)

            // Credential blob: 128 bytes AES-ECB encrypted with wrapKey
            val plain = ByteArray(128).also { SecureRandom().nextBytes(it) }
            fun putCredential(value: String, offset: Int) {
                val b = value.toByteArray(Charsets.UTF_8)
                val len = minOf(b.size, 63)
                b.copyInto(plain, offset, 0, len)
                plain[offset + len] = 0
            }
            putCredential(username, 0)
            putCredential(password, 64)

            val creds = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"))
            }.doFinal(plain)
            plain.fill(0)
            shared.fill(0)

            output.write(creds)
            val pub = (keyPair.public as javax.crypto.interfaces.DHPublicKey).y.toByteArray()
            val pubPadded = if (pub.size > keyLength) pub.copyOfRange(pub.size - keyLength, pub.size) else pub
            output.write(ByteArray(keyLength - pubPadded.size))
            output.write(pubPadded)
            output.flush()
            log("S4: DH credentials and public key sent")

            // S5: SecurityResult
            stage = "S5"
            val secResult = input.readInt()
            check(secResult == 0) { "SecurityResult non-zero: $secResult" }
            log("S5: SecurityResult = 0 (Authentication Success)")

            // S6: ClientInit & ServerInit
            stage = "S6"
            output.writeByte(0xc1) // Apple enhanced shared flag
            output.flush()

            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            val pixelFormat = ByteArray(16).also { input.readFully(it) }
            val nameLen = input.readInt()
            check(nameLen in 0..65536) { "ServerInit name length $nameLen out of bounds" }
            val name = ByteArray(nameLen).also { input.readFully(it) }
            val flags = if (name.size >= 22 && name[0] == 0.toByte()) {
                DataInputStream(name.copyOfRange(2, 6).inputStream()).readInt()
            } else 0
            serverInit = MacHevcServerInit(width, height, pixelFormat, name, flags)
            log("S6: ServerInit ${width}x${height}, nameLen=$nameLen, flags=$flags")

            // S7: Cleartext Apple Prelude
            stage = "S7"

            // 1. ViewerInfo (0x21)
            output.writeByte(0x21)
            output.writeByte(0)
            output.writeShort(62) // body length
            output.writeShort(1)  // version = 1
            output.writeInt(2)    // viewer_app = 2
            listOf(6, 1, 0, 10, 15, 0).forEach(output::writeInt)
            output.write(ByteArray(32)) // capability bitmap
            output.flush()
            log("S7: Sent ViewerInfo (0x21)")

            // 2. SetEncryption(command=1) (0x12)
            output.write(byteArrayOf(
                0x12, 0x00,             // type=0x12, reserved=0
                0x00, 0x01,             // command=1
                0x00, 0x01, 0x00, 0x01, // method_count=1, reserved
                0x00, 0x00, 0x00, 0x01  // method=1 (AES-128)
            ))
            output.flush()
            log("S7: Sent SetEncryption(cmd=1)")

            // 3. Receive 0x44f Rekey message (carried as FramebufferUpdate rectangle)
            val msgType = input.readUnsignedByte()
            check(msgType == 0) { "Expected FramebufferUpdate (0) for rekey, got $msgType" }
            input.skipBytes(1) // pad
            val numRects = input.readUnsignedShort()
            check(numRects == 1) { "Expected 1 rectangle for rekey, got $numRects" }
            val rx = input.readUnsignedShort()
            val ry = input.readUnsignedShort()
            val rw = input.readUnsignedShort()
            val rh = input.readUnsignedShort()
            val encoding = input.readInt()
            check(encoding == 0x44f) { "Expected rekey encoding 0x44f (1103), got 0x${Integer.toHexString(encoding)}" }
            val rekeyPayload = ByteArray(36).also { input.readFully(it) }
            log("S7: Received 0x44f Rekey payload (36 bytes, rect=${rw}x$rh@$rx,$ry)")

            val layer = MacHevcRecordLayer.unwrapRekey(wrapKey, rekeyPayload)
            recordLayer = layer
            log("S7: Rekey unwrap success (generation=${layer.generation})")

            // 4. SetMode(mode=1) (0x0a)
            output.write(byteArrayOf(0x0a, 0x00, 0x00, 0x01))
            output.flush()
            log("S7: Sent SetMode(mode=1)")

            // 5. SetEncryption(command=2) (0x12)
            output.write(byteArrayOf(0x12, 0x00, 0x00, 0x02, 0x00, 0x01, 0x00, 0x00))
            output.flush()
            log("S7: Sent SetEncryption(cmd=2)")

            // S8: Record Layer Active
            stage = "S8"
            log("S8: Record Layer ACTIVE")

            // 1. Encrypted SetDisplayConfiguration (0x1d)
            val displayConfig = buildDisplayConfiguration(width, height)
            layer.writeRecord(output, displayConfig)
            log("S8: Sent encrypted SetDisplayConfiguration (0x1d, len=${displayConfig.size})")

            // 2. Encrypted SetEncodings (0x02)
            val encodings = buildSetEncodings(listOf(0x451, 0x453, 0x455, 0x456, 0x450, 6))
            layer.writeRecord(output, encodings)
            log("S8: Sent encrypted SetEncodings (0x02, len=${encodings.size})")

            // 3. Encrypted FramebufferUpdateRequest (0x03) + AutoFrameBufferUpdate (0x09)
            val fbur = buildFramebufferUpdateRequest(width, height)
            layer.writeRecord(output, fbur)
            val autoFbur = buildAutoFrameBufferUpdate(width, height)
            layer.writeRecord(output, autoFbur)
            log("S8: Sent encrypted 0x03 and 0x09")

            // Decrypt first record from server
            val record = layer.readRecord(input)
            val recType = record[0].toInt() and 0xff
            log("S8 GATE PASS: Decrypted first record (type=0x${Integer.toHexString(recType)}, len=${record.size}, recvSeq=${layer.recvSeq - 1})")
            return record
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun buildDisplayConfiguration(w: Int, h: Int): ByteArray {
        val totalLen = 196
        val messageSize = totalLen - 4 // 192
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        // Front header (12 bytes)
        buf.put(0x1d.toByte()) // type
        buf.put(0.toByte())    // reserved
        buf.putShort(messageSize.toShort())
        buf.putShort(1.toShort()) // version
        buf.putShort(1.toShort()) // display_count = 1
        buf.putInt(0)             // flags = 0

        // Display descriptor (184 bytes: 0x9c + 1 * 0x1c)
        buf.putShort(184.toShort()) // display_info_size
        buf.put(ByteArray(120))     // display_info_region
        buf.putInt(0)               // display_flags
        buf.putInt(0)               // display_type
        buf.putFloat(0f)            // physical_width_mm
        buf.putFloat(0f)            // physical_height_mm
        buf.putInt(w)               // max_width
        buf.putInt(h)               // max_height
        buf.putShort(0.toShort())   // current_mode_index
        buf.putShort(0.toShort())   // preferred_mode_index
        buf.putInt(0)               // rotations = 0

        // Mode table (1 entry x 28 bytes)
        buf.putInt(w)               // width
        buf.putInt(h)               // height
        buf.putInt(w)               // scaled_width
        buf.putInt(h)               // scaled_height
        buf.putDouble(60.0)         // refresh_rate_hz
        buf.putInt(0)               // flags
        return buf.array()
    }

    private fun buildSetEncodings(encodings: List<Int>): ByteArray {
        val buf = ByteBuffer.allocate(4 + encodings.size * 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(2.toByte())                  // type = SetEncodings
        buf.put(0.toByte())                  // pad
        buf.putShort(encodings.size.toShort())
        for (enc in encodings) {
            buf.putInt(enc)
        }
        return buf.array()
    }

    private fun buildFramebufferUpdateRequest(w: Int, h: Int): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.put(3.toByte()) // FramebufferUpdateRequest
        buf.put(0.toByte()) // incremental = 0
        buf.putShort(0)     // x
        buf.putShort(0)     // y
        buf.putShort(w.toShort())
        buf.putShort(h.toShort())
        return buf.array()
    }

    private fun buildAutoFrameBufferUpdate(w: Int, h: Int): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.put(9.toByte()) // AutoFrameBufferUpdate
        buf.put(0.toByte()) // reserved
        buf.putShort(1.toShort()) // version = 1
        buf.putInt(-1)      // selected_screen = 0xffffffff (all/main)
        buf.putShort(0)     // x
        buf.putShort(0)     // y
        buf.putShort(w.toShort())
        buf.putShort(h.toShort())
        return buf.array()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
