package com.farside.workspace

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
 */
class MacHevcVideoPipeline(
    private val host: String,
    private val videoPort: Int = 5901,
    videoKeyBlob: ByteArray, // 46 bytes: 32 bytes masterKey + 14 bytes masterSalt
    @Volatile var surface: Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val onFrameDecoded: () -> Unit = {},
    private val logCallback: (String) -> Unit = {}
) : Closeable {

    init {
        require(videoKeyBlob.size == 46) { "videoKeyBlob must be 46 bytes, got ${videoKeyBlob.size}" }
    }

    private val masterKey = videoKeyBlob.copyOfRange(0, 32)
    private val masterSalt = videoKeyBlob.copyOfRange(32, 46)
    private val srtp = MacHevcSrtp(masterKey, masterSalt)
    private val rtpParser = MacHevcRtpParser()

    private val isRunning = AtomicBoolean(false)
    val framesDecoded = AtomicLong(0)
    val packetsReceived = AtomicLong(0)

    private var udpSocket: DatagramSocket? = null
    private var mediaCodec: MediaCodec? = null
    private var receiverThread: Thread? = null

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

        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 4 * 1024 * 1024
                soTimeout = 1000
                bind(InetSocketAddress(videoPort))
            }
            udpSocket = sock
            log("UDP video socket bound to port $videoPort (rcvbuf=${sock.receiveBufferSize})")
        } catch (e: Exception) {
            log("Failed to bind UDP port $videoPort, attempting ephemeral fallback: ${e.message}")
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    receiveBufferSize = 4 * 1024 * 1024
                    soTimeout = 1000
                    bind(InetSocketAddress(0))
                }
                udpSocket = sock
                log("Fallback UDP video socket bound to port ${sock.localPort}")
            } catch (e2: Exception) {
                log("UDP socket bind failed: ${e2.message}")
                isRunning.set(false)
                return
            }
        }

        receiverThread = Thread({
            receiveLoop()
        }, "MacHevcUdpReceiver").apply { start() }

        // Send firewall punch to server video port
        punchFirewall()
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

    fun punchFirewall() {
        Thread({
            val sock = udpSocket ?: return@Thread
            try {
                val addr = InetAddress.getByName(host)
                val punch = DatagramPacket(byteArrayOf(0), 1, addr, videoPort)
                for (i in 0 until 3) {
                    sock.send(punch)
                    Thread.sleep(100)
                }
                log("Sent firewall punch to $host:$videoPort")
            } catch (e: Exception) {
                log("Firewall punch error: ${e.message}")
            }
        }, "MacHevcPunch").start()
    }

    private fun receiveLoop() {
        val sock = udpSocket ?: return
        val buffer = ByteArray(65536)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning.get()) {
            try {
                sock.receive(packet)
                packetsReceived.incrementAndGet()

                val length = packet.length
                if (length < 22) continue // Minimum SRTP header (12) + auth tag (10)

                // Decrypt SRTP packet
                val decrypted = try {
                    srtp.decryptPacket(packet.data, packet.offset, length)
                } catch (e: Exception) {
                    // Invalid packet or auth failure
                    continue
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
                framesDecoded.incrementAndGet()
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
            receiverThread?.interrupt()
            receiverThread?.join(500)
        } catch (ignored: Exception) {}
        receiverThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null
        log("MacHevcVideoPipeline closed (received=${packetsReceived.get()}, decoded=${framesDecoded.get()})")
    }
}
