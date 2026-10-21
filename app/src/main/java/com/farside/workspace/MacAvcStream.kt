package com.farside.workspace

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/** Bounded RFC6184 access-unit assembly. Sequence gaps discard the whole timestamp group. */
internal class MacAvcAssembler {
    private var timestamp = -1L
    private var sequence = -1
    private var broken = false
    private var fu: ByteArrayOutputStream? = null
    private val au = ByteArrayOutputStream()
    private val start = byteArrayOf(0,0,0,1)
    private var loss=false
    var sequenceGaps=0L; private set
    var unfinishedGroups=0L; private set
    var invalidPayloads=0L; private set
    var completedPictures=0L; private set
    val diagnostics:String get()="pictures=$completedPictures sequenceGaps=$sequenceGaps unfinishedGroups=$unfinishedGroups invalidPayloads=$invalidPayloads"
    fun takeLoss():Boolean=loss.also { loss=false }
    fun reset() { timestamp=-1L;sequence=-1;broken=false;fu=null;loss=false;au.reset() }
    fun accept(payload: ByteArray, seq: Int, ts: Long, marker: Boolean): ByteArray? {
        if(ts!=timestamp) { if(fu!=null || au.size()>0) { loss=true;unfinishedGroups++ };timestamp=ts; sequence=-1; broken=false; fu=null; au.reset() }
        if(sequence>=0 && ((seq-sequence) and 65535)!=1) { broken=true;loss=true;sequenceGaps++;fu=null }
        sequence=seq
        if(payload.isEmpty() || broken) return null
        val type=payload[0].toInt() and 31
        when {
            type in 1..23 -> { au.write(start); au.write(payload) }
            type==28 && payload.size>2 -> {
                val header=payload[1].toInt() and 255
                if(header and 128!=0) {
                    fu=ByteArrayOutputStream().apply { write((payload[0].toInt() and 224) or (header and 31)) }
                }
                val part=fu
                if(part==null) broken=true else {
                    part.write(payload,2,payload.size-2)
                    if(part.size()>4*1024*1024) { broken=true; fu=null }
                    else if(header and 64!=0) { au.write(start); au.write(part.toByteArray()); fu=null }
                }
            }
            else -> broken=true
        }
        if(au.size()>4*1024*1024) { broken=true; au.reset(); fu=null }
        if(broken) { loss=true;invalidPayloads++ }
        if(!marker) return null
        if(fu!=null) loss=true
        return if(!broken && fu==null && au.size()>0) au.toByteArray().also { au.reset();completedPictures++ } else null
    }
}

/** A one-tile offer may overlap old/new encoder generations during display/login changes. */
internal class MacAvcSourceSelector {
    var active:Long?=null; private set
    private val retired=linkedSetOf<Long>()
    fun accept(ssrc:Long,validConfig:Boolean):Boolean {
        if(ssrc==active) return true
        if(!validConfig || ssrc in retired) return false
        active?.let { retired.add(it) }
        if(retired.size>64) retired.remove(retired.first())
        active=ssrc
        return true
    }
}

/** Apple's 0x92 VisualSampleEntry config followed by RFC6184 H.264 access units. */
internal class MacAvcStream(private val log: (String)->Unit, private val frame: ()->Unit) : Closeable {
    private val assembler=MacAvcAssembler()
    private val packetOrder=MacAvcPacketOrder()
    private val sources=MacAvcSourceSelector()
    val activeSsrc:Long? get()=sources.active
    @Volatile var accessUnitObserver:((ByteArray)->Unit)?=null
    @Volatile var packetObserver:((ByteArray,Int,Long,Boolean,Long)->Unit)?=null
    @Synchronized fun assemblyDiagnostics():String="${assembler.diagnostics} reordered=${packetOrder.repaired} duplicates=${packetOrder.duplicates} expiredGaps=${packetOrder.expiredGaps}"
    val inputDrops=java.util.concurrent.atomic.AtomicLong()
    var onRecovery:()->Unit={}
    @Synchronized fun configurationSnapshot():ByteArray?=config?.copyOf()
    private var codec: MediaCodec?=null
    private var config: ByteArray?=null
    @Volatile var width=0; private set
    @Volatile var height=0; private set
    private var surface: Surface?=null
    var description="H.264 awaiting configuration"; private set
    @Synchronized fun setSurface(next: Surface?) {
        surface=next
        if(next!=null && next.isValid) {
            if(codec==null) configure() else runCatching { codec?.setOutputSurface(next) }.onFailure { closeCodec(); configure() }
        }
    }
    private fun configure() {
        val target=surface?.takeIf { it.isValid } ?: return
        val data=config ?: return
        val format=MediaFormat.createVideoFormat("video/avc",width,height).apply {
            setByteBuffer("csd-0",ByteBuffer.wrap(data))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024)
        }
        val decoder=MediaCodec.createDecoderByType("video/avc")
        try {
            check(decoder.codecInfo.isHardwareAccelerated) { "Selected AVC decoder is not hardware accelerated" }
            if(decoder.codecInfo.getCapabilitiesForType("video/avc").isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) format.setInteger(MediaFormat.KEY_LOW_LATENCY,1)
            decoder.configure(format,target,null,0); decoder.start(); codec=decoder
            description="Hardware H.264 (${decoder.name})"
            log("$description configured ${width}x$height")
        } catch(e: Exception) { decoder.release(); throw e }
    }
    @Synchronized fun accept(payload: ByteArray, seq: Int, timestamp: Long, marker: Boolean, ssrc:Long) {
        packetObserver?.invoke(payload,seq,timestamp,marker,ssrc)
        if(payload.firstOrNull()==0x92.toByte()) {
            val parsed=parseConfig(payload) ?: return
            val previous=sources.active
            if(!sources.accept(ssrc,true)) return
            if(previous!=ssrc || config?.contentEquals(parsed.third)!=true || width!=parsed.first || height!=parsed.second) {
                closeCodec();assembler.reset();packetOrder.reset((seq+1) and 65535);width=parsed.first; height=parsed.second; config=parsed.third; configure()
                accessUnitObserver?.invoke(parsed.third)
                log("AVC source selected ${ssrc.toString(16)}; retired source packets excluded")
            }
            // Repeated configuration consumes an RTP sequence number, too.
            else submitOrdered(MacAvcPacketOrder.Packet(byteArrayOf(),seq,timestamp,marker))
            return
        }
        if(!sources.accept(ssrc,false)) return
        submitOrdered(MacAvcPacketOrder.Packet(payload,seq,timestamp,marker))
    }
    private fun submitOrdered(packet:MacAvcPacketOrder.Packet) {
        val ready=packetOrder.accept(packet,System.nanoTime()/1_000_000)
        if(packetOrder.takeLoss()) onRecovery()
        ready.forEach(::submitPacket)
    }
    private fun submitPacket(packet:MacAvcPacketOrder.Packet) {
        if(packet.payload.isEmpty()) return
        val timestamp=packet.timestamp
        val unit=assembler.accept(packet.payload,packet.sequence,timestamp,packet.marker)
        if(assembler.takeLoss()) onRecovery()
        val decoder=codec ?: return
        drainCodec()
        if(unit!=null) {
            val index=decoder.dequeueInputBuffer(5000)
            if(index>=0) {
                val input=decoder.getInputBuffer(index)!!
                check(unit.size<=input.capacity()) { "AVC access unit too large" }
                input.clear();input.put(unit)
                decoder.queueInputBuffer(index,0,unit.size,timestamp*1_000_000L/90000,0)
                accessUnitObserver?.invoke(unit)
            } else { inputDrops.incrementAndGet();onRecovery() }
        }
        drainCodec()
    }
    @Synchronized fun drain() {
        packetOrder.drain(System.nanoTime()/1_000_000).forEach(::submitPacket)
        if(packetOrder.takeLoss()) onRecovery()
        drainCodec()
    }
    private fun drainCodec() {
        val decoder=codec ?: return
        val info=MediaCodec.BufferInfo()
        var index=decoder.dequeueOutputBuffer(info,0)
        while(index>=0 || index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if(index>=0) { decoder.releaseOutputBuffer(index,true); frame() }
            index=decoder.dequeueOutputBuffer(info,0)
        }
    }
    private fun closeCodec() { runCatching { codec?.stop() }; runCatching { codec?.release() }; codec=null }
    @Synchronized override fun close() { closeCodec() }
    companion object {
        /** Validate box lengths and complete SPS/PPS arrays before accepting replacement config. */
        fun parseConfig(data: ByteArray): Triple<Int,Int,ByteArray>? = runCatching {
            fun u16(p: Int)=((data[p].toInt() and 255) shl 8) or (data[p+1].toInt() and 255)
            fun u32(p: Int)=ByteBuffer.wrap(data,p,4).int
            fun box(tag: String): Int = (4..data.size-4).firstOrNull { i ->
                tag.indices.all { data[i+it]==tag[it].code.toByte() } && u32(i-4)>=8 && u32(i-4)<=data.size-(i-4)
            } ?: error("Missing $tag")
            val entry=box("avc1"); val avcc=box("avcC")
            val end=avcc-4+u32(avcc-4)
            check(avcc>entry && end<=entry-4+u32(entry-4))
            val w=u16(entry+28); val h=u16(entry+30)
            check(w in 200..8192 && h in 200..8192)
            var p=avcc+4
            check(p+6<=end && data[p]==1.toByte())
            val spsCount=data[p+5].toInt() and 31; p+=6
            val result=ByteArrayOutputStream()
            fun nals(count:Int,type:Int) {
                check(count>0)
                repeat(count) {
                    check(p+2<=end);val size=u16(p);p+=2
                    check(size>0 && size<=end-p && data[p].toInt() and 31==type)
                    result.write(byteArrayOf(0,0,0,1)); result.write(data,p,size);p+=size
                }
            }
            nals(spsCount,7);check(p<end);val count=data[p++].toInt() and 255;nals(count,8)
            Triple(w,h,result.toByteArray())
        }.getOrNull()
    }
}
