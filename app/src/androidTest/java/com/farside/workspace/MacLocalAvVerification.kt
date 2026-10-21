package com.farside.workspace

import android.app.Instrumentation
import android.os.SystemClock
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Bounded local A/V check with transient sessions and a separate explicit streaming-copy save mode. */
internal class MacLocalAvVerification(private val test:Instrumentation) {
    private val context get()=test.targetContext
    private fun file(name:String)=File(context.cacheDir,"mac-local-av-$name")
    fun saveStreamingCopy(id:String):String {
        val pm=ProfileManager(context)
        val original=pm.getProfiles()
        val credentials=pm.getCredentials().map { it.id }
        val p=original.single { it.id==id && it.type==ConnectionType.VNC }
        require(!p.useCloudflare && p.proxyType==ProxyType.NONE && !p.expMacHevc)
        val copyId=java.util.UUID.nameUUIDFromBytes("mac-streaming:$id".toByteArray()).toString()
        val copy=p.copy(id=copyId,name="${p.name} Streaming",expMacHevc=true,macPreferAvc=true,
            macAudioEnabled=true,macSshRelayProfileId=null,reconnectAutomatically=false)
        original.find { it.id==copyId }?.let { check(it==copy) { "Streaming copy has changed; preserve it" } }
        pm.addProfile(copy)
        check(context.getSharedPreferences("farside_profiles",0).edit().commit())
        val saved=pm.getProfiles()
        check(original.all { previous -> saved.single { it.id==previous.id }==previous })
        check(saved.single { it.id==copyId }==copy && pm.getCredentials().map { it.id }==credentials)
        return "PASS saved hardware H.264/audio streaming copy; original profiles and credentials retained; automatic reconnect off"
    }
    fun run(id:String):String {
        val output=file("results.txt");output.writeText("")
        File(context.cacheDir,"mac-local-av-current.png").delete()
        fun record(s:String) { output.appendText("${System.currentTimeMillis()}: $s\n") }
        val pm=ProfileManager(context)
        val p=pm.getProfiles().single { it.id==id && it.type==ConnectionType.VNC }
        require(p.proxyType==ProxyType.NONE && !p.useCloudflare && !p.expMacHevc && !p.reconnectAutomatically)
        val ssh=pm.getProfiles().single { it.type==ConnectionType.SSH && it.host==p.host && it.proxyType==ProxyType.NONE }
        val credential=pm.getCredentials().single { it.id==p.credentialId }
        val sshCredential=pm.getCredentials().single { it.id==ssh.credentialId }
        val shareConsole=test.arguments?.getString("macShareConsole")=="true"
        val gate=MacAuthenticationGate { false }
        fun baseline(label:String) {
            gate.await()
            check(MacNativeBaseline(test).run(p.id).contains("PASS standard Mac VNC"))
            record("PASS $label standard VNC, clean close")
        }
        baseline("before")
        val lease=SshConnectionManager.acquire(context,"mac-local-av-debug")
        var awake:net.schmizz.sshj.connection.channel.direct.Session?=null
        var failure:Exception?=null
        var reject=false
        var sshConnected=false
        var unlockSubmitted=false
        var desktopConfirmed=false
        var source:java.util.concurrent.CompletableFuture<String>?=null
        try {
            gate.await()
            val client=lease.connect(ssh.copy(reconnectAutomatically=false))
            sshConnected=true
            fun command(text:String):String=client.startSession().use { channel -> channel.exec(text).use { cmd ->
                val result=cmd.inputStream.bufferedReader().readText().trim()
                cmd.join(15,TimeUnit.SECONDS)
                check(cmd.exitStatus==0) { "Owned SSH command failed: status ${cmd.exitStatus}" }
                result
            } }
            check(command("id -un")==sshCredential.user)
            record("PASS saved local SSH identity; no relay")
            fun restoreMute(value:String) {
                require(value=="true" || value=="false")
                var actual=""
                repeat(10) {
                    command("/usr/bin/osascript -e 'set volume ${if(value=="true") "with" else "without"} output muted'")
                    actual=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
                    if(actual==value) return
                    Thread.sleep(500)
                }
                error("Mac mute restoration failed: expected=$value actual=$actual")
            }
            // Recovery only when an operator supplies a previously recorded pre-test state.
            test.arguments?.getString("macRestoreMute")?.let { restoreMute(it);record("PASS explicitly recorded prior mute recovered") }
            val originalMute=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
            file("host.txt").writeText(command("/usr/bin/sw_vers; /usr/bin/stat -f '%Su %u' /dev/console; /usr/bin/osascript -e 'get volume settings'; /usr/sbin/system_profiler SPAudioDataType -json"))
            awake=client.startSession().also { it.exec("exec /usr/bin/caffeinate -diu -t 900") }
            gate.await()
            val action=file("action.json").also { it.delete() }
            try { MacMediaSurface(test,p.copy(expMacHevc=true,macPreferAvc=true,macAudioEnabled=true,expMacShareConsole=shareConsole,macSshRelayProfileId=null),realSessionWindow=true).use { display ->
                check(display.awaitConnection()) { "A/V connection failed; stop new authentication" }
                val bridge=checkNotNull(display.bridge);val handle=display.handle
                fun key(value:Int) { bridge.sendUnicodeKeyEvent(handle,value,true);bridge.sendUnicodeKeyEvent(handle,value,false) }
                var observed:MacScreenAudio?=null;var unitsSaved=0
                var actions=0;var finished=false;var nextMetrics=0L
                record("READY bounded local hardware H.264/AAC session; inspect current PNG before any input; maximum 600s")
                record("Existing console mode=$shareConsole; no automatic unlock")
                val until=SystemClock.uptimeMillis()+600000
                while(!finished && SystemClock.uptimeMillis()<until) {
                    display.capture("mac-local-av-current.png")
                    val pipeline=checkNotNull(bridge.macMediaPipeline)
                    val audio=pipeline.audio
                    if(audio!=null && audio!==observed) {
                        observed=audio
                        audio.accessUnitObserver={ bytes ->
                            if(bytes.size>4 && unitsSaved<200) file("aac-${unitsSaved++}.bin").writeBytes(bytes)
                        }
                        record("Audio pipeline ready; observing non-silence-sized compressed units")
                    }
                    if(SystemClock.uptimeMillis()>=nextMetrics) {
                        record("MEDIA frames=${pipeline.framesDecoded.get()} audioPackets=${audio?.authenticated?.get()} bytes=${audio?.payloadBytes?.get()} maxAU=${audio?.largestAccessUnit?.get()} decoded=${audio?.decodedFrames?.get()} written=${audio?.writtenFrames?.get()} played=${audio?.playedFrames} peak=${audio?.peak?.get()} spatial=${bridge.audioSpatialBound} error=${audio?.error}")
                        nextMetrics=SystemClock.uptimeMillis()+5000
                    }
                    if(action.exists()) {
                        val request=JSONObject(action.readText());action.delete();check(++actions<=40)
                        when(request.getString("action")) {
                            "click" -> {
                                val x=request.getDouble("x");val y=request.getDouble("y");require(x in 0.0..1.0 && y in 0.0..1.0)
                                val px=(bridge.getDesktopWidth(handle)*x).toInt();val py=(bridge.getDesktopHeight(handle)*y).toInt()
                                bridge.sendPointerEvent(handle,px,py,0);Thread.sleep(80)
                                bridge.sendPointerEvent(handle,px,py,1);Thread.sleep(100);bridge.sendPointerEvent(handle,px,py,0)
                            }
                            "unlock" -> {
                                check(!unlockSubmitted && request.getBoolean("visuallyConfirmedOwnPasswordField"))
                                unlockSubmitted=true
                                credential.pass.codePoints().forEach { value -> key(value);Thread.sleep(60) };key(13)
                                record("One visually gated own-account test unlock submitted; no credentials logged; no retry")
                            }
                            "unlockSaved" -> {
                                check(!unlockSubmitted && request.getBoolean("visuallyConfirmedOwnPasswordField"))
                                unlockSubmitted=true
                                check(bridge.submitSavedMacPassword(handle)) { "Saved password action unavailable" }
                                check(!bridge.submitSavedMacPassword(handle)) { "Saved password action permitted a duplicate submission" }
                                record("Production saved-password action submitted once after visual confirmation; duplicate refused")
                            }
                            "desktop" -> { check(request.getBoolean("visuallyConfirmedOwnDesktop"));desktopConfirmed=true;record("Own desktop visually confirmed") }
                            "sound" -> {
                                check(source==null || source!!.isDone)
                                source=java.util.concurrent.CompletableFuture.supplyAsync {
                                    command("set -e; m=\$(/usr/bin/osascript -e 'output muted of (get volume settings)'); restore() { if test \"\$m\" = true; then /usr/bin/osascript -e 'set volume with output muted'; else /usr/bin/osascript -e 'set volume without output muted'; fi; }; trap restore EXIT HUP INT TERM; /usr/bin/osascript -e 'set volume without output muted'; for i in 1 2 3 4 5; do /usr/bin/afplay /System/Library/Sounds/Glass.aiff; /bin/sleep 1; done")
                                }
                                record("Owned finite test sound started over SSH, original mute restored by trap")
                            }
                            "player" -> { check(desktopConfirmed);command("/usr/bin/open -a 'QuickTime Player' /System/Library/Sounds/Glass.aiff");record("Owned bundled sound opened in GUI player; inspect before playback") }
                            "space" -> { check(desktopConfirmed);key(32) }
                            "finish" -> finished=true
                            else -> error("Unknown bounded A/V test action")
                        }
                    }
                    Thread.sleep(500)
                }
                source?.get(30,TimeUnit.SECONDS)
                display.capture("mac-local-av-final.png")
                val video=checkNotNull(bridge.macMediaPipeline);val audio=checkNotNull(video.audio)
                record("FINAL hardware=${video.decoderDescription} frames=${video.framesDecoded.get()} audioPackets=${audio.authenticated.get()} maxAU=${audio.largestAccessUnit.get()} decoded=${audio.decodedFrames.get()} written=${audio.writtenFrames.get()} played=${audio.playedFrames} peak=${audio.peak.get()}")
                check(finished && desktopConfirmed && video.framesDecoded.get()>0 && audio.decodedFrames.get()>0 && audio.playedFrames>0 && audio.peak.get()>0) { "No verified unlocked non-silent compressed A/V session" }
            } } catch(e:Exception) {
                record("A/V failure before cleanup: ${e.javaClass.simpleName}: ${e.message}");throw e
            } finally {
                try { source?.get(30,TimeUnit.SECONDS) }
                finally { restoreMute(originalMute);record("PASS pre-connection Mac mute restored after stream close") }
            }
            check(command("printf retained_ssh")=="retained_ssh")
        } catch(e:Exception) {
            failure=e;reject=!sshConnected || e is VncAuthenticationException || e.message?.contains("A/V connection failed")==true
            record("FAIL ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { source?.get(30,TimeUnit.SECONDS) }
            runCatching { awake?.close() };lease.close()
        }
        check(!reject && !(unlockSubmitted && !desktopConfirmed)) { "Authentication rejected or unlock unconfirmed; stop new attempts" }
        baseline("after")
        failure?.let { throw it }
        record("PASS local compressed hardware video and non-silent decoded/played audio; baselines retained")
        return output.readText()
    }
}
