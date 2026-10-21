package com.farside.workspace

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Apple PT101: one raw AAC-ELD-SBR access unit per authenticated RTP packet. */
class MacScreenAudio(key: ByteArray, private val log: (String) -> Unit,
    private val observer: ((ByteArray) -> Unit)? = null,
    private val codecConfig: ByteArray = CONFIG) : Closeable {
    companion object {
        // MPEG-4 AudioSpecificConfig: AOT39, stereo, 48kHz, 480-sample frame, SBR.
        val CONFIG: ByteArray get() = byteArrayOf(0xf8.toByte(),0xe6.toByte(),0x51,0x32,0xe0.toByte(),0)
    }
    private val decryptor = MacHevcSrtp(key.copyOfRange(0,32),key.copyOfRange(32,46))
    private data class AccessUnit(val payload: ByteArray, val timestamp: Long)
    private val queue = ArrayBlockingQueue<AccessUnit>(8)
    private val running = AtomicBoolean(true)
    val authenticated = AtomicLong()
    val decodedFrames = AtomicLong()
    val writtenFrames = AtomicLong()
    val dropped = AtomicLong()
    val peak = AtomicLong()
    val payloadBytes = AtomicLong()
    val largestAccessUnit = AtomicLong()
    @Volatile var error: String? = null; private set
    @Volatile private var track: AudioTrack? = null
    @Volatile var muted = false
        set(value) { field=value; runCatching { track?.setVolume(if(value) 0f else 1f) } }
    val playedFrames: Long get() = runCatching { (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL }.getOrDefault(0)
    val playbackSessionId: Int get() = runCatching { track?.audioSessionId ?: 0 }.getOrDefault(0)
    private var previousSequence = -1
    private var currentSsrc = -1L
    @Volatile internal var accessUnitObserver: ((ByteArray)->Unit)? = observer
    private val worker = Thread({ decodeLoop() },"MacScreenAudio").apply { start() }

    /** Called only on the control socket receiver; SRTCP is filtered before decryption. */
    fun receive(bytes: ByteArray, offset: Int, length: Int) {
        if (!running.get() || length < 22 || bytes[offset+1].toInt() and 0x7f != 101) return
        val packet = try { decryptor.decryptPacket(bytes,offset,length) } catch (_: MacHevcSrtpException) { return }
        if (packet.payloadType != 101) return
        if (currentSsrc != packet.ssrc) { currentSsrc=packet.ssrc; previousSequence=-1 }
        if (previousSequence >= 0 && (packet.sequenceNumber-previousSequence and 0xffff) !in 1..32767) return
        previousSequence=packet.sequenceNumber
        authenticated.incrementAndGet()
        payloadBytes.addAndGet(packet.payload.size.toLong())
        largestAccessUnit.updateAndGet { maxOf(it,packet.payload.size.toLong()) }
        accessUnitObserver?.invoke(packet.payload)
        if (!queue.offer(AccessUnit(packet.payload,packet.timestamp))) {
            queue.poll(); dropped.incrementAndGet(); queue.offer(AccessUnit(packet.payload,packet.timestamp))
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            codec=MediaCodec.createDecoderByType("audio/mp4a-latm")
            val format=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2).apply {
                setByteBuffer("csd-0",ByteBuffer.wrap(codecConfig))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192)
            }
            codec.configure(format,null,null,0); codec.start()
            log("AAC decoder=${codec.name}; raw ELD-SBR stereo configuration")
            val info=MediaCodec.BufferInfo()
            var baseTimestamp: Long? = null
            var pending: AccessUnit? = null
            while(running.get()) {
                if(pending==null) pending=queue.poll(5,TimeUnit.MILLISECONDS)
                pending?.let { unit ->
                    val index=codec.dequeueInputBuffer(0)
                    if(index>=0) {
                        val input=codec.getInputBuffer(index)!!
                        check(unit.payload.size<=input.capacity()) { "AAC access unit exceeds decoder capacity" }
                        input.clear(); input.put(unit.payload)
                        if(baseTimestamp==null) baseTimestamp=unit.timestamp
                        val pts=((unit.timestamp-baseTimestamp!!) and 0xffffffffL)*1_000_000L/48000L
                        codec.queueInputBuffer(index,0,unit.payload.size,pts,0); pending=null
                    }
                }
                var index=codec.dequeueOutputBuffer(info,0)
                while(index>=0 || index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out=codec.outputFormat
                        check(out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==2)
                        check(!out.containsKey(MediaFormat.KEY_PCM_ENCODING) || out.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT)
                        val rate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        check(rate in listOf(24000,48000)) { "Unexpected AAC output rate $rate" }
                        track?.release()
                        val audioFormat=AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                        track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT),rate*4/10))
                            .setTransferMode(AudioTrack.MODE_STREAM).build().also {
                                check(it.state==AudioTrack.STATE_INITIALIZED)
                                it.setVolume(if(muted) 0f else 1f); it.play()
                            }
                        log("AAC output PCM16 rate=$rate channels=2")
                    } else {
                        try {
                            if(info.size>0) {
                                val buffer=codec.getOutputBuffer(index)!!
                                buffer.position(info.offset); buffer.limit(info.offset+info.size)
                                val pcm=ByteArray(info.size); buffer.get(pcm)
                                val samples=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                var maximum=0L
                                while(samples.hasRemaining()) maximum=maxOf(maximum,kotlin.math.abs(samples.get().toInt()).toLong())
                                peak.updateAndGet { maxOf(it,maximum) }
                                decodedFrames.addAndGet(pcm.size/4L)
                                val output=checkNotNull(track) { "AAC output before format" }
                                var offset=0
                                val writeDeadline=android.os.SystemClock.uptimeMillis()+150
                                while(offset<pcm.size && running.get()) {
                                    if(android.os.SystemClock.uptimeMillis()>=writeDeadline) { dropped.incrementAndGet(); break }
                                    val wrote=output.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_NON_BLOCKING)
                                    check(wrote>=0) { "AudioTrack write error $wrote" }
                                    if(wrote==0) Thread.sleep(2) else { offset+=wrote; writtenFrames.addAndGet(wrote/4L) }
                                }
                            }
                        } finally { codec.releaseOutputBuffer(index,false) }
                    }
                    index=codec.dequeueOutputBuffer(info,0)
                }
            }
        } catch(e: Exception) {
            if(running.get()) { error="${e.javaClass.simpleName}: ${e.message}"; log("AAC failed: $error") }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { track?.stop() }; runCatching { track?.release() }; track=null
        }
    }
    override fun close() {
        running.set(false); worker.interrupt(); worker.join(3000); queue.clear()
        check(!worker.isAlive) { "AAC worker did not stop" }
    }
}

