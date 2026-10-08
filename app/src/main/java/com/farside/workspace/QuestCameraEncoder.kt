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
        }
        val info = MediaCodec.BufferInfo()
        repeat(5) {
            val output = codec.dequeueOutputBuffer(info, 10000)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    headers = listOf("csd-0", "csd-1").mapNotNull { key ->
                        format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }
                    }.fold(byteArrayOf()) { all, bytes -> all + bytes }
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) headers = bytes
                    else if (bytes.isNotEmpty()) {
                        val packet = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) headers + bytes else bytes
                        diagnostic?.write(packet)
                        return packet
                    }
                }
                else -> return null
            }
        }
        return null
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
