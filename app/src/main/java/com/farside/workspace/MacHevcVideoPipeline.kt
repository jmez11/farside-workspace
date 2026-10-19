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
    val serverVideoSsrc = AtomicLong(0L)

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
                soTimeout = 1000
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
                    soTimeout = 1000
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
                soTimeout = 1000
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
                    soTimeout = 1000
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
                    vSock.send(punchPacket)
                }

                val cSock = udpCtrlSocket
                if (cSock != null && !cSock.isClosed) {
                    punchPacket.address = targetAddr
                    punchPacket.port = ctrlPort
                    cSock.send(punchPacket)
                }

                // Send keyframe request if server SSRC is known and no frames decoded or every ~2s (20 ticks)
                val ssrc = serverVideoSsrc.get()
                val needFir = ssrc != 0L && ((framesDecoded.get() == 0L && tick % 5 == 0) || (tick % 20 == 0))
                if (needFir && cSock != null && !cSock.isClosed && srtcpEncryptor != null) {
                    try {
                        val firPkt = MacHevcRtcp.buildCompoundFir(ourVideoSsrc, targetSsrc = ssrc)
                        val protected = srtcpEncryptor.protect(firPkt)
                        val rtcpDatagram = DatagramPacket(protected, protected.size, targetAddr, ctrlPort)
                        cSock.send(rtcpDatagram)
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
                sock.receive(packet)
                log("UDP control socket received packet from ${packet.address}:${packet.port} (len=${packet.length})")
                // Drain control/RTCP datagrams so the buffer does not overflow
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

                // Parse Apple RFC 7798 HEVC NAL units with 2-byte DONL stripping
                val nals = rtpParser.parseRtpPayload(decrypted.payload, decrypted.ssrc)
                if (nals.isEmpty()) continue

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

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null
        log("MacHevcVideoPipeline closed (received=${packetsReceived.get()}, decoded=${framesDecoded.get()})")
    }
}
