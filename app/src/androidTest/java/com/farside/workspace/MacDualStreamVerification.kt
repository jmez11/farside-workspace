package com.farside.workspace

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/** Two independent encrypted video sources over the same Apple UDP ports and two hardware decoders. */
internal class MacDualStreamVerification(private val test:Instrumentation) {
    /** Exercise the real bridge admission before testing encrypted packets/decoders below. */
    private fun verifyBridgeAdmission(surfaces:List<android.view.Surface>) {
        val endpoints=List(2) {java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).apply {soTimeout=4000}}
        val sockets=mutableListOf<java.net.Socket>()
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
        val attempts=endpoints.mapIndexed {i,endpoint ->workers.submit<Throwable?> {
            val profile=com.farside.workspace.data.ConnectionProfile("owned-$i","Owned bridge $i",
                com.farside.workspace.data.ConnectionType.VNC,"127.0.0.1",port=endpoint.localPort,
                user="fixture",pass="fixture",expMacHevc=true,macPreferAvc=true)
            runCatching {VncBridge().connect(profile.host,profile.port,profile.pass,surfaces[i],profile.user,
                com.farside.workspace.data.VncAuthMode.MAC_USER,profile)}.exceptionOrNull()
        }}
        try {
            endpoints.forEach {endpoint ->sockets.add(endpoint.accept())}
            check(sockets.size==2) {"Both production bridges must reach their own handshake concurrently"}
            // No banner/authentication is supplied. Only our sockets are touched.
            sockets.forEach {it.close()}
            attempts.forEach {attempt ->
                val failure=attempt.get(10,java.util.concurrent.TimeUnit.SECONDS)
                check(failure is java.io.IOException) {"Expected independent truncated handshakes, got $failure"}
            }
        } finally {
            endpoints.forEach {it.close()};sockets.forEach {it.close()}
            workers.shutdownNow();check(workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    fun run():String {
        check(test.targetContext.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        val english=test.targetContext.getString(R.string.mac_media_title)
        listOf("cs","da","de","el","es","fi","fr","it","ja","ko","nb","nl","pl","pt-BR","pt-PT","ro","ru","sv","tr","zh-CN","zh-HK","zh-TW").forEach {tag ->
            val config=android.content.res.Configuration(test.targetContext.resources.configuration).apply {setLocale(java.util.Locale.forLanguageTag(tag))}
            val localized=test.targetContext.createConfigurationContext(config)
            check(localized.getString(R.string.mac_media_title)!=english) {"Missing compiled locale $tag"}
            check(localized.getString(R.string.about_version,"fixture").contains("fixture"))
            val age=localized.getString(R.string.mac_quality_age_value,4.5)
            check(age.endsWith(" s") && !age.contains("%")) {"Invalid compiled frame-age format $tag"}
        }
        fun source(name:String)=DataInputStream(test.context.assets.open("mac-avc-source-$name.bin")).use {input ->
            List(input.readInt()) {ByteArray(input.readInt()).also(input::readFully)}
        }
        fun config(nals:List<ByteArray>):ByteArray {
            val sps=nals.single {it[0].toInt() and 31==7};val pps=nals.single {it[0].toInt() and 31==8}
            val avcc=ByteBuffer.allocate(11+sps.size+pps.size).apply {
                put(1);put(sps[1]);put(sps[2]);put(sps[3]);put(0xff.toByte());put(0xe1.toByte())
                putShort(sps.size.toShort());put(sps);put(1);putShort(pps.size.toShort());put(pps)
            }.array()
            return byteArrayOf(0x92.toByte())+ByteBuffer.allocate(94+avcc.size).apply {
                putInt(capacity());put("avc1".toByteArray());position(32);putShort(320);putShort(240)
                position(86);putInt(8+avcc.size);put("avcC".toByteArray());put(avcc)
            }.array()
        }
        val thread=HandlerThread("OwnedDualMacImages").apply {start()}
        val readers=List(2) {ImageReader.newInstance(320,240,ImageFormat.PRIVATE,3).apply {
            setOnImageAvailableListener({r ->r.acquireLatestImage()?.close()},Handler(thread.looper))
        }}
        val blobs=List(2) {i ->ByteArray(46) {(it+i*75).toByte()}}
        val feedback=List(2) {java.util.concurrent.CopyOnWriteArrayList<Int>()}
        val refreshes=List(2) {java.util.concurrent.atomic.AtomicInteger()}
        val displays=List(2) {java.util.concurrent.atomic.AtomicInteger()}
        val pipelines=List(2) {i ->MacHevcVideoPipeline(host="127.0.0.1",videoKeyBlob=blobs[i],videoKeyBlobV=blobs[i],surface=readers[i].surface,
            context=test.targetContext,datagramSender={port,bytes ->if(bytes.size>1) feedback[i].add(port)},preferAvc=true,
            onMediaRefresh={refreshes[i].incrementAndGet()},onDisplayRefresh={displays[i].incrementAndGet()})}
        val senders=List(2) {MacHevcSrtpSender(blobs[it],10L+it)}
        val sources=listOf(source("red"),source("blue"))
        try {
            verifyBridgeAdmission(readers.map {it.surface})
            pipelines.forEach {it.start()}
            DatagramSocket().use {socket ->
                blobs.forEachIndexed {i,blob ->
                    val packet=MacHevcSrtcpEncryptor(blob.copyOfRange(0,32),blob.copyOfRange(32,46)).protect(MacHevcRtcp.buildRr(100L+i))
                    socket.send(DatagramPacket(packet,packet.size,InetAddress.getByName("127.0.0.1"),5900+i))
                }
                val rtcpUntil=android.os.SystemClock.uptimeMillis()+3000
                while(pipelines.any {it.rtcpDiagnostics.snapshot().authenticated!=1L} && android.os.SystemClock.uptimeMillis()<rtcpUntil) Thread.sleep(50)
                pipelines.forEach {p ->
                    val r=p.rtcpDiagnostics.snapshot()
                    check(r.control==1L && r.video==1L && r.authenticated==1L && r.unverified==1L && r.rr==1L) {"Shared-port RTCP attribution failed: $r"}
                    check(p.authenticatedPackets.get()==0L && p.packetsReceived.get()==0L) {"RTCP leaked into video decoder"}
                }
                fun feed(index:Int,timestamp:Long,includeConfig:Boolean=true) {
                    fun send(bytes:ByteArray,last:Boolean=false) {
                        val packet=senders[index].protect(bytes,if(last) 224 else 96,timestamp)
                        socket.send(DatagramPacket(packet,packet.size,InetAddress.getByName("127.0.0.1"),5901))
                        Thread.sleep(2)
                    }
                    if(includeConfig) send(config(sources[index]))
                    val nals=sources[index].filter {it[0].toInt() and 31 in 1..5}
                    nals.forEachIndexed {n,nal ->
                        val chunks=nal.copyOfRange(1,nal.size).asList().chunked(900)
                        chunks.forEachIndexed {j,part ->
                            val flags=(if(j==0) 128 else 0) or (if(j==chunks.lastIndex) 64 else 0)
                            send(byteArrayOf(((nal[0].toInt() and 224) or 28).toByte(),((nal[0].toInt() and 31) or flags).toByte())+part.toByteArray(),n==nals.lastIndex && j==chunks.lastIndex)
                        }
                    }
                }
                feed(0,0,includeConfig=false)
                val startupUntil=android.os.SystemClock.uptimeMillis()+6000
                var startupStep=1L
                while(refreshes[0].get()==0 && android.os.SystemClock.uptimeMillis()<startupUntil) {
                    feed(1,startupStep++*90000L);Thread.sleep(150)
                }
                check(refreshes[0].get()==1 && pipelines[0].framesDecoded.get()==0L) {"Partial startup did not request a media offer"}
                repeat(4) {feed(0,(it+startupStep)*90000L);feed(1,(it+startupStep)*90000L);Thread.sleep(150)}
                val until=android.os.SystemClock.uptimeMillis()+10000
                while(pipelines.any {it.framesDecoded.get()<2} && android.os.SystemClock.uptimeMillis()<until) Thread.sleep(50)
                pipelines.forEachIndexed {i,p ->check(p.framesDecoded.get()>=2 && p.videoStreamSummary.startsWith((10L+i).toString(16)+":")) {"Dual decoder $i failed: ${p.decoderDescription}, frames=${p.framesDecoded.get()}, streams=${p.videoStreamSummary}"}}
                val feedbackBefore=feedback.map {it.size}
                pipelines.forEach {it.requestKeyframe()}
                val feedbackDeadline=android.os.SystemClock.uptimeMillis()+2000
                while(feedback.indices.any {feedback[it].size<feedbackBefore[it]+2} && android.os.SystemClock.uptimeMillis()<feedbackDeadline) Thread.sleep(50)
                feedback.forEachIndexed {i,ports ->check(ports.size>=feedbackBefore[i]+2 && ports.all {it==5900}) {"Video FIR used wrong media port: $ports"}}
                val recoveredBefore=pipelines.map {it.framesDecoded.get()}
                repeat(2) {step ->feed(0,(step+4)*90000L);feed(1,(step+4)*90000L);Thread.sleep(150)}
                val recoveredDeadline=android.os.SystemClock.uptimeMillis()+5000
                while(pipelines.indices.any {pipelines[it].framesDecoded.get()<=recoveredBefore[it]} && android.os.SystemClock.uptimeMillis()<recoveredDeadline) Thread.sleep(50)
                pipelines.forEachIndexed {i,p ->check(p.framesDecoded.get()>recoveredBefore[i]) {"Fresh IDR did not resume decoder $i"}}
                val settledFeedback=feedback.map {it.size}
                Thread.sleep(700)
                check(feedback.map {it.size}==settledFeedback) {"Video feedback continued after clean IDRs"}
                // Withhold stream0 entirely (no loss event), while stream1 stays healthy.
                val stalledFeedback=feedback[0].size
                val previousRefreshes=refreshes[0].get()
                val stallUntil=android.os.SystemClock.uptimeMillis()+16000
                var step=100L
                while(android.os.SystemClock.uptimeMillis()<stallUntil) {feed(1,step++*90000L);Thread.sleep(150)}
                check(feedback[0].size>stalledFeedback && refreshes[0].get()==previousRefreshes+1 && displays[0].get()==1) {
                    "Silent stall did not escalate: FIR=${feedback[0].size-stalledFeedback} offer=${refreshes[0].get()} display=${displays[0].get()}"
                }
                check(refreshes[1].get()==0 && displays[1].get()==0) {"Healthy neighboring stream refreshed"}
                val resumed=pipelines[0].framesDecoded.get()
                repeat(2) {feed(0,step++*90000L);Thread.sleep(150)}
                val resumeUntil=android.os.SystemClock.uptimeMillis()+3000
                while(pipelines[0].framesDecoded.get()<=resumed && android.os.SystemClock.uptimeMillis()<resumeUntil) Thread.sleep(50)
                check(pipelines[0].framesDecoded.get()>resumed) {"Stalled decoder did not resume after IDR"}
                val resumedFeedback=feedback[0].size
                Thread.sleep(700)
                check(feedback[0].size==resumedFeedback && refreshes[0].get()==previousRefreshes+1 && displays[0].get()==1) {"Recovery continued after frames resumed"}
                pipelines[0].close()
                val before=pipelines[1].framesDecoded.get()
                repeat(3) {feed(1,(it+6)*90000L);Thread.sleep(150)}
                check(pipelines[1].framesDecoded.get()>before) {"Closing one stream stopped the other"}
                return "PASS concurrent production VncBridge admission and hardware streams; silent16s stall automatically requests keyframes, media offer and display refresh, healthy neighbor untouched, fresh IDR resumes and stops recovery; feedback on5900, closing first preserves survivor. ACCESS_NETWORK_STATE granted. Owned replay; live recovery remains separate."
            }
        } finally {
            pipelines.forEach {it.close()};readers.forEach {it.close()};thread.quitSafely();thread.join(2000)
        }
    }
}
