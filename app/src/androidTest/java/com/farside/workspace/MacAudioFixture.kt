package com.farside.workspace

import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Offline ELD-SBR control; Android encoder uses a different frame length from Apple. */
internal class MacAudioFixture(private val test:Instrumentation) {
    fun run(backgroundControl:Boolean=false):String {
        if(backgroundControl) return runForeground()
        // Quest may suspend AudioTrack with no visible app window; exercise the real foreground case.
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        return try {
            test.targetContext.startActivity(android.content.Intent(test.targetContext,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) { "Horizon did not present the audio fixture Activity" }
            try { runForeground() } finally { test.runOnMainSync { activity.finish() } }
        } finally { test.removeMonitor(monitor) }
    }
    private fun runForeground():String {
        val report=File(test.targetContext.cacheDir,"mac-audio-fixture.txt")
        report.writeText("")
        fun record(s:String) { report.appendText("$s\n") }
        val encoder=MediaCodec.createEncoderByType("audio/mp4a-latm")
        val units=mutableListOf<ByteArray>()
        var config:ByteArray?=null
        try {
            val format=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setInteger(MediaFormat.KEY_AAC_SBR_MODE,1)
                setInteger(MediaFormat.KEY_BIT_RATE,24000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192)
            }
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            record("Encoder=${encoder.name}")
            val info=MediaCodec.BufferInfo()
            var chunk=0
            var done=false
            val deadline=SystemClock.uptimeMillis()+6000
            while(!done && SystemClock.uptimeMillis()<deadline) {
                if(chunk<=60) {
                    val slot=encoder.dequeueInputBuffer(1000)
                    if(slot>=0) {
                        val pcm=ByteBuffer.allocate(480*4).order(ByteOrder.LITTLE_ENDIAN)
                        repeat(480) { i ->
                            val value=(sin(2*PI*440*(chunk*480+i)/48000)*8000).toInt().toShort()
                            pcm.putShort(value);pcm.putShort(value)
                        }
                        val bytes=if(chunk==60) byteArrayOf() else pcm.array()
                        encoder.getInputBuffer(slot)!!.apply { clear();put(bytes) }
                        encoder.queueInputBuffer(slot,0,bytes.size,chunk*10_000L,if(chunk==60) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        chunk++
                    }
                }
                val slot=encoder.dequeueOutputBuffer(info,1000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    config=encoder.outputFormat.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                } else if(slot>=0) {
                    val bytes=ByteArray(info.size)
                    encoder.getOutputBuffer(slot)!!.apply { position(info.offset);limit(info.offset+info.size);get(bytes) }
                    if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0) config=bytes
                    else if(bytes.isNotEmpty()) units+=bytes
                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    encoder.releaseOutputBuffer(slot,false)
                }
            }
            record("Encoder ASC=${config?.joinToString("") { "%02x".format(it.toInt() and 255) }} units=${units.size}")
            check(config?.take(2)==MacScreenAudio.CONFIG.take(2)) { "Synthetic control is not stereo AAC-ELD at 48kHz" }
            record("Synthetic ASC differs from Apple; this control verifies AAC path, not exact Apple interoperability")
            check(units.size>10)
        } finally { runCatching { encoder.stop() };encoder.release() }
        val blob=ByteArray(46) { (it+13).toByte() }
        val sender=MacHevcSrtpSender(blob,0x10203040)
        val audio=MacScreenAudio(blob,::record,codecConfig=checkNotNull(config))
        try {
            repeat(3) { cycle ->
                units.forEachIndexed { i,unit ->
                    val packet=sender.protect(unit,101,(cycle*units.size+i)*480L)
                    audio.receive(packet,0,packet.size)
                    Thread.sleep(10)
                }
            }
            Thread.sleep(300)
            record("decoded=${audio.decodedFrames.get()} played=${audio.playedFrames} peak=${audio.peak.get()} error=${audio.error}")
            check(audio.error==null && audio.peak.get()>100 && audio.playedFrames>0)
            audio.muted=true;check(audio.muted);audio.muted=false;check(!audio.muted)
        } finally { audio.close() }
        record("PASS non-silent synthetic ELD decoding/playback, mute state and teardown; Apple live remains separate")
        return report.readText()
    }
}
