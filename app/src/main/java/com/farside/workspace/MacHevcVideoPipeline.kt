package com.farside.workspace

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP SRTP receiver and Android MediaCodec hardware HEVC decoder pipeline.
 *
 * Receives SRTP packets on UDP port 5901, decrypts using MacHevcSrtp (AES-256-CTR + HMAC-SHA1-80),
 * depacketizes Apple RFC 7798 variant HEVC NAL units with 2-byte DONL stripping,
 * and feeds Annex-B NAL units directly into hardware MediaCodec bound to the Quest Surface.
 *
 * Also manages UDP port 5900 for RTCP control, continuous pinhole punching, and legacy FIR keyframe triggers.
 */
class MacHevcVideoPipeline(
    private val host: String,
    private val videoPort: Int = 5901,
    private val ctrlPort: Int = 5900,
    videoKeyBlob: ByteArray, // 46 bytes: 32 bytes masterKey + 14 bytes masterSalt (server -> viewer)
    videoKeyBlobV: ByteArray? = null, // 46 bytes: (viewer -> server) for SRTCP
    private val ourVideoSsrc: Long = 0L,
    @Volatile var surface: Surface?,
    private var width: Int = 1920,
    private var height: Int = 1080,
    private val context: Context? = null,
    private val datagramSender: ((Int, ByteArray) -> Unit)? = null,
    private val parameterSetObserver: ((Int, ByteArray) -> Unit)? = null,
    private val audioKeyBlob: ByteArray? = null,
    audioKeyBlobV: ByteArray? = null,
    ourAudioSsrc: Long = 0,
    private val audioObserver: ((ByteArray) -> Unit)? = null,
    private val preferAvc: Boolean = false,
    private val encodedVideoObserver:((Long,ByteArray)->Unit)?=null,
    private val onFrameDecoded: () -> Unit = {},
    private val logCallback: (String) -> Unit = {}
) : Closeable {

    constructor(
        host: String,
        videoPort: Int,
        videoKeyBlob: ByteArray,
        surface: Surface?,
        width: Int = 1920,
        height: Int = 1080,
        onFrameDecoded: () -> Unit = {},
        logCallback: (String) -> Unit = {}
    ) : this(
        host = host,
        videoPort = videoPort,
        ctrlPort = 5900,
        videoKeyBlob = videoKeyBlob,
        videoKeyBlobV = null,
        ourVideoSsrc = 0L,
        surface = surface,
        width = width,
        height = height,
        context = null,
        onFrameDecoded = onFrameDecoded,
        logCallback = logCallback
    )

    init {
        require(videoKeyBlob.size == 46) { "videoKeyBlob must be 46 bytes, got ${videoKeyBlob.size}" }
    }

    private val masterKey = videoKeyBlob.copyOfRange(0, 32)
    private val masterSalt = videoKeyBlob.copyOfRange(32, 46)
    private val srtp = MacHevcSrtp(masterKey, masterSalt)
    private val srtcpEncryptor = videoKeyBlobV?.takeIf { it.size == 46 }?.let {
        MacHevcSrtcpEncryptor(it.copyOfRange(0, 32), it.copyOfRange(32, 46))
    }
    private val rtpParser = MacHevcRtpParser()

    private val isRunning = AtomicBoolean(false)
    val framesDecoded = AtomicLong(0)
    val packetsReceived = AtomicLong(0)
    val authenticatedPackets = AtomicLong(0)
    val nalUnits = AtomicLong(0)
    val serverVideoSsrc = AtomicLong(0L)
    private val videoStreams=java.util.concurrent.ConcurrentHashMap<Long,AtomicLong>()
    val videoStreamSummary: String get()=videoStreams.entries.sortedBy { it.key }.joinToString { "${it.key.toString(16)}:${it.value.get()}" }
    var audio: MacScreenAudio? = null
        private set
    private val needsRecovery=AtomicBoolean(false)
    private val avc = if(preferAvc) MacAvcStream(::log) { framesDecoded.incrementAndGet(); onFrameDecoded() }.also { stream ->
        stream.onRecovery={ needsRecovery.set(true) }
        if(encodedVideoObserver!=null) stream.accessUnitObserver={ bytes -> encodedVideoObserver.invoke(stream.activeSsrc ?: 0,bytes) }
    } else null
    val decoderDescription: String get() = avc?.description ?: "HEVC MediaCodec"
    val decodedWidth: Int get() = avc?.width ?: width
    val decodedHeight: Int get() = avc?.height ?: height
    val avcConfiguration:ByteArray? get()=avc?.configurationSnapshot()
    var avcAccessUnitObserver:((ByteArray)->Unit)?
        get()=avc?.accessUnitObserver
        set(value) { avc?.accessUnitObserver=value }
    val avcInputDrops:Long get()=avc?.inputDrops?.get() ?: 0L
    val avcAssemblyDiagnostics:String get()=avc?.assemblyDiagnostics() ?: "not AVC"
    internal fun requestKeyframe() { needsRecovery.set(true) }
    var avcPacketObserver:((ByteArray,Int,Long,Boolean,Long)->Unit)?
        get()=avc?.packetObserver
        set(value) { avc?.packetObserver=value }
    private val audioSender=audioKeyBlobV?.let { MacHevcSrtpSender(it,ourAudioSsrc) }

    private var udpSocket: DatagramSocket? = null
    private var udpCtrlSocket: DatagramSocket? = null
    private var mediaCodec: MediaCodec? = null
    private var receiverThread: Thread? = null
    private var ctrlReceiverThread: Thread? = null
    private var punchThread: Thread? = null

    private fun log(msg: String) {
        Log.i("MacHevcVideoPipeline", msg)
        logCallback(msg)
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        audio = audioKeyBlob?.let { MacScreenAudio(it, ::log, audioObserver) }

        val s = surface
        if (s != null && s.isValid) {
            initCodec(s)
        }

        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val targetNetwork = cm?.allNetworks?.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: cm?.activeNetwork

        if (targetNetwork != null) {
            log("Discovered target network for UDP binding: $targetNetwork")
        }

        // Bind UDP video socket (5901)
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 4 * 1024 * 1024
                soTimeout = if(preferAvc) 16 else 1000
            }
            try {
                targetNetwork?.bindSocket(sock)
                log("Bound UDP video socket to network $targetNetwork")
            } catch (e: Exception) {
                log("Failed to bind UDP video socket to network $targetNetwork: ${e.message}")
            }
            sock.bind(InetSocketAddress(videoPort))
            udpSocket = sock
            log("UDP video socket bound to port $videoPort (rcvbuf=${sock.receiveBufferSize})")
        } catch (e: Exception) {
            log("Failed to bind UDP port $videoPort, attempting ephemeral fallback: ${e.message}")
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    receiveBufferSize = 4 * 1024 * 1024
                    soTimeout = if(preferAvc) 16 else 1000
                }
                try { targetNetwork?.bindSocket(sock) } catch (ignored: Exception) {}
                sock.bind(InetSocketAddress(0))
                udpSocket = sock
                log("Fallback UDP video socket bound to port ${sock.localPort}")
            } catch (e2: Exception) {
                log("UDP socket bind failed: ${e2.message}")
                isRunning.set(false)
                return
            }
        }

        // Bind UDP control socket (5900)
        try {
            val cSock = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 1024 * 1024
                soTimeout = if(preferAvc) 16 else 1000
            }
            try {
                targetNetwork?.bindSocket(cSock)
                log("Bound UDP control socket to network $targetNetwork")
            } catch (e: Exception) {
                log("Failed to bind UDP control socket to network $targetNetwork: ${e.message}")
            }
            cSock.bind(InetSocketAddress(ctrlPort))
            udpCtrlSocket = cSock
            log("UDP control socket bound to port $ctrlPort")
        } catch (e: Exception) {
            log("Failed to bind UDP control port $ctrlPort: ${e.message}")
            try {
                val cSock = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = if(preferAvc) 16 else 1000
                }
                try { targetNetwork?.bindSocket(cSock) } catch (ignored: Exception) {}
                cSock.bind(InetSocketAddress(0))
                udpCtrlSocket = cSock
                log("Fallback UDP control socket bound to port ${cSock.localPort}")
            } catch (ignored: Exception) {}
        }

        receiverThread = Thread({
            receiveLoop()
        }, "MacHevcUdpReceiver").apply { start() }

        if (udpCtrlSocket != null) {
            ctrlReceiverThread = Thread({
                ctrlReceiveLoop()
            }, "MacHevcCtrlReceiver").apply { start() }
        }

        // Start continuous firewall punching and periodic FIR triggers
        punchThread = Thread({
            punchAndFirLoop()
        }, "MacHevcPunchAndFir").apply { start() }
    }

    private fun initCodec(targetSurface: Surface) {
        if(preferAvc) { avc?.setSurface(targetSurface); return }
        try {
            val w = if (width in 200..8192) width else 1920
            val h = if (height in 200..8192) height else 1080
            val format = MediaFormat.createVideoFormat("video/hevc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                try {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                } catch (e: Exception) {
                    // Ignored on platforms where low latency flag is unsupported
                }
            }
            val codec = MediaCodec.createDecoderByType("video/hevc")
            codec.configure(format, targetSurface, null, 0)
            codec.start()
            mediaCodec = codec
            log("Hardware MediaCodec (video/hevc) configured at ${w}x${h} to surface $targetSurface")
        } catch (e: Exception) {
            log("MediaCodec initialization failed: ${e.message}")
        }
    }

    fun updateSurface(newSurface: Surface?) {
        surface = newSurface
        if(preferAvc) { avc?.setSurface(newSurface); return }
        val codec = mediaCodec
        if (codec != null && newSurface != null && newSurface.isValid) {
            try {
                codec.setOutputSurface(newSurface)
                log("Updated MediaCodec output surface to $newSurface")
            } catch (e: Exception) {
                log("Failed to setOutputSurface, recreating codec: ${e.message}")
                try {
                    codec.stop()
                    codec.release()
                } catch (ignored: Exception) {}
                initCodec(newSurface)
            }
        } else if (codec == null && newSurface != null && newSurface.isValid && isRunning.get()) {
            initCodec(newSurface)
        }
    }

    fun updateDimensions(newW: Int, newH: Int) {
        if(preferAvc) return // Actual dimensions come from the validated avc1 sample entry.
        if (newW in 200..8192 && newH in 200..8192 && (newW != width || newH != height)) {
            width = newW
            height = newH
            val s = surface
            if (s != null && s.isValid && isRunning.get()) {
                log("Reconfiguring MediaCodec for ${newW}x${newH}")
                try {
                    mediaCodec?.stop()
                    mediaCodec?.release()
                } catch (ignored: Exception) {}
                mediaCodec = null
                initCodec(s)
            }
        }
    }

    private fun punchAndFirLoop() {
        var tick = 0
        val punchPacket = DatagramPacket(byteArrayOf(0), 1)
        val targetAddr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            log("Cannot resolve host $host for punching: ${e.message}")
            return
        }
        log("Started active UDP pinhole punch loop to $host:$videoPort and $host:$ctrlPort")

        while (isRunning.get()) {
            try {
                val vSock = udpSocket
                if (vSock != null && !vSock.isClosed) {
                    punchPacket.address = targetAddr
                    punchPacket.port = videoPort
                    if (datagramSender != null) datagramSender.invoke(videoPort, byteArrayOf(0)) else vSock.send(punchPacket)
                }

                val cSock = udpCtrlSocket
                if (cSock != null && !cSock.isClosed) {
                    punchPacket.address = targetAddr
                    punchPacket.port = ctrlPort
                    if (datagramSender != null) datagramSender.invoke(ctrlPort, byteArrayOf(0)) else cSock.send(punchPacket)
                    if(tick%5==0 && audioSender!=null) {
                        val heartbeat=audioSender.protect(byteArrayOf(0,0x68,0x34,0),101,tick.toLong()*4800)
                        if(datagramSender!=null) datagramSender.invoke(ctrlPort,heartbeat)
                        else cSock.send(DatagramPacket(heartbeat,heartbeat.size,targetAddr,ctrlPort))
                    }
                }

                // Send keyframe request if server SSRC is known and no frames decoded or every ~2s (20 ticks)
                val ssrc = serverVideoSsrc.get()
                val needFir = ssrc != 0L && ((framesDecoded.get() == 0L && tick % 5 == 0) || (tick % 20 == 0 && needsRecovery.getAndSet(false)))
                if (needFir && cSock != null && !cSock.isClosed && srtcpEncryptor != null) {
                    try {
                        val firPkt = MacHevcRtcp.buildCompoundFir(ourVideoSsrc, targetSsrc = ssrc)
                        val protected = srtcpEncryptor.protect(firPkt)
                        val rtcpDatagram = DatagramPacket(protected, protected.size, targetAddr, ctrlPort)
                        if (datagramSender != null) datagramSender.invoke(ctrlPort, protected) else cSock.send(rtcpDatagram)
                        if (tick % 20 == 0) {
                            log("Sent SRTCP Compound FIR keyframe trigger for SSRC 0x${java.lang.Long.toHexString(ssrc)} to $host:$ctrlPort (seq=$tick)")
                        }
                    } catch (e: Exception) {
                        log("Error sending SRTCP FIR: ${e.message}")
                    }
                }

                tick++
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    log("Pinhole punch loop error: ${e.message}")
                }
            }
        }
    }

    private fun ctrlReceiveLoop() {
        val sock = udpCtrlSocket ?: return
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        while (isRunning.get()) {
            try {
                packet.length = buffer.size
                sock.receive(packet)
                audio?.receive(packet.data, packet.offset, packet.length)
            } catch (e: java.net.SocketTimeoutException) {
                // Timeout expected
            } catch (e: Exception) {
                if (isRunning.get()) {
                    log("Control UDP receive error: ${e.message}")
                }
            }
        }
    }

    private fun receiveLoop() {
        val sock = udpSocket ?: return
        val buffer = ByteArray(65536)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning.get()) {
            try {
                packet.length = buffer.size
                sock.receive(packet)
                val count = packetsReceived.incrementAndGet()
                if (count == 1L) {
                    log("First UDP SRTP media packet received from ${packet.address}:${packet.port} (len=${packet.length})")
                }

                val length = packet.length
                if (length < 22) continue // Minimum SRTP header (12) + auth tag (10)

                // Decrypt SRTP packet
                val decrypted = try {
                    srtp.decryptPacket(packet.data, packet.offset, length)
                } catch (e: Exception) {
                    if (count <= 5L) {
                        log("Failed to decrypt SRTP packet #$count (len=$length): ${e.message}")
                    }
                    continue
                }

                if (count == 1L || serverVideoSsrc.get() == 0L) {
                    serverVideoSsrc.set(decrypted.ssrc)
                    log("First decrypted SRTP packet: ssrc=0x${java.lang.Long.toHexString(decrypted.ssrc)}, payload=${decrypted.payload.size}B")
                }
                val authenticated = authenticatedPackets.incrementAndGet()
                if(videoStreams.containsKey(decrypted.ssrc) || videoStreams.size<8)
                    videoStreams.getOrPut(decrypted.ssrc) { AtomicLong() }.incrementAndGet()
                if(preferAvc) {
                    avc?.accept(decrypted.payload,decrypted.sequenceNumber,decrypted.timestamp,decrypted.marker,decrypted.ssrc)
                    avc?.activeSsrc?.let(serverVideoSsrc::set)
                    continue
                }
                if (authenticated <= 3) {
                    log("Authenticated RTP pt=${decrypted.payloadType} seq=${decrypted.sequenceNumber} payload=${decrypted.payload.size} header=${decrypted.payload.take(4).joinToString("") { "%02x".format(it.toInt() and 255) }}")
                }
                // Apple's AVC configuration is a small out-of-band avcC packet, not an HEVC NAL.
                if (decrypted.payload.firstOrNull() == 0x92.toByte() && decrypted.payload.size <= 4096) {
                    parameterSetObserver?.invoke(0x92, decrypted.payload)
                }

                // Parse Apple RFC 7798 HEVC NAL units with 2-byte DONL stripping
                val nals = rtpParser.parseRtpPayload(decrypted.payload, decrypted.ssrc)
                if (nals.isEmpty()) continue
                if (authenticated <= 3) log("Validated HEVC RTP aggregation: DONL=${rtpParser.usesDonl(decrypted.ssrc)}, NALs=${nals.size}")
                nalUnits.addAndGet(nals.size.toLong())
                for (nal in nals) {
                    encodedVideoObserver?.invoke(decrypted.ssrc,nal)
                    if (nal.size > 5) {
                        val type = (nal[4].toInt() and 0x7e) ushr 1
                        if (type in 32..34 || (type in 16..21 && nal.size <= 4 * 1024 * 1024)) parameterSetObserver?.invoke(type, nal)
                    }
                }

                // Check if codec is initialized
                var codec = mediaCodec
                if (codec == null) {
                    val s = surface
                    if (s != null && s.isValid) {
                        initCodec(s)
                        codec = mediaCodec
                    }
                }
                if (codec == null) continue

                // Submit Annex-B NAL units to MediaCodec
                for (nal in nals) {
                    feedNalToCodec(codec, nal)
                }

            } catch (e: java.net.SocketTimeoutException) {
                // Read timeout, continue loop
                avc?.drain()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    log("UDP receive error: ${e.message}")
                }
            }
        }
    }

    private fun feedNalToCodec(codec: MediaCodec, nal: ByteArray) {
        val inIdx = try {
            codec.dequeueInputBuffer(5000)
        } catch (e: Exception) {
            -1
        }
        if (inIdx >= 0) {
            val inputBuffer = codec.getInputBuffer(inIdx)
            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(nal)
                val ptsUs = System.nanoTime() / 1000L
                codec.queueInputBuffer(inIdx, 0, nal.size, ptsUs, 0)
            }
        }

        // Drain available output buffers
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val outIdx = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                -1
            }
            if (outIdx >= 0) {
                codec.releaseOutputBuffer(outIdx, true) // renders directly to Surface
                val f = framesDecoded.incrementAndGet()
                if (f == 1L || f % 300L == 0L) {
                    log("Decoded and rendered $f HEVC frames to Surface")
                }
                onFrameDecoded()
            } else {
                break
            }
        }
    }

    override fun close() {
        if (!isRunning.getAndSet(false)) return
        try {
            udpSocket?.close()
        } catch (ignored: Exception) {}
        udpSocket = null

        try {
            udpCtrlSocket?.close()
        } catch (ignored: Exception) {}
        udpCtrlSocket = null

        try {
            punchThread?.interrupt()
            punchThread?.join(500)
        } catch (ignored: Exception) {}
        punchThread = null

        try {
            receiverThread?.interrupt()
            receiverThread?.join(500)
        } catch (ignored: Exception) {}
        receiverThread = null

        try {
            ctrlReceiverThread?.interrupt()
            ctrlReceiverThread?.join(500)
        } catch (ignored: Exception) {}
        ctrlReceiverThread = null
        runCatching { audio?.close() }.onFailure { log("AAC teardown failed: ${it.message}") }
        audio = null
        avc?.close()

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null
        log("MacHevcVideoPipeline closed (received=${packetsReceived.get()}, decoded=${framesDecoded.get()})")
    }
}
