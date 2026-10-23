package com.farside.workspace

import android.app.Instrumentation
import android.graphics.BitmapFactory
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer

/** Two synthetic solid-color encoders overlap; retired frames must never replace the new display. */
internal class MacAvcSourceFixture(private val test:Instrumentation) {
    fun run():String {
        fun source(name:String):List<ByteArray> = DataInputStream(test.context.assets.open("mac-avc-source-$name.bin")).use { input ->
            List(input.readInt()) { ByteArray(input.readInt()).also(input::readFully) }
        }
        fun config(nals:List<ByteArray>):ByteArray {
            val sps=nals.single { it[0].toInt() and 31==7 };val pps=nals.single { it[0].toInt() and 31==8 }
            val avcc=ByteBuffer.allocate(11+sps.size+pps.size).apply {
                put(1);put(sps[1]);put(sps[2]);put(sps[3]);put(0xff.toByte());put(0xe1.toByte())
                putShort(sps.size.toShort());put(sps);put(1);putShort(pps.size.toShort());put(pps)
            }.array()
            val entry=ByteBuffer.allocate(86+8+avcc.size).apply {
                putInt(capacity());put("avc1".toByteArray());position(32);putShort(320);putShort(240)
                position(86);putInt(8+avcc.size);put("avcC".toByteArray());put(avcc)
            }.array()
            return byteArrayOf(0x92.toByte())+entry
        }
        val red=source("red");val blue=source("blue")
        var frames=0
        MacMediaSurface(test).use { displayed ->
            val decoder=MacAvcStream({ android.util.Log.i("MacAvcFixture",it) },{ frames++ })
            try {
                decoder.setSurface(displayed.surface)
                val sequences=mutableMapOf<Long,Int>();var ts=0L
                fun feed(ssrc:Long,nals:List<ByteArray>) {
                    var seq=sequences[ssrc] ?: 65534
                    decoder.accept(config(nals),seq++,ts,false,ssrc)
                    val picture=nals.filter { it[0].toInt() and 31 in 1..5 }
                    picture.forEachIndexed { i,nal ->
                        val chunks=nal.copyOfRange(1,nal.size).asList().chunked((nal.size+1)/3).map { it.toByteArray() }
                        val packets=chunks.mapIndexed { j,part ->
                            val flags=(if(j==0) 128 else 0) or (if(j==chunks.lastIndex) 64 else 0)
                            val payload=byteArrayOf(((nal[0].toInt() and 224) or 28).toByte(),((nal[0].toInt() and 31) or flags).toByte())+part
                            MacAvcPacketOrder.Packet(payload,seq++ and 65535,ts,i==picture.lastIndex && j==chunks.lastIndex)
                        }
                        val order=if(packets.size>=3) listOf(packets[0],packets[2],packets[1])+packets.drop(3) else packets
                        order.forEach { p -> decoder.accept(p.payload,p.sequence,p.timestamp,p.marker,ssrc) }
                        // A retransmission after completion must not paint an older picture.
                        packets.first().let { p -> decoder.accept(p.payload,p.sequence,p.timestamp,p.marker,ssrc) }
                    }
                    sequences[ssrc]=seq and 65535
                    ts+=90000
                    repeat(20) { Thread.sleep(15);decoder.drain() }
                }
                fun color(name:String,blueExpected:Boolean) {
                    displayed.capture(name)
                    val bitmap=BitmapFactory.decodeFile(File(test.targetContext.cacheDir,name).path)
                    try {
                        val pixel=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                        val r=(pixel ushr 16) and 255;val b=pixel and 255
                        check(if(blueExpected) b>180 && r<70 else r>180 && b<70) { "Unexpected source color red=$r blue=$b" }
                    } finally { bitmap.recycle() }
                }
                feed(10,red);color("mac-avc-source-red.png",false)
                feed(20,blue);color("mac-avc-source-blue.png",true)
                repeat(5) { feed(10,red);feed(20,blue);color("mac-avc-source-overlap.png",true) }
                var submitted=0;var recoveries=0
                decoder.accessUnitObserver={submitted++};decoder.onRecovery={recoveries++}
                val beforeLoss=frames
                var next=(sequences.getValue(20L)+1) and 65535 // Lose one complete packet.
                decoder.accept(byteArrayOf(0x61,0),next++,ts,true,20)
                Thread.sleep(320);decoder.drain()
                repeat(20) {i ->decoder.accept(byteArrayOf(0x61,0),next++ and 65535,ts+(i+1)*90000L,true,20)}
                repeat(10) {Thread.sleep(16);decoder.drain()}
                check(submitted==0 && frames==beforeLoss && recoveries>0) {"Damaged dependent pictures reached hardware AVC"}
                color("mac-avc-loss-held.png",true)
                sequences[20L]=next and 65535;ts+=21*90000L
                feed(20,blue)
                check(frames>beforeLoss && submitted>0) {"Valid IDR did not resume decoding"}
                color("mac-avc-loss-recovered.png",true)
                check(decoder.activeSsrc==20L && frames>=2)
                return "PASS Qualcomm hardware AVC reordered FU-A/wrap/duplicate input; five retired red/current blue overlaps remain blue; packet loss holds clean image, suppresses dependent pictures and resumes on IDR; frames=$frames; ${decoder.assemblyDiagnostics()}"
            } finally { decoder.close() }
        }
    }
}
