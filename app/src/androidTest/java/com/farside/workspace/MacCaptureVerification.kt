package com.farside.workspace

import android.app.Instrumentation
import android.os.SystemClock
import com.farside.workspace.data.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Capture only the explicitly selected saved Mac: no typing/unlock, volume or host changes. */
internal class MacCaptureVerification(private val test:Instrumentation) {
    fun run(id:String):String {
        val context=test.targetContext
        val prefs=context.getSharedPreferences("farside_profiles",0)
        val before=prefs.getString("profiles","")
        val profile=ProfileManager(context).getProfiles().single {it.id==id && it.type==ConnectionType.VNC}
        val evidence=File(context.cacheDir,"mac-capture-results.txt").apply {writeText("")}
        fun record(line:String) {evidence.appendText("$line\n")}
        if(test.arguments?.getString("macCaptureBaselineBefore")!="false") record(MacNativeBaseline(test).run(id))
        else record("Cold startup: no preliminary native VNC connection")
        MacMediaSurface(test).use {displayed ->
            val bridge=VncBridge();val ready=CountDownLatch(1)
            var handle=0L;var failure:String?=null
            val connection=VncSessionConnection(context,profile.copy(reconnectAutomatically=false),bridge,
                {reason,_ ->failure=reason;ready.countDown()}, {}, {handle=it;ready.countDown()})
            val encoded=File(context.cacheDir,"mac-capture.h264").outputStream().buffered()
            var encodedBytes=0L
            try {
                connection.attach(displayed.surface)
                check(ready.await(90,TimeUnit.SECONDS) && handle!=0L) {"Selected Mac startup failed: $failure"}
                val pipeline=checkNotNull(bridge.macMediaPipeline)
                record("Connected route=${connection.macRoute} decoder=${pipeline.decoderDescription}")
                pipeline.avcConfiguration?.let {encoded.write(it);encodedBytes+=it.size}
                val idr=java.util.concurrent.atomic.AtomicLong()
                pipeline.avcAccessUnitObserver={unit ->
                    // Bound private evidence; identical Annex-B access units go to the hardware decoder.
                    if(encodedBytes+unit.size<=64L*1024*1024) {encoded.write(unit);encodedBytes+=unit.size}
                    if((0 until unit.size-4).any {i ->unit[i]==0.toByte() && unit[i+1]==0.toByte() && unit[i+2]==0.toByte() && unit[i+3]==1.toByte() && (unit[i+4].toInt() and 31)==5}) idr.incrementAndGet()
                }
                val keyframeAt=SystemClock.uptimeMillis()
                pipeline.requestKeyframe()
                val keyframeDeadline=keyframeAt+5000
                while(idr.get()==0L && SystemClock.uptimeMillis()<keyframeDeadline) Thread.sleep(50)
                record("Requested video keyframe; observedIdr=${idr.get()} elapsedMs=${SystemClock.uptimeMillis()-keyframeAt}")
                check(idr.get()>0) {"No IDR observed within five seconds of video feedback request"}

                fun sample(stage:String) {
                    repeat(32) {i ->
                        check(failure==null) {"Capture disconnected"}
                        check(displayed.capture("mac-capture-$stage-$i.png")) {"Missing or blank capture $stage/$i"}
                        record("$stage/$i time=${SystemClock.uptimeMillis()} decoded=${pipeline.framesDecoded.get()} streams=${pipeline.videoStreamSummary}")
                        Thread.sleep((137+(i%7)*31).toLong())
                    }
                    record("$stage ${pipeline.avcAssemblyDiagnostics}")
                }
                sample("initial")
                // Request again after startup has settled; an initial encoder burst is not a feedback response.
                val settledIdr=idr.get()
                val recoveryAt=SystemClock.uptimeMillis()
                pipeline.requestKeyframe()
                while(idr.get()<=settledIdr && SystemClock.uptimeMillis()<recoveryAt+5000) Thread.sleep(50)
                record("Settled video keyframe request additionalIdr=${idr.get()-settledIdr} elapsedMs=${SystemClock.uptimeMillis()-recoveryAt}")
                check(idr.get()>settledIdr) {"Settled stream did not answer recovery request"}
                bridge.updateDisplay(handle,1280,800,1f)
                val deadline=SystemClock.uptimeMillis()+10000
                while((pipeline.decodedWidth!=1280 || pipeline.decodedHeight!=800) && SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
                check(pipeline.decodedWidth==1280 && pipeline.decodedHeight==800) {"Resize not acknowledged by decoder"}
                record("Resize decoded=${pipeline.decodedWidth}x${pipeline.decodedHeight}")
                sample("resized")
                check(pipeline.framesDecoded.get()>4)
                val sshId=profile.sftpPairProfileId ?: profile.macSshRelayProfileId
                val ssh=ProfileManager(context).getProfiles().single {it.id==sshId && it.type==ConnectionType.SSH}
                com.farside.workspace.ssh.SshConnectionManager.acquire(context,ssh.id).use {lease ->
                    val client=lease.connect(ssh.copy(reconnectAutomatically=false))
                    fun diagnostic(name:String,command:String) {
                        runCatching {client.startSession().use {channel ->
                            channel.exec(command).use {remote ->
                                val text=remote.inputStream.bufferedReader().readText()
                                remote.join(15,TimeUnit.SECONDS)
                                File(context.cacheDir,"mac-capture-host-$name.txt").writeText(text)
                                record("Read-only host $name exit=${remote.exitStatus}; private bytes=${text.length}")
                            }
                        }}.onFailure {record("Read-only host $name unavailable: ${it.javaClass.simpleName}")}
                    }
                    diagnostic("processes","/bin/ps -axo pid,ppid,uid,comm | /usr/bin/grep -E 'screensharing|Screensharing|avconferenced|WindowServer|loginwindow' || true")
                    diagnostic("capture-log","/usr/bin/log show --last 2m --info --debug --style compact --predicate '(process == \"ScreensharingAgent\" OR process == \"screensharingd\" OR process == \"avconferenced\") AND (eventMessage CONTAINS[c] \"capture\" OR eventMessage CONTAINS[c] \"display\" OR eventMessage CONTAINS[c] \"session\")' 2>&1 | /usr/bin/tail -n 350")
                }

            } finally {
                try {connection.close();check(connection.awaitClosed())}
                finally {encoded.close()}
                record("Private Annex-B evidence bytes=$encodedBytes")
                check(!bridge.isMacHevc) {"Mac capture session did not close"}
            }
        }
        record(MacNativeBaseline(test).run(id))
        check(prefs.getString("profiles","")==before) {"Capture changed saved profiles"}
        record("PASS bounded live capture, no synthetic typing/unlock, saved profiles unchanged; images require visual review")
        return evidence.readText()
    }
}
