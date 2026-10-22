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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    private val sessionUsername: String = username,
    private val authenticationSpacingMs: Long = 0,
    private val mediaDatagramSender: ((Int, ByteArray) -> Unit)? = null,
    private val parameterSetObserver: ((Int, ByteArray) -> Unit)? = null,
    private val audioEnabled: Boolean = false,
    private val audioObserver: ((ByteArray) -> Unit)? = null,
    private val preferAvc: Boolean = false,
    private val authenticationGate: (() -> Unit)? = null,
    private val preferRsaAuthentication: Boolean = true,
    private val preferSrpAuthentication: Boolean = false,
    private val sessionLoginUsername:String=sessionUsername,
    private val encodedVideoObserver:((Long,ByteArray)->Unit)?=null,
    private val selectVirtualDisplay:Boolean=false,
    private val selectVirtualDisplayByIndex:Boolean=false,
    private val onAuthenticated:()->Unit={},
    private val controlSocketFactory:(()->Socket)?=null,
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
    @Volatile internal var controlUpdatesEnabled=true
    internal var sessionSelectReply:ByteArray?=null; private set
    @Volatile internal var selectedVirtualDisplay:MacDisplaySelection?=null; private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var isAltSession: Boolean = false
        private set

    var recordIn: DataInputStream? = null
        private set
    var recordOut: DataOutputStream? = null
        private set
    private val cursorCache=MacCursorCache()
    @Volatile internal var cursor:MacCursor?=null;private set
    private val zlibInflater = Inflater()
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MacHevc-Writer").apply { isDaemon = true }
    }

    @Volatile var currentSurface: Surface? = null
    var onResolutionChanged: ((Int, Int) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    private var frameBitmap: Bitmap? = null
    private var framePixels: IntArray? = null

    var videoPipeline: MacHevcVideoPipeline? = null
        private set
    var negotiationKeys: MacHevcNegotiationKeys? = null
        private set
    var currentOffers: MacHevcOffersBundle? = null
        private set
    private val layoutRefresh=MacLayoutRefresh()

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
            "${pipeline?.decoderDescription} ($decoded frames)"
        } else if (pkts > 0L) {
            "${pipeline?.decoderDescription}; $pkts packets, no decoded frames"
        } else {
            "Apple streaming ($stage; waiting for video)"
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

    private data class AuthInitResult(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val wrapKey: ByteArray,
        val serverInit: MacHevcServerInit
    )

    private fun doAuthAndClientInit(sock: Socket): AuthInitResult {
        authenticationGate?.invoke()
        stage = "S0"
        if(!sock.isConnected) sock.connect(InetSocketAddress(host, port), 4000)
        sock.tcpNoDelay=true
        sock.soTimeout = 8000
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
        if (!typeList.contains(30) && !(preferRsaAuthentication && typeList.contains(33))) {
            log("S2: Server did not offer Apple DH type 30 (offered: $typeList)")
            throw VncAuthenticationException()
        }

        val wrapKey: ByteArray
        if(preferRsaAuthentication && typeList.contains(33)) {
            stage="S4"
            log("S3: Selected advertised RSA1 security type 33; no retry on rejection")
            wrapKey=if(preferSrpAuthentication) MacSrpAuthentication.authenticate(input,output,sessionUsername,password)
                else MacRsaAuthentication.authenticate(input,output,username,password)
            stage="S5"
            log("S5: RSA1 ${if(preferSrpAuthentication) "SRP mutual proof" else "non-SRP"} SecurityResult = 0 (Authentication Success)")
        } else {
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
            wrapKey = MessageDigest.getInstance("MD5").digest(shared)

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

        }

        onAuthenticated()
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
     * Executes the bootstrap through S8 and sends the media offer; the streaming reader handles replies.
     * Throws an exception on any protocol or integrity failure, closing the socket immediately.
     */
    fun start() {
        val s1 = controlSocketFactory?.invoke() ?: Socket().apply { tcpNoDelay=true }
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

            val first2 = MacSessionPrelude.optionalHeader(input,s1.soTimeout) { s1.soTimeout=it }
            val firstLen = first2?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }

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

                // Authentication and GUI-session identity need not use the same saved alias.
                val loginUser=sessionLoginUsername

                cmd = if (shareConsole) 0 else if ((sessionState and 4) != 0) 2 else if ((sessionState and 2) != 0) 1 else 0
                isAltSession = (cmd == 2)

                if (cmd == 2) {
                    // Apple Screen Sharing two-TCP convention for cmd=2 (alt-session):
                    // Close conn1, open fresh conn2, redo auth and clientInit, then send cmd=2
                    log("S7: alt-session cmd=2: performing two-TCP reset (closing conn1, opening conn2)")
                    try { s1.close() } catch (ignored: Exception) {}
                    if (authenticationSpacingMs > 0) {
                        log("S7: explicit probe authentication spacing ${authenticationSpacingMs}ms")
                        Thread.sleep(authenticationSpacingMs)
                    }
                    val s2 = controlSocketFactory?.invoke() ?: Socket().apply { tcpNoDelay=true }
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

                    output.write(MacSessionSelect.virtualLogin(loginUser,password,wrapKey))
                    output.flush()
                    log("S7: Sent SessionSelect Virtual Multi-Session (cmd=2, user='$loginUser', 202 bytes)")
                } else {
                    output.write(MacSessionSelect.consoleLogin(cmd,loginUser))
                    output.flush()
                    log("S7: Sent SessionSelect console response (cmd=$cmd, user='$loginUser', 74 bytes)")
                }

                // Read server response (ack)
                val replySize = try {
                    input.readUnsignedShort()
                } catch (e: java.net.SocketTimeoutException) {
                    if (cmd == 2) {
                        throw java.io.IOException("SessionSelect multi-session reply timed out; off-console agent, permissions and transport remain unverified.", e)
                    } else {
                        throw java.io.IOException("Timed out waiting for Mac user to accept screen sharing request. Check the Mac screen and click 'Share Display'.", e)
                    }
                }
                val replyBytes = ByteArray(replySize).also { input.readFully(it) }
                sessionSelectReply=replyBytes.copyOf()
                val replyStatus = if (replySize >= 3) (replyBytes[2].toInt() and 0xff) else -1
                log("S7: Received SessionSelect reply ($replySize bytes, status=$replyStatus)")
                if (replyStatus != 0) {
                    val detail = if (replyStatus == 1) "User rejected screen sharing permission" else "SessionSelect status $replyStatus"
                    throw MacSessionRejectedException("Mac Screen Sharing declined connection ($detail)")
                }

                // Send ViewerInfo (0x21) with APPLE_VIEWER_COMMAND_MASK + APPLE_0X12_FOLLOWUP
                val vInfo = ByteBuffer.allocate(34 + 32).order(ByteOrder.BIG_ENDIAN)
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
                log("S7: Sent ViewerInfo (0x21, 66 bytes) + 0x12 followup (12 bytes)")

                if (cmd == 2) {
                    // Send SetDisplayConfiguration for virtual display (displayType = 4)
                    val vdc = buildDisplayConfiguration(1920, 1080, displayType = 4, hidpiScale = 1f)
                    output.write(vdc)
                    output.flush()
                    log("S7: Sent SetDisplayConfiguration (0x1d, ${vdc.size} bytes)")
                }

                // Send plaintext SetEncodings
                output.write(buildSetEncodings(HP_ENCODINGS_FULL))
                output.flush()
                log("S7: Sent plaintext SetEncodings (HP_ENCODINGS_FULL)")

                // Read until 0x44f Rekey (handles 0x14 UserSessionChanged and preceding rects)
                val layer = readUntilEnc1103(input, wrapKey)
                recordLayer = layer
                log("S7: Rekey unwrap success (generation=${layer.generation})")
            } else if(first2==null) {
                // Same-account servers wait for the viewer prelude rather than sending SessionSelect.
                val viewer=ByteBuffer.allocate(66).order(ByteOrder.BIG_ENDIAN)
                viewer.put(0x21.toByte());viewer.put(0.toByte());viewer.putShort(62.toShort())
                viewer.putShort(1.toShort());viewer.putInt(2)
                viewer.putInt(6);viewer.putInt(1);viewer.putInt(0)
                viewer.putInt(15);viewer.putInt(3);viewer.putInt(0)
                viewer.put(APPLE_VIEWER_COMMAND_MASK)
                output.write(viewer.array());output.write(APPLE_0X12_FOLLOWUP);output.flush()
                if(!shareConsole) output.write(buildDisplayConfiguration(1920,1080,displayType=4,hidpiScale=1f))
                output.write(buildSetEncodings(HP_ENCODINGS_FULL));output.flush()
                recordLayer=readUntilEnc1103(input,wrapKey)
                log("S7: No SessionSelect prompt; standard viewer/display prelude and rekey succeeded")
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

            // S8: Record Layer ACTIVE
            stage = "S8"
            val layer = checkNotNull(recordLayer) { "Rekey unwrap failed" }
            log("S8: Record Layer ACTIVE")

            // 1. Send encrypted SetEncodings (HP_ENCODINGS_FULL)
            val encodings = buildSetEncodings(HP_ENCODINGS_FULL)
            layer.writeRecord(output, encodings)
            log("S8: Sent encrypted SetEncodings (0x02)")

            // 2. Build 0x1c MediaStreamOptions offer with tilesPerFrame = 1
            val keys = MacHevcNegotiationKeys.random()
            negotiationKeys = keys
            val offers = MacHevcOffers.createOffers(tilesPerFrame = 1, audioEnabled = audioEnabled, preferAvc = preferAvc)
            currentOffers = offers
            val msg1c = MacHevcOffers.build0x1cMessage(offers.audioOffer, offers.videoOffer, keys, altSession = (cmd == 2))

            // Initialize UDP SRTP receiver, pinhole puncher, and hardware MediaCodec decoder BEFORE 0x1c
            val pipeline = MacHevcVideoPipeline(
                host = host,
                videoPort = 5901,
                ctrlPort = 5900,
                videoKeyBlob = keys.videoKeyS,
                videoKeyBlobV = keys.videoKeyV,
                ourVideoSsrc = offers.ourVideoSsrc,
                surface = currentSurface,
                width = width,
                height = height,
                datagramSender = mediaDatagramSender,
                parameterSetObserver = parameterSetObserver,
                audioKeyBlob = if (audioEnabled) keys.audioKeyS else null,
                audioKeyBlobV = if (audioEnabled) keys.audioKeyV else null,
                ourAudioSsrc = offers.ourAudioSsrc,
                audioObserver = audioObserver,
                preferAvc = preferAvc,
                encodedVideoObserver=encodedVideoObserver,
                onFrameDecoded = {
                    // Decoded hardware frame rendered directly to Surface
                },
                logCallback = { log(it) }
            )
            videoPipeline = pipeline
            pipeline.start()
            log("S8: Launched MacHevcVideoPipeline on UDP 5901/5900 with active pinhole punches and MediaCodec HEVC decoding")

            // 3. Write encrypted 0x1c MediaStreamOptions offer
            layer.writeRecord(output, msg1c)
            log("S8: Sent encrypted 0x1c MediaStreamOptions offer (${msg1c.size} bytes, tilesPerFrame=1)")

            // Do not wait for a fixed batch of control replies. Video and metadata arrive
            // independently; initStreamingLayer/startStreaming consume all replies in order.
            log("S8: Media offer sent; control replies will be read while video starts")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun readUntilEnc1103(input: DataInputStream, wrapKey: ByteArray): MacHevcRecordLayer {
        var layer: MacHevcRecordLayer? = null
        while (layer == null) {
            val msgType = input.readUnsignedByte()
            if (msgType == 0x14) {
                // UserSessionChanged (8 bytes total: type + 7 bytes)
                val skip = ByteArray(7)
                input.readFully(skip)
                log("S7: Received UserSessionChanged (0x14)")
                continue
            }
            if (msgType == 0x00) {
                // FramebufferUpdate
                val pad = input.readUnsignedByte()
                val numRects = input.readUnsignedShort()
                log("S7: Received FramebufferUpdate (0x00) with $numRects rects")
                for (i in 0 until numRects) {
                    val rx = input.readUnsignedShort()
                    val ry = input.readUnsignedShort()
                    val rw = input.readUnsignedShort()
                    val rh = input.readUnsignedShort()
                    val enc = input.readInt()
                    if (enc == 0x44f || enc == 1103) {
                        val rekey = ByteArray(36).also { input.readFully(it) }
                        layer = MacHevcRecordLayer.unwrapRekey(wrapKey, rekey)
                        log("S7: Found 0x44f Rekey in rect $i of $numRects, unwrapped successfully (generation=${layer.generation})")
                        break
                    } else if (enc == 1010 || enc == 1011) {
                        val sz = input.readUnsignedShort()
                        val skip = ByteArray(sz)
                        input.readFully(skip)
                    }
                }
                if (layer != null) {
                    break
                }
            } else {
                log("S7: Unexpected msgType 0x${Integer.toHexString(msgType)} while awaiting Rekey")
            }
        }
        return checkNotNull(layer) { "Server did not send enc1103 Rekey" }
    }

    fun buildDisplayConfiguration(
        w: Int = 1920,
        h: Int = 1080,
        displayType: Int = 4,
        hidpiScale: Float = 2.0f,
        hdr: Boolean = false,
        displayName: String = "Farside Virtual Display"
    ): ByteArray {
        val modeCount = 5
        val diSize = 0x9c + 28 * modeCount // 296
        val msgSize = 8 + diSize // 304
        val totalLen = 4 + msgSize // 308
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // Front header (12 bytes)
        buf.put(0x1d.toByte())        // message_type = 29 (0x1d)
        buf.put(0.toByte())           // padding = 0
        buf.putShort(msgSize.toShort()) // message_size = 304
        buf.putShort(1.toShort())     // version = 1
        buf.putShort(1.toShort())     // display_count = 1
        buf.putInt(0)                 // flags = 0

        // Display descriptor (296 bytes)
        buf.putShort(diSize.toShort()) // display_info_size = 296
        val nameBytes = displayName.toByteArray(Charsets.UTF_8).let {
            if (it.size > 119) it.copyOfRange(0, 119) else it
        }
        val nameRegion = ByteArray(120)
        System.arraycopy(nameBytes, 0, nameRegion, 0, nameBytes.size)
        buf.put(nameRegion)           // 120 bytes display_info_region

        buf.putInt(1)                 // display_flags = 1 (DYNAMIC_RESOLUTION)
        buf.putInt(displayType)       // display_type (4 = virtual display)
        buf.putFloat(369.45456f)      // physical_width_mm
        buf.putFloat(207.81818f)      // physical_height_mm
        buf.putInt(3840)              // max_width = 3840
        buf.putInt(2160)              // max_height = 2160
        buf.putShort(0.toShort())     // current_mode_index = 0
        buf.putShort(0.toShort())     // preferred_mode_index = 0
        buf.putInt(7)                 // rotations / reserved = 7
        buf.putShort(modeCount.toShort()) // mode_count = 5

        // Heterogeneous modes
        val nativeModes = arrayOf(
            intArrayOf(3840, 2160, 1920, 1080),
            intArrayOf(2880, 1800, 1440, 900),
            intArrayOf(3840, 2160, 1920, 1080),
            intArrayOf(2880, 1620, 1440, 810),
            intArrayOf(2624, 1696, 1312, 848)
        )
        val sx = if (w > 0) w.toDouble() / 1920.0 else 1.0
        val sy = if (h > 0) h.toDouble() / 1080.0 else 1.0
        val modeFlags = if (hdr) 1 else 0

        for (i in 0 until modeCount) {
            val base = nativeModes[i]
            val msw = (base[2] * sx + 0.5).toInt()
            val msh = (base[3] * sy + 0.5).toInt()
            val mw = (msw * hidpiScale + 0.5).toInt()
            val mh = (msh * hidpiScale + 0.5).toInt()

            buf.putInt(mw)
            buf.putInt(mh)
            buf.putInt(msw)
            buf.putInt(msh)
            buf.putDouble(60.0)
            buf.putInt(modeFlags)
        }

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

    fun initStreamingLayer() {
        val layer = checkNotNull(recordLayer) { "Record layer not established" }
        val rIn = checkNotNull(rawInput) { "Input stream not established" }
        val rOut = checkNotNull(rawOutput) { "Output stream not established" }

        val inStream = object : InputStream() {
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
                    val rec = try { layer.readRecord(rIn) }
                        catch (_: java.io.EOFException) { return false }
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
        requestUpdate(incremental=false)
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
                            if(selectVirtualDisplay && isAltSession) selectNegotiatedDisplay(data)
                            val newLayout=resolved!=null && layoutRefresh.changed(data)
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
                                videoPipeline?.updateDimensions(newW, newH)
                                onResolutionChanged?.invoke(newW, newH)
                            } else {
                                log("S9: AppleDisplayLayout (${data.size} bytes)")
                            }
                            // A login/session switch can keep the same dimensions. Re-arm
                            // control delivery on every layout and media on new layout data,
                            // including the first layout; duplicate acknowledgements must not loop.
                            requestUpdate(incremental=false)
                            if(newLayout) { reofferMediaStream();videoPipeline?.requestKeyframe() }
                        }
                        1104, 0x450 -> { // CursorImage
                            val cacheId = stream.readInt()
                            val compLen = stream.readInt()
                            if(compLen !in 0..1024*1024) throw MacHevcProtocolException("Invalid cursor payload size")
                            val data=ByteArray(compLen).also(stream::readFully)
                            cursor=cursorCache.update(cacheId,rw,rh,rx,ry,data)
                        }
                        1010, 1011, 1107, 1109, 1110, 0x3ea, 0x3f2, 0x3f3, 0x453, 0x455, 0x456 -> {
                            val payloadLen = stream.readUnsignedShort()
                            val data = ByteArray(payloadLen)
                            stream.readFully(data)
                            log("S9: Apple pseudo-encoding 0x${Integer.toHexString(enc)} ($enc, $payloadLen bytes)")
                            if (enc == 1010 || enc == 0x3f2) {
                                val dims = MacHevcOffers.extractCanvasDims(data)
                                if (dims != null && (dims.first != width || dims.second != height)) {
                                    log("S9: 1010/0x3f2 updated canvas dimensions: ${dims.first}x${dims.second} (tiles=${dims.third})")
                                    width = dims.first
                                    height = dims.second
                                    videoPipeline?.updateDimensions(dims.first, dims.second)
                                    onResolutionChanged?.invoke(width, height)
                                }
                            }
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

    internal val pointerEventsSent=java.util.concurrent.atomic.AtomicLong()
    internal val keyEventsSent=java.util.concurrent.atomic.AtomicLong()
    private val pointerQueue=MacPointerQueue({ writerExecutor.execute(it) }) { point ->
        try {
            val out=recordOut
            if(out!=null && stage!="CLOSED") {
                out.write(checkNotNull(recordLayer).pointerEvent(point.x,point.y,point.buttons,width,height));out.flush()
                pointerEventsSent.incrementAndGet()
            }
        } catch(_:Exception) { log("Pointer output stopped") }
    }
    private val mouseInput=MacMouseInput { x,y,mask ->
        if(stage!="CLOSED" && !writerExecutor.isShutdown) pointerQueue.offer(x,y,mask)
    }
    fun sendPointer(x:Int,y:Int,buttonMask:Int=0) = mouseInput.pointer(x,y,buttonMask)

    private fun selectNegotiatedDisplay(data:ByteArray) {
        val selection=MacDisplaySelection.parse(data) ?: return
        if(selection==selectedVirtualDisplay) return
        selectedVirtualDisplay=selection
        writerExecutor.execute {
            val out=recordOut ?: return@execute
            out.write(buildControlUpdateRequest(false))
            out.write(selection.request(selectVirtualDisplayByIndex))
            out.flush()
            videoPipeline?.requestKeyframe()
            log("S9: Requested sole negotiated display ${selection.id} using ${if(selectVirtualDisplayByIndex) "first index" else "display ID"}, ${selection.width}x${selection.height}; server-driven control, no further polling")
        }
    }

    fun requestUpdate(incremental: Boolean = true) {
        if (!controlUpdatesEnabled || selectedVirtualDisplay!=null || stage == "CLOSED" || writerExecutor.isShutdown) return
        try {
            writerExecutor.execute {
                try {
                    if(!controlUpdatesEnabled || selectedVirtualDisplay!=null) return@execute
                    val out = recordOut ?: return@execute
                    val fbur = buildControlUpdateRequest(incremental)
                    out.write(fbur)
                    out.flush()
                    log("S9: Requested 1x1 control FramebufferUpdate (incremental=$incremental)")
                } catch (e: Exception) {
                    log("requestUpdate error: ${e.message}")
                }
            }
        } catch (ignored: RejectedExecutionException) {}
    }

    fun sendDisplayConfiguration(w: Int = width, h: Int = height, displayType: Int = 0) {
        if (stage == "CLOSED" || writerExecutor.isShutdown) return
        try {
            writerExecutor.execute {
                try {
                    val out = recordOut ?: return@execute
                    val config = buildDisplayConfiguration(w, h, displayType, hidpiScale=1f)
                    log("S9: Sending SetDisplayConfiguration (0x1d, ${config.size} bytes: ${w}x${h}, displayType=$displayType)")
                    out.write(config)
                    out.flush()
                } catch (e: Exception) {
                    log("sendDisplayConfiguration error: ${e.message}")
                }
            }
        } catch (ignored: RejectedExecutionException) {}
    }

    fun reofferMediaStream() {
        val keys = negotiationKeys ?: return
        val offers = currentOffers ?: return
        if (stage == "CLOSED" || writerExecutor.isShutdown) return
        try {
            writerExecutor.execute {
                try {
                    val out = recordOut ?: return@execute
                    val msg1c = MacHevcOffers.build0x1cMessage(offers.audioOffer, offers.videoOffer, keys, altSession = isAltSession)
                    out.write(msg1c)
                    out.flush()
                    log("S9: Sent encrypted 0x1c media re-offer (${msg1c.size} bytes)")
                } catch (e: Exception) {
                    log("reofferMediaStream error: ${e.message}")
                }
            }
        } catch (ignored: RejectedExecutionException) {}
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
        // Console sharing must not turn into a curtained virtual session on window resize.
        if (shareConsole) return
        if (stage != "S8" && stage != "S9") return
        val (w,h) = MacVideoSize.fromWindow(targetWidth,targetHeight)
        log("S9: updateDisplay requested: ${w}x${h} (displayType=$displayType)")
        sendDisplayConfiguration(w, h, displayType)
    }

    fun sendMouseEvent(x:Int,y:Int,flags:Int) = mouseInput.mouse(x,y,flags)

    fun sendKeyEvent(keysym: Int, isDown: Boolean) {
        if (stage == "CLOSED" || writerExecutor.isShutdown) return
        try {
            pointerQueue.barrier {
                try {
                    val out = recordOut ?: return@barrier
                    val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    buf.put(4.toByte()) // KeyEvent
                    buf.put(if (isDown) 1.toByte() else 0.toByte())
                    buf.putShort(0.toShort()) // pad
                    buf.putInt(keysym)
                    out.write(buf.array())
                    out.flush()
                    keyEventsSent.incrementAndGet()
                } catch (e: Exception) {
                    log("sendKeyEvent error: ${e.message}")
                }
            }
        } catch (ignored: RejectedExecutionException) {}
    }

    @Volatile private var isStreaming = false
    private val savedPasswordInput=MacSavedPasswordInput(password)
    internal val canSubmitSavedPassword:Boolean get()=isStreaming && !savedPasswordInput.attempted
    internal fun submitSavedPassword():Boolean=savedPasswordInput.submit(
        active={ isStreaming && stage!="CLOSED" && recordOut!=null && !writerExecutor.isShutdown },
        enqueue={ pointerQueue.barrier(it) },
        key={ symbol,down ->
            val event=ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
                put(4);put(if(down) 1.toByte() else 0.toByte());putShort(0);putInt(symbol)
            }.array()
            checkNotNull(recordOut).apply { write(event);flush() }
        },failed={ log("Saved password input stopped; no automatic retry") },
        beforeInput={ authenticationGate?.invoke() })

    fun startStreaming(surface: Surface?, onFinished: (String?) -> Unit = {}) {
        setSurface(surface)
        // Authentication has a short deadline; an idle authenticated desktop does not.
        socket?.soTimeout = 0
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
        /** Streaming pixels use RTP. Full-screen TCP polls disturb Apple's capture path. */
        internal fun buildControlUpdateRequest(incremental:Boolean):ByteArray =
            ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                put(3.toByte());put(if(incremental) 1.toByte() else 0.toByte())
                putShort(0);putShort(0);putShort(1);putShort(1)
            }.array()

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
        try {
            pointerQueue.close()
            writerExecutor.shutdownNow()
        } catch (ignored: Exception) {}
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
