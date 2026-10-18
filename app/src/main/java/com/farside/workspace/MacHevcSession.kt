package com.farside.workspace

import android.util.Log
import android.graphics.Bitmap
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
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
 * Implements warmup_tcp probe, cleartext Apple prelude, SessionSelect multi-session / console share,
 * 0x44f rekey unwrapping, AES-128-CBC record layer, 0x1c MediaStreamOptions offer generation with tilesPerFrame=1,
 * and UDP SRTP / MediaCodec hardware HEVC decoding pipeline.
 */
class MacHevcSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val shareConsole: Boolean = false,
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

    @Volatile var currentSurface: Surface? = null
    var onResolutionChanged: ((Int, Int) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    private var frameBitmap: Bitmap? = null
    private var framePixels: IntArray? = null

    var videoPipeline: MacHevcVideoPipeline? = null
        private set
    var negotiationKeys: MacHevcNegotiationKeys? = null
        private set

    fun setSurface(surface: Surface?) {
        currentSurface = surface
        videoPipeline?.updateSurface(surface)
        if (surface != null && frameBitmap != null) {
            drawFrameToSurface()
        }
    }

    fun getDecoderDescription(): String {
        val pipeline = videoPipeline
        val decoded = pipeline?.framesDecoded?.get() ?: 0L
        val pkts = pipeline?.packetsReceived?.get() ?: 0L
        return if (decoded > 0L) {
            "Hardware HEVC (MediaCodec, UDP 5901, $decoded frames)"
        } else if (pkts > 0L) {
            "Hardware HEVC (MediaCodec, UDP 5901, $pkts pkts buffering)"
        } else {
            "CPU Software ZLIB (MacHevcSession stage $stage)"
        }
    }

    private fun drawFrameToSurface() {
        val s = currentSurface ?: return
        val bmp = frameBitmap ?: return
        try {
            val canvas = s.lockCanvas(null) ?: return
            try {
                val destRect = android.graphics.Rect(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bmp, null, destRect, null)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            // Surface may be transiently released or resizing
        }
    }

    private fun updatePixels(rx: Int, ry: Int, rw: Int, rh: Int, decomp: ByteArray) {
        if (width <= 0 || height <= 0 || rw <= 0 || rh <= 0) return
        var pixels = framePixels
        if (pixels == null || pixels.size != width * height) {
            pixels = IntArray(width * height)
            framePixels = pixels
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        var srcOffset = 0
        for (y in 0 until rh) {
            val destY = ry + y
            if (destY >= height) break
            val destRow = destY * width
            for (x in 0 until rw) {
                val destX = rx + x
                if (destX >= width) break
                if (srcOffset + 3 <= decomp.size) {
                    val r = decomp[srcOffset].toInt() and 0xff
                    val g = decomp[srcOffset + 1].toInt() and 0xff
                    val b = decomp[srcOffset + 2].toInt() and 0xff
                    pixels[destRow + destX] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                    srcOffset += 4
                }
            }
        }
        frameBitmap?.setPixels(pixels, 0, width, 0, 0, width, height)
        drawFrameToSurface()
    }

    private fun log(message: String) {
        Log.i("MacHevcSession", message)
        logCallback(message)
    }

    private fun warmupTcp(h: String, p: Int) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(h, p), 5000)
                s.soTimeout = 5000
                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                val banner = ByteArray(12).also { input.readFully(it) }
                output.write("RFB 003.889\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                val nt = input.readUnsignedByte()
                if (nt > 0) {
                    ByteArray(nt).also { input.readFully(it) }
                }
                Thread.sleep(1400)
            }
            log("warmup_tcp: Pre-session probe completed successfully (dwelled 1.4s)")
        } catch (e: Exception) {
            log("warmup_tcp: Probe notice: ${e.message}")
        }
    }

    private data class AuthInitResult(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val wrapKey: ByteArray,
        val serverInit: MacHevcServerInit
    )

    private fun doAuthAndClientInit(sock: Socket): AuthInitResult {
        stage = "S0"
        sock.connect(InetSocketAddress(host, port), 10000)
        sock.soTimeout = 60000
        val input = DataInputStream(sock.getInputStream())
        val output = DataOutputStream(java.io.BufferedOutputStream(sock.getOutputStream()))

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
        if (!typeList.contains(30)) {
            log("S2: Server did not offer Apple DH type 30 (offered: $typeList)")
            throw VncAuthenticationException()
        }

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

        // Wrap key for type 30 is MD5(shared)[0:16]
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
        if (secResult != 0) {
            log("S5: SecurityResult failed ($secResult)")
            throw VncAuthenticationException()
        }
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
        val flags = if (name.size >= 22 && name[0] == 0.toByte()) {
            DataInputStream(name.copyOfRange(2, 6).inputStream()).readInt()
        } else 0
        val sInit = MacHevcServerInit(width, height, pixelFormat, name, flags)
        serverInit = sInit
        log("S6: ServerInit ${width}x${height}, nameLen=$nameLen, flags=$flags")

        return AuthInitResult(sock, input, output, wrapKey, sInit)
    }

    /**
     * Executes the bootstrap through S8 (Record Layer Active) and reads the first encrypted record.
     * Throws an exception on any protocol or integrity failure, closing the socket immediately.
     */
    fun start(): ByteArray {
        warmupTcp(host, port)

        val s1 = Socket()
        socket = s1
        try {
            var auth = doAuthAndClientInit(s1)
            var input = auth.input
            var output = auth.output
            var wrapKey = auth.wrapKey
            rawInput = input
            rawOutput = output

            // S7: Cleartext Apple Prelude & SessionSelect check
            stage = "S7"

            val first2 = ByteArray(2).also { input.readFully(it) }
            val firstLen = ((first2[0].toInt() and 0xff) shl 8) or (first2[1].toInt() and 0xff)

            var cmd = 0
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

                val shortUser = if (username.equals("James Kasyan", ignoreCase = true)) "jameskasyan" else username.lowercase().replace(" ", "")
                val uBytes = ByteArray(64)
                val srcU = shortUser.toByteArray(Charsets.UTF_8)
                System.arraycopy(srcU, 0, uBytes, 0, minOf(srcU.size, 63))

                val pBytes = ByteArray(64)
                val srcP = password.toByteArray(Charsets.UTF_8)
                System.arraycopy(srcP, 0, pBytes, 0, minOf(srcP.size, 63))

                cmd = if (shareConsole) 0 else if ((sessionState and 4) != 0) 2 else if ((sessionState and 2) != 0) 1 else 0

                if (cmd == 2) {
                    // Apple Screen Sharing two-TCP convention for cmd=2 (alt-session):
                    // Close conn1, open fresh conn2, redo auth and clientInit, then send cmd=2
                    log("S7: alt-session cmd=2: performing two-TCP reset (closing conn1, opening conn2)")
                    try { s1.close() } catch (ignored: Exception) {}
                    val s2 = Socket()
                    socket = s2
                    auth = doAuthAndClientInit(s2)
                    input = auth.input
                    output = auth.output
                    wrapKey = auth.wrapKey
                    rawInput = input
                    rawOutput = output

                    // Read re-issued SessionSelect prompt
                    val reissuedLen = input.readUnsignedShort()
                    check(reissuedLen == 74) { "Expected reissued SessionSelect length 74, got $reissuedLen" }
                    val reissuedBuf = ByteArray(74).also { input.readFully(it) }
                    log("S7: conn2 re-read SessionSelect prompt (74 bytes)")

                    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"))
                    val encUser = cipher.doFinal(uBytes)
                    val encPass = cipher.doFinal(pBytes)

                    // 200-byte body layout (202 bytes on wire) matching renegadelink/iShareScreen
                    val resp = ByteBuffer.allocate(202).order(ByteOrder.BIG_ENDIAN)
                    resp.putShort(200.toShort()) // size = 200
                    resp.putShort(2.toShort())   // version = 2
                    resp.putShort(0.toShort())   // reserved = 0
                    resp.putShort(0.toShort())   // reserved = 0
                    resp.put(2.toByte())         // cmd = 2
                    resp.put(0.toByte())         // pad = 0
                    resp.put(ByteArray(64))      // 64 bytes ZEROES (offsets 8..71)
                    resp.put(encUser)            // 64 bytes encrypted username (offsets 72..135)
                    resp.put(encPass)            // 64 bytes encrypted password (offsets 136..199)

                    output.write(resp.array())
                    output.flush()
                    log("S7: Sent SessionSelect Virtual Multi-Session (cmd=2, user='$shortUser', 202 bytes)")
                } else {
                    // cmd = 0 (share console) or 1
                    val resp = ByteBuffer.allocate(74).order(ByteOrder.BIG_ENDIAN)
                    resp.putShort(72.toShort()) // size = 72
                    resp.putShort(2.toShort())  // version = 2
                    resp.putShort(0.toShort())  // reserved = 0
                    resp.putShort(0.toShort())  // reserved = 0
                    resp.put(cmd.toByte())      // cmd = 0 or 1
                    resp.put(0.toByte())        // pad = 0
                    resp.put(uBytes)            // username (64 bytes)

                    output.write(resp.array())
                    output.flush()
                    log("S7: Sent SessionSelect console response (cmd=$cmd, user='$username', 74 bytes)")
                }

                // Read server response (ack)
                val replySize = try {
                    input.readUnsignedShort()
                } catch (e: java.net.SocketTimeoutException) {
                    if (cmd == 2) {
                        throw java.io.IOException("SessionSelect multi-session login timed out awaiting macOS off-console agent. Verify Fast User Switching is enabled in macOS System Settings.", e)
                    } else {
                        throw e
                    }
                }
                val replyBytes = ByteArray(replySize).also { input.readFully(it) }
                val replyStatus = if (replySize >= 3) (replyBytes[2].toInt() and 0xff) else -1
                log("S7: Received SessionSelect reply ($replySize bytes, status=$replyStatus)")
                if (replyStatus != 0) {
                    val detail = if (replyStatus == 1) "User rejected screen sharing permission" else "SessionSelect status $replyStatus"
                    throw java.io.IOException("Mac Screen Sharing declined connection ($detail)")
                }

                // Send ViewerInfo (0x21) with APPLE_VIEWER_COMMAND_MASK + APPLE_0X12_FOLLOWUP
                val vInfo = ByteBuffer.allocate(42 + 32).order(ByteOrder.BIG_ENDIAN)
                vInfo.put(0x21.toByte())
                vInfo.put(0.toByte())
                vInfo.putShort(62.toShort()) // msgSize = 0x3e
                vInfo.putShort(1.toShort())  // msgVersion = 1
                vInfo.putInt(2)              // app_id = 2
                vInfo.putInt(6); vInfo.putInt(1); vInfo.putInt(0) // app_ver (6, 1, 0)
                vInfo.putInt(15); vInfo.putInt(3); vInfo.putInt(0) // os_ver (15, 3, 0)
                vInfo.put(APPLE_VIEWER_COMMAND_MASK)
                output.write(vInfo.array())
                output.write(APPLE_0X12_FOLLOWUP)
                output.flush()
                log("S7: Sent ViewerInfo (0x21) + 0x12 followup")
                Thread.sleep(100)

                // Send plaintext SetEncodings
                output.write(buildSetEncodings(HP_ENCODINGS_FULL))
                output.flush()
                log("S7: Sent plaintext SetEncodings (HP_ENCODINGS_FULL)")

                // Read 0x44f Rekey
                val msgType = input.readUnsignedByte()
                val pad = input.readUnsignedByte()
                val numRects = input.readUnsignedShort()
                val rx = input.readUnsignedShort()
                val ry = input.readUnsignedShort()
                val rw = input.readUnsignedShort()
                val rh = input.readUnsignedShort()
                val encoding = input.readInt()
                check(encoding == 0x44f) { "Expected rekey encoding 0x44f (1103), got 0x${Integer.toHexString(encoding)}" }
                val rekeyPayload = ByteArray(36).also { input.readFully(it) }
                val layer = MacHevcRecordLayer.unwrapRekey(wrapKey, rekeyPayload)
                recordLayer = layer
                log("S7: Rekey unwrap success (generation=${layer.generation})")
            } else {
                // Direct rekey path
                val msgType = first2[0].toInt() and 0xff
                val pad = first2[1].toInt() and 0xff
                val numRects = input.readUnsignedShort()
                val rx = input.readUnsignedShort()
                val ry = input.readUnsignedShort()
                val rw = input.readUnsignedShort()
                val rh = input.readUnsignedShort()
                val encoding = input.readInt()
                check(encoding == 0x44f) { "Expected rekey encoding 0x44f, got 0x${Integer.toHexString(encoding)}" }
                val rekeyPayload = ByteArray(36).also { input.readFully(it) }
                val layer = MacHevcRecordLayer.unwrapRekey(wrapKey, rekeyPayload)
                recordLayer = layer
                log("S7: Direct rekey unwrap success (generation=${layer.generation})")
            }

            // Send PostEncryptionToggle (0x12)
            output.write(byteArrayOf(0x12, 0x00, 0x00, 0x02, 0x00, 0x01, 0x00, 0x00))
            output.flush()
            log("S7: Sent PostEncryptionToggle (0x12)")
            Thread.sleep(200)

            // S8: Record Layer ACTIVE
            stage = "S8"
            val layer = checkNotNull(recordLayer) { "Rekey unwrap failed" }
            log("S8: Record Layer ACTIVE")

            // 1. Send encrypted SetEncodings (HP_ENCODINGS_FULL)
            val encodings = buildSetEncodings(HP_ENCODINGS_FULL)
            layer.writeRecord(output, encodings)
            log("S8: Sent encrypted SetEncodings (0x02)")

            // 2. Build and send encrypted 0x1c MediaStreamOptions offer with tilesPerFrame = 1
            val keys = MacHevcNegotiationKeys.random()
            negotiationKeys = keys
            val (videoOffer, audioOffer) = MacHevcOffers.createOffers(tilesPerFrame = 1, audioEnabled = false)
            val msg1c = MacHevcOffers.build0x1cMessage(audioOffer, videoOffer, keys, altSession = (cmd == 2))
            layer.writeRecord(output, msg1c)
            log("S8: Sent encrypted 0x1c MediaStreamOptions offer (${msg1c.size} bytes, tilesPerFrame=1)")

            // 3. Send encrypted FramebufferUpdateRequest (0x03)
            val fbur = buildFramebufferUpdateRequest(width, height)
            layer.writeRecord(output, fbur)
            log("S8: Sent encrypted 0x03 FBU request")

            // Decrypt first record from server
            val record = layer.readRecord(input)
            val recType = record[0].toInt() and 0xff
            log("S8 GATE PASS: Decrypted first record (type=0x${Integer.toHexString(recType)}, len=${record.size})")

            // Check if first record contains canvas dimensions
            val dims = MacHevcOffers.extractCanvasDims(record)
            if (dims != null) {
                log("S8: Extracted canvas dimensions: ${dims.first}x${dims.second} (tiles=${dims.third})")
                width = dims.first
                height = dims.second
                onResolutionChanged?.invoke(width, height)
            }

            // Initialize UDP SRTP receiver and hardware MediaCodec decoder pipeline
            val pipeline = MacHevcVideoPipeline(
                host = host,
                videoPort = 5901,
                videoKeyBlob = keys.videoKeyS,
                surface = currentSurface,
                width = width,
                height = height,
                onFrameDecoded = {
                    // Decoded hardware frame rendered directly to Surface
                },
                logCallback = { log(it) }
            )
            videoPipeline = pipeline
            pipeline.start()
            log("S8: Launched MacHevcVideoPipeline on UDP 5901 with MediaCodec hardware HEVC decoding")

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
        log("S9: Reached steady-state (S9) on the control path with record layer active")
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
                            if (totalInflated > 0 && (videoPipeline?.framesDecoded?.get() ?: 0L) == 0L) {
                                updatePixels(rx, ry, rw, rh, decomp)
                            }
                            log("S9: Decompressed ZLIB rect: $totalInflated / $expectedPixels bytes (w=$rw, h=$rh)")
                        }
                        0x451 -> { // AppleDisplayLayout
                            val payloadLen = stream.readUnsignedShort()
                            val data = ByteArray(payloadLen)
                            stream.readFully(data)
                            val resolved = parseAppleDisplayLayout(data)
                            if (resolved != null && (resolved.first != width || resolved.second != height)) {
                                val (newW, newH) = resolved
                                log("S9: AppleDisplayLayout resized display: ${width}x${height} -> ${newW}x${newH}")
                                width = newW
                                height = newH
                                lastW = newW
                                lastH = newH
                                framePixels = null
                                frameBitmap?.recycle()
                                frameBitmap = null
                                onResolutionChanged?.invoke(newW, newH)
                            } else {
                                log("S9: AppleDisplayLayout (${data.size} bytes), re-arming with 0x09 and 0x03")
                            }
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
                            if (rawLen > 0 && (videoPipeline?.framesDecoded?.get() ?: 0L) == 0L) {
                                updatePixels(rx, ry, rw, rh, data)
                            }
                        }
                        else -> {
                            log("S9: Unknown or unhandled encoding 0x${Integer.toHexString(enc)}, ignoring per §12")
                        }
                    }
                }
            }
            0x14 -> { // MiscStatus
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
                log("S9: Received MediaStreamOptions (0x1c) answer: msgSize=$msgSize")
                val dims = MacHevcOffers.extractCanvasDims(payload)
                if (dims != null && (dims.first != width || dims.second != height)) {
                    log("S9: 0x1c updated canvas dimensions: ${dims.first}x${dims.second} (tiles=${dims.third})")
                    width = dims.first
                    height = dims.second
                    onResolutionChanged?.invoke(width, height)
                }
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

    fun sendDisplayConfiguration(w: Int = width, h: Int = height, displayType: Int = 0) {
        val out = checkNotNull(recordOut) { "Record stream not initialized" }
        val config = buildDisplayConfiguration(w, h, displayType)
        log("S9: Sending SetDisplayConfiguration (0x1d, ${config.size} bytes: ${w}x${h}, displayType=$displayType)")
        out.write(config)
        out.flush()
    }

    fun sendMediaStreamOffer(tilesPerFrame: Int = 1, audioEnabled: Boolean = false): ByteArray {
        val out = checkNotNull(recordOut) { "Record stream not initialized" }
        val layer = checkNotNull(recordLayer) { "Record layer not established" }
        val keys = negotiationKeys ?: MacHevcNegotiationKeys.random().also { negotiationKeys = it }
        val (videoOffer, audioOffer) = MacHevcOffers.createOffers(tilesPerFrame, audioEnabled)
        val offer = MacHevcOffers.build0x1cMessage(audioOffer, videoOffer, keys, altSession = false)
        log("S9: Sending MediaStreamOptions offer (0x1c, ${offer.size} bytes)")
        layer.writeRecord(out, offer)
        out.flush()
        return offer
    }

    fun updateDisplay(targetWidth: Int, targetHeight: Int, displayType: Int = 4) {
        if (stage != "S8" && stage != "S9") return
        val w = targetWidth.coerceIn(200, 8192) and -2
        val h = targetHeight.coerceIn(200, 8192)
        log("S9: updateDisplay requested: ${w}x${h} (displayType=$displayType)")
        sendDisplayConfiguration(w, h, displayType)
        requestUpdate(incremental = false)
    }

    fun sendMouseEvent(x: Int, y: Int, flags: Int) {
        var mask = 0
        if ((flags and 0x8000) != 0) {
            if ((flags and 0x1000) != 0) mask = mask or 1 // left
            if ((flags and 0x2000) != 0) mask = mask or 4 // right
            if ((flags and 0x4000) != 0) mask = mask or 2 // middle
        }
        sendPointer(x, y, mask)
    }

    fun sendKeyEvent(keysym: Int, isDown: Boolean) {
        val out = recordOut ?: return
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.put(4.toByte()) // KeyEvent
        buf.put(if (isDown) 1.toByte() else 0.toByte())
        buf.putShort(0.toShort()) // pad
        buf.putInt(keysym)
        out.write(buf.array())
        out.flush()
        log("S9: Sent KeyEvent(keysym=0x${Integer.toHexString(keysym)}, down=$isDown)")
    }

    @Volatile private var isStreaming = false

    fun startStreaming(surface: Surface?, onFinished: (String?) -> Unit = {}) {
        setSurface(surface)
        isStreaming = true
        Thread({
            var errorMsg: String? = null
            try {
                while (isStreaming && (stage == "S8" || stage == "S9")) {
                    val result = readNextFramebufferUpdate()
                    if (result.messageType == 0) {
                        requestUpdate(incremental = true)
                    }
                }
            } catch (e: Exception) {
                if (isStreaming) {
                    log("S9: Streaming error: ${e.message}")
                    errorMsg = e.message ?: "Disconnected"
                }
            } finally {
                isStreaming = false
                onFinished(errorMsg)
            }
        }, "MacHevcStreamWorker").start()
    }

    fun stopStreaming() {
        isStreaming = false
    }

    companion object {
        val APPLE_VIEWER_COMMAND_MASK = ByteArray(32).apply {
            this[0] = 0xb0.toByte()
            this[2] = 0x0c.toByte()
            this[3] = 0x03.toByte()
            this[4] = 0x90.toByte()
            this[10] = 0x40.toByte()
        }

        val APPLE_0X12_FOLLOWUP = byteArrayOf(
            0x12, 0x00,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x01
        )

        val HP_ENCODINGS_FULL = listOf(
            1010, 1011, 1002, 6, 16, 1104, 1100, -223, 1101, 1105, 1107, 1109, 1110
        )

        fun parseAppleDisplayLayout(data: ByteArray): Pair<Int, Int>? {
            if (data.size < 10) return null
            val scaledW = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
            val scaledH = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
            val backingW = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
            val backingH = ((data[8].toInt() and 0xff) shl 8) or (data[9].toInt() and 0xff)
            if (backingW in 200..8192 && backingH in 200..8192) {
                return Pair(backingW, backingH)
            }
            if (scaledW in 200..8192 && scaledH in 200..8192) {
                return Pair(scaledW, scaledH)
            }
            // Fallback: check display_record global bounds (+0x14 into 56-byte record, following 14-byte header)
            if (data.size >= 14 + 56) {
                val gw = ((data[14 + 0x18].toInt() and 0xff) shl 8) or (data[14 + 0x19].toInt() and 0xff)
                val gh = ((data[14 + 0x1a].toInt() and 0xff) shl 8) or (data[14 + 0x1b].toInt() and 0xff)
                if (gw in 200..8192 && gh in 200..8192) {
                    return Pair(gw, gh)
                }
            }
            return null
        }
    }

    override fun close() {
        stopStreaming()
        videoPipeline?.close()
        videoPipeline = null
        zlibInflater.end()
        frameBitmap?.recycle()
        frameBitmap = null
        framePixels = null
        currentSurface = null
        runCatching { rawInput?.close() }
        runCatching { rawOutput?.close() }
        runCatching { socket?.close() }
        socket = null
        stage = "CLOSED"
    }
}
