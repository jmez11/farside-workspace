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
import java.io.InputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.SecretKeySpec

data class FramebufferUpdateResult(
    val messageType: Int,
    val encodingsSeen: List<Int>,
    val decompressedBytes: Int,
    val zlibRectCount: Int,
    val width: Int,
    val height: Int
)

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
    private var rawInput: DataInputStream? = null
    private var rawOutput: DataOutputStream? = null
    var recordLayer: MacHevcRecordLayer? = null
        private set
    var serverInit: MacHevcServerInit? = null
        private set
    var stage: String = "S0"
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    var recordIn: DataInputStream? = null
        private set
    var recordOut: DataOutputStream? = null
        private set
    private val zlibInflater = Inflater()

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
            s.connect(InetSocketAddress(host, port), 10000)
            s.soTimeout = 60000
            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(java.io.BufferedOutputStream(s.getOutputStream()))
            rawInput = input
            rawOutput = output

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

            width = input.readUnsignedShort()
            height = input.readUnsignedShort()
            val pixelFormat = ByteArray(16).also { input.readFully(it) }
            val nameLen = input.readInt()
            check(nameLen in 0..65536) { "ServerInit name length $nameLen out of bounds" }
            val name = ByteArray(nameLen).also { input.readFully(it) }
            val nameHex = name.joinToString("") { "%02x".format(it) }
            val nameStr = String(name, Charsets.ISO_8859_1)
            val flags = if (name.size >= 22 && name[0] == 0.toByte()) {
                DataInputStream(name.copyOfRange(2, 6).inputStream()).readInt()
            } else 0
            serverInit = MacHevcServerInit(width, height, pixelFormat, name, flags)
            log("S6: ServerInit ${width}x${height}, nameLen=$nameLen, flags=$flags, nameStr='$nameStr', nameHex=$nameHex")

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

            // 3. Receive incoming message after SetEncryption(cmd=1)
            val first2 = ByteArray(2).also { input.readFully(it) }
            val firstLen = ((first2[0].toInt() and 0xff) shl 8) or (first2[1].toInt() and 0xff)

            if (firstLen == 74) {
                // SessionSelect_SessionInfo (74 bytes payload)
                val sessionInfo = ByteArray(74).also { input.readFully(it) }
                val infoBuf = ByteBuffer.wrap(sessionInfo).order(ByteOrder.BIG_ENDIAN)
                val infoVersion = infoBuf.short.toInt() and 0xffff
                val sessionState = infoBuf.int
                val sessionFlags = infoBuf.int
                val userBytes = ByteArray(64).also { infoBuf.get(it) }
                val activeUser = String(userBytes).trimEnd { it == '\u0000' }
                log("S7: Received SessionSelect_SessionInfo: activeUser='$activeUser', state=$sessionState, flags=$sessionFlags")

                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"))

                val shortUser = if (username.equals("James Kasyan", ignoreCase = true)) "jameskasyan" else username.lowercase().replace(" ", "")
                val uBytes = ByteArray(64)
                val srcU = shortUser.toByteArray(Charsets.UTF_8)
                System.arraycopy(srcU, 0, uBytes, 0, minOf(srcU.size, 63))

                val pBytes = ByteArray(64)
                val srcP = password.toByteArray(Charsets.UTF_8)
                System.arraycopy(srcP, 0, pBytes, 0, minOf(srcP.size, 63))

                // Permitted commands per disassembly (btl %eax, %ecx against sessionState):
                // Bit 2 (4): cmd = 2 (virtual multi-session separate login)
                // Bit 1 (2): cmd = 1 (direct connect to console)
                // Bit 0 (1): cmd = 0 (request console permission from active user)
                val cmd = if ((sessionState and 4) != 0) 2 else 0

                if (cmd == 2) {
                    // cmd = 2: Virtual multi-session login
                    val encUser = cipher.doFinal(uBytes)
                    val encPass = cipher.doFinal(pBytes)
                    val resp = ByteBuffer.allocate(202).order(ByteOrder.BIG_ENDIAN)
                    resp.putShort(200.toShort()) // size = 200
                    resp.putShort(2.toShort())   // version = 2
                    resp.putInt(0)               // flags = 0
                    resp.put(2.toByte())         // cmd = 2
                    resp.put(0.toByte())         // pad
                    resp.put(uBytes)             // clear username (64 bytes)
                    resp.put(encUser)            // encrypted username (64 bytes)
                    resp.put(encPass)            // encrypted password (64 bytes)

                    output.write(resp.array())
                    output.flush()
                    log("S7: Sent SessionSelect Virtual Multi-Session (cmd=2, user='$username', 202 bytes)")
                } else {
                    // cmd = 1 or 0
                    val resp = ByteBuffer.allocate(76).order(ByteOrder.BIG_ENDIAN)
                    resp.putShort(74.toShort()) // size = 74
                    resp.putShort(1.toShort())  // version = 1
                    resp.putInt(0)              // flags = 0
                    resp.put(cmd.toByte())      // cmd = 0 or 1
                    resp.put(0.toByte()); resp.put(0.toByte()); resp.put(0.toByte()) // pad[3]
                    resp.put(uBytes)            // username (64 bytes)

                    output.write(resp.array())
                    output.flush()
                    log("S7: Sent SessionSelect console response (cmd=$cmd, user='$username', 76 bytes)")
                }

                // Read server response (82 bytes = u16 size 80 + 80 bytes)
                val replySize = input.readUnsignedShort()
                val replyBytes = ByteArray(replySize).also { input.readFully(it) }
                val replyVersion = if (replySize >= 2) ((replyBytes[0].toInt() and 0xff) shl 8) or (replyBytes[1].toInt() and 0xff) else -1
                val replyStatus = if (replySize >= 3) (replyBytes[2].toInt() and 0xff) else -1
                log("S7: Received SessionSelect reply ($replySize bytes, version=$replyVersion, status=$replyStatus)")
                check(replyStatus == 0) { "SessionSelect failed with status $replyStatus" }

                // Now read the next message (0x44f Rekey message)
                val msgType = input.readUnsignedByte()
                val pad = input.readUnsignedByte()
                val numRects = input.readUnsignedShort()
                val rx = input.readUnsignedShort()
                val ry = input.readUnsignedShort()
                val rw = input.readUnsignedShort()
                val rh = input.readUnsignedShort()
                val encoding = input.readInt()
                log("S7: Rekey header: msgType=$msgType pad=$pad numRects=$numRects rect=${rw}x$rh@$rx,$ry encoding=0x${Integer.toHexString(encoding)}")
                check(msgType == 0) { "Expected FramebufferUpdate (0) for rekey, got $msgType" }
                check(numRects == 1) { "Expected 1 rectangle for rekey, got $numRects" }
                check(encoding == 0x44f) { "Expected rekey encoding 0x44f (1103), got 0x${Integer.toHexString(encoding)}" }
                val rekeyPayload = ByteArray(36).also { input.readFully(it) }
                log("S7: Received 0x44f Rekey payload (36 bytes, rect=${rw}x$rh@$rx,$ry)")
                val layer = MacHevcRecordLayer.unwrapRekey(wrapKey, rekeyPayload)
                recordLayer = layer
                log("S7: Rekey unwrap success (generation=${layer.generation})")
            } else {
                // If not SessionSelect, then first2 are the first 2 bytes of the FramebufferUpdate header (msgType, pad)
                val msgType = first2[0].toInt() and 0xff
                val pad = first2[1].toInt() and 0xff
                val numRects = input.readUnsignedShort()
                val rx = input.readUnsignedShort()
                val ry = input.readUnsignedShort()
                val rw = input.readUnsignedShort()
                val rh = input.readUnsignedShort()
                val encoding = input.readInt()
                log("S7: Direct rekey header: msgType=$msgType pad=$pad numRects=$numRects rect=${rw}x$rh@$rx,$ry encoding=0x${Integer.toHexString(encoding)}")
                check(msgType == 0) { "Expected FramebufferUpdate (0) for rekey, got $msgType" }
                check(numRects == 1) { "Expected 1 rectangle for rekey, got $numRects" }
                check(encoding == 0x44f) { "Expected rekey encoding 0x44f (1103), got 0x${Integer.toHexString(encoding)}" }
                val rekeyPayload = ByteArray(36).also { input.readFully(it) }
                log("S7: Received 0x44f Rekey payload (36 bytes, rect=${rw}x$rh@$rx,$ry)")
                val layer = MacHevcRecordLayer.unwrapRekey(wrapKey, rekeyPayload)
                recordLayer = layer
                log("S7: Rekey unwrap success (generation=${layer.generation})")
            }

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
            val layer = checkNotNull(recordLayer) { "Rekey unwrap failed" }

            // 1. Encrypted SetEncodings (0x02)
            val encodings = buildSetEncodings(listOf(0x451, 0x453, 0x455, 0x456, 0x450, 0x3f2, 6, 0))
            layer.writeRecord(output, encodings)
            log("S8: Sent encrypted SetEncodings (0x02, len=${encodings.size})")

            // 2. Encrypted FramebufferUpdateRequest (0x03) + AutoFrameBufferUpdate (0x09)
            val fbur = buildFramebufferUpdateRequest(width, height)
            layer.writeRecord(output, fbur)
            val autoFbur = buildAutoFrameBufferUpdate(width, height)
            layer.writeRecord(output, autoFbur)
            log("S8: Sent encrypted 0x03 and 0x09")

            // Decrypt first record from server
            val record = layer.readRecord(input)
            val recType = record[0].toInt() and 0xff
            val preview = record.take(64).joinToString(" ") { "%02x".format(it) }
            log("S8 GATE PASS: Decrypted first record (type=0x${Integer.toHexString(recType)}, len=${record.size}, recvSeq=${layer.recvSeq - 1})")
            log("S8: First record bytes[0..${minOf(64, record.size)}]: $preview")
            return record
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun buildDisplayConfiguration(w: Int, h: Int, displayType: Int = 0): ByteArray {
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
        buf.putInt(if (displayType == 4) 1 else 0) // display_flags (bit 0 = dynamic resolution)
        buf.putInt(displayType)     // display_type (0 = primary, 4 = virtual display)
        buf.putFloat(369.45f)       // physical_width_mm
        buf.putFloat(207.82f)       // physical_height_mm
        buf.putInt(w)               // max_width
        buf.putInt(h)               // max_height
        buf.putShort(0.toShort())   // current_mode_index
        buf.putShort(0.toShort())   // preferred_mode_index
        buf.putInt(0)               // rotations = 0
        buf.putShort(1.toShort())   // mode_count = 1

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

    fun initStreamingLayer(firstRecord: ByteArray? = null) {
        val layer = checkNotNull(recordLayer) { "Record layer not established" }
        val rIn = checkNotNull(rawInput) { "Input stream not established" }
        val rOut = checkNotNull(rawOutput) { "Output stream not established" }

        val inStream = object : InputStream() {
            private var currentRecord: ByteArray? = firstRecord
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
                        layer.readRecord(rIn)
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

        recordIn = DataInputStream(inStream)
        recordOut = DataOutputStream(layer.createOutputStream(rOut))
        stage = "S9"
        log("S9: Reached steady-state (S9) on the framebuffer path with record layer active")
    }

    fun readNextFramebufferUpdate(): FramebufferUpdateResult {
        val stream = checkNotNull(recordIn) { "Record stream not initialized" }
        val msgType = stream.readUnsignedByte()
        val encodingsSeen = mutableListOf<Int>()
        var decompressedBytes = 0
        var zlibRectCount = 0
        var lastW = width
        var lastH = height

        when (msgType) {
            0 -> { // FramebufferUpdate
                stream.readByte() // pad
                val numRects = stream.readUnsignedShort()
                log("S9: FramebufferUpdate numRects=$numRects")
                for (i in 0 until numRects) {
                    val rx = stream.readUnsignedShort()
                    val ry = stream.readUnsignedShort()
                    val rw = stream.readUnsignedShort()
                    val rh = stream.readUnsignedShort()
                    val enc = stream.readInt()
                    encodingsSeen.add(enc)
                    log("S9: Rect $i: ${rw}x$rh@$rx,$ry enc=0x${Integer.toHexString(enc)} ($enc)")

                    when (enc) {
                        6 -> { // ZLIB
                            val zlibLen = stream.readInt()
                            val compressed = ByteArray(zlibLen)
                            stream.readFully(compressed)
                            val expectedPixels = rw * rh * 4
                            val decomp = ByteArray(expectedPixels)
                            zlibInflater.setInput(compressed)
                            var totalInflated = 0
                            while (!zlibInflater.needsInput() && totalInflated < decomp.size) {
                                val count = zlibInflater.inflate(decomp, totalInflated, decomp.size - totalInflated)
                                if (count <= 0) break
                                totalInflated += count
                            }
                            decompressedBytes += totalInflated
                            zlibRectCount++
                            lastW = rw
                            lastH = rh
                            log("S9: Decompressed ZLIB rect: $totalInflated / $expectedPixels bytes (w=$rw, h=$rh)")
                        }
                        0x451 -> { // AppleDisplayLayout
                            val payloadLen = stream.readUnsignedShort()
                            val data = ByteArray(payloadLen)
                            stream.readFully(data)
                            log("S9: AppleDisplayLayout (${data.size} bytes), re-arming with 0x09 and 0x03")
                            recordOut?.write(buildAutoFrameBufferUpdate(width, height))
                            recordOut?.write(buildFramebufferUpdateRequest(width, height))
                            recordOut?.flush()
                        }
                        0x453 -> { // VendorKeysymEncoding
                            val data = ByteArray(22)
                            stream.readFully(data)
                            log("S9: VendorKeysymEncoding (22 bytes)")
                        }
                        0x455 -> { // KeyboardInputSource
                            val prefixLen = stream.readUnsignedShort()
                            val data = ByteArray(prefixLen)
                            stream.readFully(data)
                            log("S9: KeyboardInputSource ($prefixLen bytes)")
                        }
                        0x456 -> { // DeviceInfo
                            val msgSize = stream.readUnsignedShort()
                            val data = ByteArray(msgSize)
                            stream.readFully(data)
                            log("S9: DeviceInfo ($msgSize bytes)")
                        }
                        0x450 -> { // CursorImage
                            val cacheId = stream.readInt()
                            val compLen = stream.readInt()
                            if (compLen > 0) {
                                val data = ByteArray(compLen)
                                stream.readFully(data)
                                log("S9: CursorImage STORE cacheId=0x${Integer.toHexString(cacheId)} len=$compLen")
                            } else {
                                log("S9: CursorImage SELECT cacheId=0x${Integer.toHexString(cacheId)}")
                            }
                        }
                        0x3f2 -> { // RFBMediaStreamMessage1
                            val payloadLen = stream.readUnsignedShort()
                            val data = ByteArray(payloadLen)
                            stream.readFully(data)
                            log("S9: RFBMediaStreamMessage1 ($payloadLen bytes)")
                        }
                        0 -> { // Raw
                            val rawLen = rw * rh * 4
                            val data = ByteArray(rawLen)
                            stream.readFully(data)
                            decompressedBytes += rawLen
                            lastW = rw
                            lastH = rh
                        }
                        else -> {
                            log("S9: Unknown or unhandled encoding 0x${Integer.toHexString(enc)}, ignoring per §12")
                        }
                    }
                }
            }
            0x14 -> { // MiscStatus (8 bytes: type + pad + body_len(4) + flags(1) + cmd)
                val pad = stream.readByte()
                val bodyLen = stream.readUnsignedShort()
                val body = ByteArray(bodyLen)
                stream.readFully(body)
                val cmd = if (bodyLen >= 4) (((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)) else -1
                log("S9: Received MiscStatus (0x14) bodyLen=$bodyLen cmd=$cmd")
            }
            0x1c -> { // MediaStreamOptions answer
                val pad = stream.readByte()
                val msgSize = stream.readUnsignedShort()
                val payload = ByteArray(msgSize)
                stream.readFully(payload)
                log("S9: Received MediaStreamOptions (0x1c) answer: msgSize=$msgSize payloadHex=${payload.take(32).joinToString("") { "%02x".format(it) }}")
            }
            0x1f -> { // ClipboardSend
                val pad1 = stream.readByte()
                val promise = stream.readByte()
                val pad2 = stream.readByte()
                val reserved = stream.readInt()
                val uncompressedSize = stream.readInt()
                val compressedSize = stream.readInt()
                val compressed = ByteArray(compressedSize)
                stream.readFully(compressed)
                log("S9: Received ClipboardSend (0x1f): uncompressed=$uncompressedSize compressed=$compressedSize")
            }
            else -> {
                log("S9: Other RFB message type $msgType, ignoring per §12")
            }
        }
        return FramebufferUpdateResult(msgType, encodingsSeen, decompressedBytes, zlibRectCount, lastW, lastH)
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int = 0) {
        val out = checkNotNull(recordOut) { "Record stream not initialized" }
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.put(5.toByte()) // PointerEvent
        buf.put(buttonMask.toByte())
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        out.write(buf.array())
        out.flush()
        log("S9: Sent PointerEvent(x=$x, y=$y, mask=$buttonMask)")
    }

    fun requestUpdate(incremental: Boolean = true) {
        val out = checkNotNull(recordOut) { "Record stream not initialized" }
        val fbur = if (incremental) {
            val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            buf.put(3.toByte())
            buf.put(1.toByte()) // incremental = 1
            buf.putShort(0); buf.putShort(0)
            buf.putShort(width.toShort()); buf.putShort(height.toShort())
            buf.array()
        } else {
            buildFramebufferUpdateRequest(width, height)
        }
        out.write(fbur)
        out.flush()
        log("S9: Requested FramebufferUpdate (incremental=$incremental)")
    }

    fun buildMediaStreamOffer(): ByteArray {
        val audioOffer = ByteArray(32) // audio offer blob
        val videoOffer = ByteArray(64) // video offer blob
        val audioKey1 = ByteArray(46)
        val audioKey2 = ByteArray(46)
        val videoKey1 = ByteArray(46)
        val videoKey2 = ByteArray(46)
        SecureRandom().nextBytes(audioKey1)
        SecureRandom().nextBytes(audioKey2)
        SecureRandom().nextBytes(videoKey1)
        SecureRandom().nextBytes(videoKey2)

        val totalLen = 20 + 16 + 46 * 2 + audioOffer.size + 46 * 2 + videoOffer.size
        val messageSize = totalLen - 4
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x1c.toByte()) // message_type = 0x1c
        buf.put(0.toByte())    // reserved
        buf.putShort(messageSize.toShort())
        buf.putShort(1.toShort()) // version = 1
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x07) // flags (host-endian): 60fps stream 1 & 2 + do-not-send-cursor
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(audioOffer.size.toShort())
        buf.putShort(videoOffer.size.toShort())
        buf.putShort(0.toShort()) // video2_offer_len = 0
        buf.putInt(0) // reserved
        buf.put(ByteArray(16)) // session / CallID UUID
        buf.put(audioKey1)
        buf.put(audioKey2)
        buf.put(audioOffer)
        buf.put(videoKey1)
        buf.put(videoKey2)
        buf.put(videoOffer)
        return buf.array()
    }

    fun sendMediaStreamOffer(): ByteArray {
        val out = checkNotNull(recordOut) { "Record stream not initialized" }
        val offer = buildMediaStreamOffer()
        log("S9: Sending MediaStreamOptions offer (0x1c, ${offer.size} bytes)")
        out.write(offer)
        out.flush()
        return offer
    }

    fun sendDisplayConfiguration(w: Int = width, h: Int = height, displayType: Int = 0) {
        val out = checkNotNull(recordOut) { "Record stream not initialized" }
        val config = buildDisplayConfiguration(w, h, displayType)
        log("S9: Sending SetDisplayConfiguration (0x1d, ${config.size} bytes: ${w}x${h}, displayType=$displayType)")
        out.write(config)
        out.flush()
    }

    override fun close() {
        zlibInflater.end()
        runCatching { rawInput?.close() }
        runCatching { rawOutput?.close() }
        runCatching { socket?.close() }
        socket = null
        stage = "CLOSED"
    }
}
