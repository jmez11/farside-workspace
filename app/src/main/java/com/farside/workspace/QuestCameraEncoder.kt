package com.farside.workspace

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.Keep
import java.nio.ByteBuffer

/** One hardware encoder per RDPECAM stream; Image planes describe the actual input layout. */
@Keep
class QuestCameraEncoder(private val width: Int, private val height: Int, fps: Int, bitrate: Int) {
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private var headers = byteArrayOf()
    private var layoutLogged = false
    private var outputLogged = false
    private val packets = ArrayDeque<ByteArray>()
    private var diagnostic: java.io.OutputStream? = null

    init {
        try {
            if (BuildConfig.DEBUG) diagnostic = diagnosticDirectory?.let {
                java.io.File(it, "encoder.h264").outputStream()
            }
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Windows' RDPECAM Media Foundation source stalls on unconstrained Baseline.
                // Request Constrained Baseline so SPS constraint_set1_flag is emitted too.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            Log.i("QuestCameraEncoder", "Started ${codec.name}: ${width}x$height, bitrate=$bitrate")
        } catch (error: Throwable) {
            try { codec.release() } finally { diagnostic?.close() }
            throw error
        }
    }

    @Synchronized
    fun encode(source: ByteBuffer, pts: Long): ByteArray? {
        drainOutput(0)
        // RDPECAM requests one sample per response. Retain each access unit, and
        // skip new raw input while a batch is pending rather than grow latency.
        // Allow one pending frame so asynchronous codecs can keep a full pipeline.
        if (packets.size > 1) return packets.removeFirst()
        queueInput(source, pts)
        drainOutput(if (packets.isEmpty()) 10000 else 0)
        return packets.removeFirstOrNull()
    }

    @Synchronized
    internal fun queueInput(source: ByteBuffer, pts: Long): Boolean {
        val input = codec.dequeueInputBuffer(10000)
        if (input >= 0) {
            // Obtain capacity before Image; asking for a ByteBuffer afterward invalidates Image.
            val capacity = checkNotNull(codec.getInputBuffer(input)).capacity()
            checkNotNull(codec.getInputImage(input)).use { image ->
                check(image.width == width && image.height == height)
                val planes = image.planes
                if (!layoutLogged) {
                    Log.i("QuestCameraEncoder", "Input planes: " + planes.joinToString {
                        "row=${it.rowStride} pixel=${it.pixelStride} bytes=${it.buffer.remaining()}"
                    })
                    layoutLogged = true
                }
                check(QuestCameraPlaneCopy.copyNv12(source, width, height,
                    planes[0].buffer.slice(), planes[0].rowStride, planes[0].pixelStride,
                    planes[1].buffer.slice(), planes[1].rowStride, planes[1].pixelStride,
                    planes[2].buffer.slice(), planes[2].rowStride, planes[2].pixelStride))
            }
            codec.queueInputBuffer(input, 0, capacity, pts, 0)
            return true
        }
        return false
    }

    @Synchronized
    internal fun drainOutput(firstTimeoutUs: Long): Int {
        val info = MediaCodec.BufferInfo()
        var drained = 0
        while (true) {
            val output = codec.dequeueOutputBuffer(info, if (drained == 0) firstTimeoutUs else 0)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    headers = listOf("csd-0", "csd-1").mapNotNull { key ->
                        format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }
                    }.onEach { requireAnnexB(it) }.fold(byteArrayOf()) { all, bytes -> all + bytes }
                    Log.i("QuestCameraEncoder", "Output format: $format")
                }
                output >= 0 -> {
                    val bytes = try {
                        checkNotNull(codec.getOutputBuffer(output)).apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                        }.let { buffer -> ByteArray(info.size).also { buffer.get(it) } }
                    } finally {
                        codec.releaseOutputBuffer(output, false)
                    }
                    if (bytes.isNotEmpty()) requireAnnexB(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) headers = bytes
                    else if (bytes.isNotEmpty()) {
                        check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) {
                            "AVC encoder produced a partial access unit"
                        }
                        if (!outputLogged) {
                            Log.i("QuestCameraEncoder", "AVC Annex B first4=" +
                                bytes.take(4).joinToString(" ") { "%02x".format(it.toInt() and 255) })
                            outputLogged = true
                        }
                        val packet = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) headers + bytes else bytes
                        diagnostic?.write(packet)
                        packets.addLast(packet)
                        drained++
                    }
                }
                output == MediaCodec.INFO_TRY_AGAIN_LATER -> return drained
            }
        }
    }

    // RDPECAM's Windows Media Foundation decoder receives Annex B byte streams,
    // not AVCC length-prefixed samples. Fail clearly if a codec changes framing.
    private fun requireAnnexB(bytes: ByteArray) {
        check(bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))) {
            "AVC encoder output is not Annex B: first4=" +
                bytes.take(4).joinToString(" ") { "%02x".format(it.toInt() and 255) }
        }
    }

    @Synchronized
    fun close() {
        try { codec.stop() } finally {
            try { codec.release() } finally { diagnostic?.close() }
        }
    }

    companion object {
        @Volatile internal var diagnosticDirectory: String? = null
    }

}

@Keep
object QuestCameraPlaneCopy {
    @JvmStatic external fun copyNv12(source: ByteBuffer, width: Int, height: Int,
        y: ByteBuffer, yRow: Int, yPixel: Int, u: ByteBuffer, uRow: Int, uPixel: Int,
        v: ByteBuffer, vRow: Int, vPixel: Int): Boolean
}

@Keep
internal object QuestCameraVerification {
    @JvmStatic external fun verifySessionIsolation(): Boolean
}
