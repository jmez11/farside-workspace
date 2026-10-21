package com.farside.workspace

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Production automatic routing and codecs without requiring a foreground Horizon window. */
internal class MacAutomaticConnectionVerification(private val test:Instrumentation) {
    fun run(id:String):String {
        val context=test.targetContext;val pm=ProfileManager(context)
        val profile=pm.getProfiles().single { it.id==id && it.type==ConnectionType.VNC }
        require(profile.proxyType==ProxyType.NONE && !profile.useCloudflare)
        val ssh=pm.getProfiles().single { it.host==profile.host && it.type==ConnectionType.SSH }
        val gate=MacAuthenticationGate { false }
        val lease=SshConnectionManager.acquire(context,ssh.id)
        gate.await()
        val client=lease.connect(ssh.copy(reconnectAutomatically=false))
        fun command(text:String):String=client.startSession().use { channel -> channel.exec(text).use { cmd ->
            val result=cmd.inputStream.bufferedReader().readText().trim();cmd.join(10,TimeUnit.SECONDS)
            check(cmd.exitStatus==0);result
        } }
        fun restoreMute(value:String) {
            require(value in listOf("true","false"))
            repeat(10) {
                command("/usr/bin/osascript -e 'set volume ${if(value=="true") "with" else "without"} output muted'")
                if(command("/usr/bin/osascript -e 'output muted of (get volume settings)'")==value) return
                Thread.sleep(500)
            }
            error("Recorded pre-test mute did not restore")
        }
        val restoreFile=File(context.cacheDir,"mac-automatic-restore-$id.txt")
        if(restoreFile.exists()) {
            val recorded=restoreFile.readText()
            check(recorded in listOf("true","false"))
            restoreMute(recorded)
            check(restoreFile.delete())
        }
        val originalMute=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
        check(originalMute in listOf("true","false"))
        restoreFile.writeText(originalMute)
        check(restoreFile.readText()==originalMute)
        val awake=client.startSession()
        val awakeCommand=awake.exec("exec /usr/bin/caffeinate -diu -t 180")
        val thread=HandlerThread("MacAutomaticFrames").apply { start() }
        val images=ImageReader.newInstance(1920,1080,ImageFormat.PRIVATE,3)
        val shown=AtomicLong()
        val firstFrameAt=AtomicLong()
        val imageError=java.util.concurrent.atomic.AtomicReference<Exception?>()
        images.setOnImageAvailableListener({ reader ->
            try { reader.acquireLatestImage()?.use { firstFrameAt.compareAndSet(0,SystemClock.uptimeMillis());shown.incrementAndGet() } }
            catch(error:Exception) { imageError.compareAndSet(null,error) }
        },Handler(thread.looper))
        val bridge=VncBridge();val ready=CountDownLatch(1)
        var handle=0L;var disconnected:String?=null
        val connection=VncSessionConnection(context,profile.copy(expMacHevc=false,macPreferAvc=false,
            macAudioEnabled=false,reconnectAutomatically=false,sftpPairProfileId=ssh.id),bridge,
            { reason,_ -> disconnected=reason;ready.countDown() },{}, { handle=it;ready.countDown() })
        var pipeline:MacHevcVideoPipeline?=null
        try {
            check(MacNativeBaseline(test).run(id).startsWith("PASS"))
            val start=SystemClock.uptimeMillis()
            connection.attach(images.surface)
            check(ready.await(60,TimeUnit.SECONDS)) { "Automatic Mac connection timed out" }
            val startupMs=SystemClock.uptimeMillis()-start
            check(handle!=0L && disconnected==null) { "Automatic Mac connection failed: $disconnected" }
            check(connection.macRoute==MacConnectionPolicy.Route.DIRECT && bridge.isMacHevc && bridge.isConfirmedMac)
            pipeline=checkNotNull(bridge.macMediaPipeline)
            val video=checkNotNull(pipeline)
            val audioPacketsBeforeSource=video.audio?.authenticated?.get() ?: 0
            // A finite, owned source makes audio validation independent of a quiet desktop.
            command("set -e; m=\$(/usr/bin/osascript -e 'output muted of (get volume settings)'); restore() { if test \"\$m\" = true; then /usr/bin/osascript -e 'set volume with output muted'; else /usr/bin/osascript -e 'set volume without output muted'; fi; }; trap restore EXIT HUP INT TERM; /usr/bin/osascript -e 'set volume without output muted'; /usr/bin/afplay /System/Library/Sounds/Glass.aiff")
            val deadline=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<deadline && ((video.audio?.decodedFrames?.get() ?: 0)==0L || shown.get()==0L)) Thread.sleep(50)
            val firstFrameMs=firstFrameAt.get()-start
            val audio=checkNotNull(video.audio)
            check(imageError.get()==null) { "Image consumer failed: ${imageError.get()?.javaClass?.simpleName}" }
            val mediaStats="readyMs=$startupMs firstRenderedBufferMs=$firstFrameMs shown=${shown.get()} video=${video.framesDecoded.get()} audioBeforeSource=$audioPacketsBeforeSource audioPackets=${audio.authenticated.get()} audioBytes=${audio.payloadBytes.get()} decoded=${audio.decodedFrames.get()} peak=${audio.peak.get()} error=${audio.error}"
            File(context.cacheDir,"mac-automatic-current-metrics.txt").writeText(mediaStats)
            check(shown.get()>0 && video.framesDecoded.get()>0 && audio.decodedFrames.get()>0 && audio.error==null) { mediaStats }
            check(video.decoderDescription.contains("qti",true) || video.decoderDescription.contains("qcom",true))
            check(bridge.canSubmitSavedMacPassword(handle))
            bridge.updateDisplay(handle,1280,720,1f)
            val resizeDeadline=SystemClock.uptimeMillis()+7000
            while(SystemClock.uptimeMillis()<resizeDeadline && (video.decodedWidth!=1280 || video.decodedHeight!=720)) Thread.sleep(50)
            check(video.decodedWidth==1280 && video.decodedHeight==720) { "Mac resize not acknowledged: ${video.decodedWidth}x${video.decodedHeight}" }
            Thread.sleep(9000) // Idle desktop must survive the shorter authentication deadline.
            check(disconnected==null)
            val metrics="PASS automatic DIRECT without companion, hardware=${video.decoderDescription}, frames=${video.framesDecoded.get()}, shown=${shown.get()}, AAC decoded=${audio.decodedFrames.get()}, peak=${audio.peak.get()}, audioBeforeSource=$audioPacketsBeforeSource, readyMs=$startupMs, firstRenderedBufferMs=$firstFrameMs, resize=1280x720, idle alive; standard VNC before/after, mute restored, SSH relay helper/banner/shared transport retained"
            File(context.cacheDir,"mac-automatic-metrics.txt").writeText(metrics)
            return metrics
        } finally {
            connection.close()
            try {
                check(connection.awaitClosed()) { "Mac connection worker did not finish" }
                val deadline=SystemClock.uptimeMillis()+15000
                while((bridge.isMacHevc || pipeline?.audio!=null) && SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
                check(!bridge.isMacHevc && pipeline?.audio==null) { "Media teardown incomplete" }
                restoreMute(originalMute)
                check(restoreFile.delete())
                if(handle!=0L && disconnected==null) {
                    check(MacNativeBaseline(test).run(id).startsWith("PASS"))
                    MacHevcSshRelay(context,client,profile.host,{ android.util.Log.i("MacRelayFixture",it) },profile.port).use { relay ->
                        java.net.Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress("127.0.0.1",relay.port),2000)
                            socket.soTimeout=3000
                            val banner=ByteArray(12).also { java.io.DataInputStream(socket.getInputStream()).readFully(it) }
                            check(banner.toString(Charsets.US_ASCII).startsWith("RFB "))
                        }
                    }
                    check(client.isConnected && command("printf companion-alive")=="companion-alive")
                }
            } finally {
                images.close();thread.quitSafely();thread.join(1000)
                try {
                    awakeCommand.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM)
                    awakeCommand.join(5,TimeUnit.SECONDS)
                    awake.close()
                } finally { lease.close() }
            }
        }
    }
}
