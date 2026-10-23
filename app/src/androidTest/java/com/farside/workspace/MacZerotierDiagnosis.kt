package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.*
import java.io.File

/** Bounded direct media trace: only authenticated RTP metadata, never credentials or pixels. */
internal class MacZerotierDiagnosis(private val test:Instrumentation) {
    fun run(id:String):String {
        val context=test.targetContext
        val profile=ProfileManager(context).getProfiles().single {it.id==id && it.type==ConnectionType.VNC}
        require(profile.proxyType==ProxyType.NONE && !profile.useCloudflare)
        val rows=java.util.Collections.synchronizedList(mutableListOf<String>())
        val report=StringBuilder()
        MacMediaSurface(test).use {display ->
            val session=MacHevcSession(profile.host,profile.port,profile.user,profile.pass,
                preferAvc=true,audioEnabled=true,shareConsole=false,
                logCallback={android.util.Log.i("MacZerotierDiagnosis",it)})
            try {
                session.setSurface(display.surface);session.start()
                val pipeline=checkNotNull(session.videoPipeline)
                pipeline.avcPacketObserver={payload,seq,ts,marker,ssrc ->
                    if(rows.size<20000) rows.add("${System.nanoTime()/1000000},${ssrc.toString(16)},$seq,$ts,$marker,${payload.size},${payload.take(3).joinToString(":") {(it.toInt() and 255).toString(16)}}")
                }
                session.initStreamingLayer();session.startStreaming(display.surface)
                repeat(22) {
                    Thread.sleep(1000)
                    report.appendLine("$it frames=${pipeline.framesDecoded.get()} raw=${pipeline.packetsReceived.get()} auth=${pipeline.authenticatedPackets.get()} appDrops=${pipeline.qualitySnapshot().localDrops} rxBuffer=${pipeline.socketReceiveBufferBytes} ${pipeline.avcAssemblyDiagnostics}")
                }
            } finally {session.close()}
        }
        File(context.cacheDir,"mac-zerotier-rtp-private.csv").writeText(synchronized(rows) {rows.joinToString("\n")})
        File(context.cacheDir,"mac-zerotier-results.txt").writeText(report.toString())
        return "PASS bounded direct RTP collection; media success is separate.\n$report"
    }
}
