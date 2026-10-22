package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Read-only transport diagnosis using the explicitly selected saved companion. */
internal class MacRelayDiagnosis(private val test: Instrumentation) {
    fun run(id: String): String {
        val context=test.targetContext
        val pm=ProfileManager(context)
        val profile=pm.getProfiles().single { it.id==id }
        if(test.arguments?.getString("relaySameMacSetup")=="true") {
            require(profile.type==ConnectionType.VNC && profile.enableMacSshRelay)
            require(profile.sftpPairProfileId!=null || profile.macSshRelayProfileId!=null)
            pm.updateProfile(profile.copy(macSshRelaySameMac=true))
            check(context.getSharedPreferences("farside_profiles",0).edit().commit())
            check(ProfileManager(context).getProfiles().single { it.id==id }.macSshRelaySameMac)
            return "PASS explicit same-Mac relay setting saved for selected profile; other fields retained"
        }
        val ssh=pm.getProfiles().single { it.id==(profile.sftpPairProfileId ?: profile.macSshRelayProfileId) }
        val notes=File(context.cacheDir,"mac-relay-diagnosis.txt").apply { writeText("") }
        fun record(s:String) { notes.appendText("$s\n") }
        SshConnectionManager.acquire(context,ssh.id).use { lease ->
            val client=lease.connect(ssh.copy(reconnectAutomatically=false))
            record("PASS saved companion SSH authenticated through ${ssh.proxyType}")
            fun command(text:String):String=client.startSession().use { session -> session.exec(text).use { cmd ->
                val result=cmd.inputStream.bufferedReader().readText().trim()
                cmd.join(10,TimeUnit.SECONDS)
                check(cmd.exitStatus==0) { "Diagnostic command failed" };result
            } }
            if(test.arguments?.getString("relayHostAudio")=="true") {
                val devices=command("/usr/sbin/system_profiler SPAudioDataType -json")
                File(context.cacheDir,"mac-audio-devices-private.json").writeText(devices)
                record("Audio devices saved privately")
                record("Mac volume: "+command("/usr/bin/osascript -e 'get volume settings'"))
                val user=pm.getCredentials().single { it.id==profile.credentialId }.user
                fun quote(s:String)="'"+s.replace("'","'\"'\"'")+"'"
                File(context.cacheDir,"mac-user-mapping-private.txt").writeText(
                    "ssh="+command("/usr/bin/id -un")+"\nrecords="+
                    command("/usr/bin/dscl /Search -search /Users RealName ${quote(user)}"))
                return notes.readText()
            }
            val server=command("printf '%s' \"\$SSH_CONNECTION\"").split(" ")[2].let { if(it=="::1") "127.0.0.1" else it }
            File(context.cacheDir,"mac-relay-server-private.txt").writeText(server)
            record("SSH server loopback=${server.startsWith("127.")}")
            for((label,target) in listOf("saved-public-target" to profile.host,"ssh-server-endpoint" to server)) {
                try {
                    MacHevcSshRelay(context,client,target,::record,profile.port).use { relay ->
                        Socket("127.0.0.1",relay.port).use { socket ->
                            socket.soTimeout=6000
                            val bytes=ByteArray(12)
                            DataInputStream(socket.getInputStream()).readFully(bytes)
                            check(bytes.toString(Charsets.US_ASCII).startsWith("RFB "))
                            record("PASS $label forwarded RFB banner")
                        }
                    }
                } catch(e:Exception) { record("FAIL $label ${e.javaClass.simpleName}: ${e.message}") }
            }
            if(test.arguments?.getString("relayMedia")=="true") {
                val target=if(test.arguments?.getString("relayLan")=="true") {
                    command("/usr/sbin/ipconfig getifaddr \"\$(/sbin/route -n get default | /usr/bin/awk '/interface:/{print \$2}')\"")
                        .also { address ->
                            require(address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))
                            File(context.cacheDir,"mac-relay-lan-private.txt").writeText(address)
                            record("Using IPv4 discovered from Mac default-route interface")
                        }
                } else server
                val credential=pm.getCredentials().single { it.id==profile.credentialId }
                val recovery=File(context.cacheDir,"mac-relay-diagnosis-mute-$id.txt")
                fun restoreMute(value:String) {
                    require(value in listOf("true","false"))
                    repeat(10) {
                        command("/usr/bin/osascript -e 'set volume ${if(value=="true") "with" else "without"} output muted'")
                        if(command("/usr/bin/osascript -e 'output muted of (get volume settings)'")==value) return
                        Thread.sleep(500)
                    }
                    error("Recorded mute restoration failed")
                }
                if(recovery.exists()) { restoreMute(recovery.readText());check(recovery.delete()) }
                val originalMute=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
                require(originalMute in listOf("true","false"))
                recovery.writeText(originalMute)
                val thread=android.os.HandlerThread("RelayDiagnosisFrames").apply { start() }
                val images=android.media.ImageReader.newInstance(1920,1080,android.graphics.ImageFormat.PRIVATE,3)
                val shown=java.util.concurrent.atomic.AtomicLong()
                images.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { shown.incrementAndGet() } },android.os.Handler(thread.looper))
                try {
                    MacHevcSshRelay(context,client,target,::record,profile.port).use { relay ->
                        MacHevcSession("127.0.0.1",relay.port,credential.user,credential.pass,
                            preferAvc=true,audioEnabled=false,mediaDatagramSender={ p,b -> relay.send(p,b) },
                            logCallback={ android.util.Log.i("MacRelayDiagnosis",it) }).use { session ->
                            session.setSurface(images.surface);session.start();session.initStreamingLayer();session.startStreaming(images.surface)
                            val deadline=android.os.SystemClock.uptimeMillis()+20000
                            while(shown.get()==0L && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
                            record("MEDIA stage=${session.stage} datagrams=${relay.videoDatagrams.get()} authenticated=${session.videoPipeline?.authenticatedPackets?.get()} decoded=${session.videoPipeline?.framesDecoded?.get()} shown=${shown.get()}")
                            check(shown.get()>0) { "No relayed hardware video buffers" }
                        }
                    }
                } finally {
                    images.close();thread.quitSafely();thread.join(1000)
                    restoreMute(originalMute);check(recovery.delete());record("PASS pre-probe mute restored")
                }
            }
            record("PASS companion retained=${client.isConnected && command("printf alive")=="alive"}")
        }
        return notes.readText()
    }
}
