package com.farside.workspace

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/** Replays one bounded owned frame without another connection/authentication. */
internal class MacHevcCodecReplay(private val test: Instrumentation) {
    private val report = File(test.targetContext.cacheDir, "mac-hevc-codec-replay.txt")
    private fun record(s: String) { report.appendText("$s\n") }
    private fun split(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < bytes.size) {
            val length = when {
                bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 0.toByte() && bytes[i+3] == 1.toByte() -> 4
                bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 1.toByte() -> 3
                else -> 0
            }
            if (length > 0) { starts += i to length; i += length } else i++
        }
        return starts.mapIndexed { index, (start, prefix) ->
            byteArrayOf(0,0,0,1) + bytes.copyOfRange(start + prefix, starts.getOrNull(index+1)?.first ?: bytes.size)
        }
    }
    fun run(): String {
        report.writeText("")
        val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first {
            !it.isEncoder && it.isHardwareAccelerated && !it.name.contains("secure") && !it.name.contains("low_latency") &&
                it.supportedTypes.any { type -> type.equals("video/hevc", true) }
        }.name
        val control = test.context.assets.open("mac-hevc-main420-control.hevc").use { split(it.readBytes()) }
        val controlParams = control.filter { (it[4].toInt() and 0x7e) ushr 1 in 32..34 }.fold(byteArrayOf()) { a,b -> a+b }
        val controlFrame = control.filter { (it[4].toInt() and 0x7e) ushr 1 !in 32..34 }.fold(byteArrayOf()) { a,b -> a+b }
        val ownedParams = (32..34).fold(byteArrayOf()) { a,t -> a + File(test.targetContext.cacheDir,"mac-hevc-parameter-$t.bin").readBytes() }
        val ownedFrame = File(test.targetContext.cacheDir,"mac-hevc-keyframe.bin").readBytes()
        val controlImages = replay("Main-420-control", codecName, controlParams, controlFrame)
        check(controlImages > 0) { "Same-size supported-format control failed; cannot interpret the Apple replay" }
        val actualImages = replay("Apple-RExt-444", codecName, ownedParams, ownedFrame)
        val independent444 = test.context.assets.open("mac-hevc-rext444-control.hevc").use { split(it.readBytes()) }
        val independentParams = independent444.filter { (it[4].toInt() and 0x7e) ushr 1 in 32..34 }.fold(byteArrayOf()) { a,b -> a+b }
        val independentFrame = independent444.filter { (it[4].toInt() and 0x7e) ushr 1 !in 32..34 }.fold(byteArrayOf()) { a,b -> a+b }
        val independentImages = replay("Independent-RExt-444-control",codecName,independentParams,independentFrame)
        record("RESULT controlImages=$controlImages AppleImages=$actualImages independent444Images=$independentImages")
        check(actualImages > 0) { "Apple HEVC replay produced no hardware-decoded image; see cache report" }
        return report.readText()
    }
    private fun replay(label: String, name: String, csd: ByteArray, frame: ByteArray): Int {
        var images = 0
        val reader = ImageReader.newInstance(3840,2160,ImageFormat.YUV_420_888,3)
        val codec = MediaCodec.createByCodecName(name)
        try {
            val format = MediaFormat.createVideoFormat("video/hevc",3840,2160).apply {
                setByteBuffer("csd-0",ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,maxOf(frame.size, 4*1024*1024))
            }
            record("$label codec=$name hardware=${codec.codecInfo.isHardwareAccelerated} csd=${csd.size} frame=${frame.size}")
            codec.configure(format,reader.surface,null,0)
            codec.start()
            val deadline = SystemClock.uptimeMillis()+6000
            var queued = 0
            val info = MediaCodec.BufferInfo()
            while (SystemClock.uptimeMillis()<deadline && images==0) {
                if (queued<2) {
                    val slot = codec.dequeueInputBuffer(10_000)
                    if (slot>=0) {
                        if (queued==0) {
                            codec.getInputBuffer(slot)!!.apply { clear(); put(frame) }
                            codec.queueInputBuffer(slot,0,frame.size,0,0)
                        } else codec.queueInputBuffer(slot,0,0,1,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued++
                    }
                }
                val slot = codec.dequeueOutputBuffer(info,10_000)
                if (slot>=0) codec.releaseOutputBuffer(slot, !(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0))
                reader.acquireLatestImage()?.use { image ->
                    // This Horizon build aborts in ImageReader.nativeCreatePlanes for vendor output.
                    // Count real non-EOS surface Images without invoking that broken CPU-mapping API.
                    record("$label IMAGE ${image.width}x${image.height} timestamp=${image.timestamp}")
                    check(image.width == 3840 && image.height == 2160)
                    images++
                }
            }
            record("$label images=$images")
        } catch (e: Exception) { record("$label ERROR ${e.javaClass.simpleName}: ${e.message}") }
        finally { runCatching { codec.stop() }; codec.release(); reader.close() }
        return images
    }
}
