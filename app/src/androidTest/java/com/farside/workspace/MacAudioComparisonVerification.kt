package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Same owned sound source for direct/companion comparisons, without changing Mac audio settings. */
internal class MacAudioComparisonVerification(private val test:Instrumentation) {
    fun run(id:String):String {
        val context=test.targetContext
        val pm=ProfileManager(context)
        val savedProfile=pm.getProfiles().single {it.id==id && it.type==ConnectionType.VNC}
        val profile=if(test.arguments?.getString("macAudioConsole")=="true") savedProfile.copy(expMacShareConsole=true) else savedProfile
        test.arguments?.getString("macAudioCleanupProfile")?.let {ownedId ->
            check(ownedId!=profile.id)
            val owned=pm.getProfiles().single {it.id==ownedId}
            check(owned==profile.copy(id=ownedId,cardBackground=null,reconnectAutomatically=false))
            pm.deleteProfile(ownedId)
            check(context.getSharedPreferences("farside_profiles",0).edit().commit())
        }
        val before=pm.getProfiles().map {it.toJson().toString()}
        val sshId=test.arguments?.getString("macAudioSshProfile") ?: profile.sftpPairProfileId ?: profile.macSshRelayProfileId
        val ssh=pm.getProfiles().single {it.id==sshId && it.type==ConnectionType.SSH}
        val unlockConfirmation=File(context.cacheDir,"mac-audio-unlock-confirmed").apply {delete()}
        val report=File(context.cacheDir,"mac-audio-comparison.txt").apply {writeText("")}
        fun record(s:String) {report.appendText("$s\n")}
        SshConnectionManager.acquire(context,ssh.id).use {lease ->
            val client=lease.connect(ssh.copy(reconnectAutomatically=false))
            // Read-only non-admin evidence, before and after our owned relay.
            fun hostSnapshot(label:String) {
                client.startSession().use { channel ->
                    val cmd=channel.exec("/usr/bin/sw_vers; /usr/bin/id; /bin/ps -axo pid,ppid,user,comm; /usr/sbin/lsof -nP -iUDP:5900 -iUDP:5901; /usr/bin/stat -f '%Su' /dev/console")
                    val output=cmd.inputStream.bufferedReader().readText()
                    cmd.join(10,TimeUnit.SECONDS)
                    File(context.cacheDir,"mac-audio-host-$label-private.txt").writeText(output)
                }
            }
            hostSnapshot("before")
            MacMediaSurface(test,profile.copy(reconnectAutomatically=false),realSessionWindow=true).use {display ->
                check(display.awaitConnection()) {"Actual session did not connect"}
                val bridge=checkNotNull(display.bridge)
                check(bridge.isMacHevc) {"Ordinary VNC fallback has no audio"}
                val pipeline=checkNotNull(bridge.macMediaPipeline)
                val audio=checkNotNull(pipeline.audio)
                fun metrics(label:String) = "$label video=${pipeline.framesDecoded.get()} auth=${audio.authenticated.get()} bytes=${audio.payloadBytes.get()} maxAU=${audio.largestAccessUnit.get()} decoded=${audio.decodedFrames.get()} written=${audio.writtenFrames.get()} played=${audio.playedFrames} peak=${audio.peak.get()} dropped=${audio.dropped.get()} muted=${audio.muted} spatial=${bridge.audioSpatialBound} error=${audio.error}"
                Thread.sleep(2000)
                if(test.arguments?.getString("macAudioUnlockConfirmed")=="true") {
                    check(profile.vncAuthMode==VncAuthMode.MAC_USER)
                    // Require fresh inspection of this live window before any password submission.
                    display.capture("mac-audio-before-unlock.png")
                    val deadline=android.os.SystemClock.uptimeMillis()+60000
                    var confirmed=false
                    while(android.os.SystemClock.uptimeMillis()<deadline) {
                        if(!unlockConfirmation.exists()) {Thread.sleep(100);continue}
                        val action=unlockConfirmation.readText().trim();unlockConfirmation.delete()
                        if(action=="confirm") {confirmed=true;break}
                        check(action=="wake")
                        val wake=android.os.SystemClock.uptimeMillis()
                        display.touch(.5f,.5f,true,wake);Thread.sleep(80);display.touch(.5f,.5f,false,wake)
                        Thread.sleep(1500);display.capture("mac-audio-before-unlock.png")
                        record("One center-click wake requested after inspected wallpaper; refreshed field capture")
                    }
                    check(confirmed) {"No live own-account field confirmation; no password sent"}
                    val now=android.os.SystemClock.uptimeMillis()
                    display.touch(.5f,.90f,true,now);Thread.sleep(80);display.touch(.5f,.90f,false,now)
                    check(bridge.submitSavedMacPassword(display.handle))
                    record("One visually-confirmed own-account saved-password submission; no retries")
                    Thread.sleep(5000)
                }
                if(test.arguments?.getString("macAudioStandardFlags")=="true") {
                    // Bounded test-only wire comparison; the session is closed by this fixture.
                    val sessionField=VncBridge::class.java.getDeclaredField("macHevcSession").apply {isAccessible=true}
                    val session=sessionField.get(bridge) as MacHevcSession
                    check(session.isAltSession)
                    MacHevcSession::class.java.getDeclaredField("isAltSession").apply {isAccessible=true}.setBoolean(session,false)
                    session.reofferMediaStream();Thread.sleep(2000)
                    record("Diagnostic media re-offer uses standard flags7; existing GUI/auth/keys unchanged; owned session closes afterward")
                }
                record(metrics("before"))
                display.capture("mac-audio-comparison-start.png")
                client.startSession().use {channel ->
                    val sound=channel.exec("set -e; for n in 1 2 3; do /usr/bin/afplay /System/Library/Sounds/Glass.aiff; /bin/sleep 1; done")
                    try {
                        repeat(16) {i ->Thread.sleep(1000);record(metrics("source/$i"))}
                        sound.join(15,TimeUnit.SECONDS)
                        record("Owned afplay exit=${sound.exitStatus}; source settings unchanged")
                        File(context.cacheDir,"mac-audio-source-error-private.txt").writeText(sound.errorStream.bufferedReader().readText())
                        check(sound.exitStatus==0) {"Known sound source failed; inspect private error"}
                    } finally {
                        // A completed command's remote channel may already have been removed.
                        if(sound.exitStatus==null) runCatching {sound.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM)}
                    }
                }
                display.capture("mac-audio-comparison-end.png")
                record(metrics("after"))
                check(audio.error==null)
            }
            hostSnapshot("after")
            client.startSession().use { channel ->
                val cmd=channel.exec("/usr/bin/log show --last 1m --style compact --info --predicate 'process == \"screensharingagent\" OR process == \"avconferenced\" OR process == \"replayd\"'")
                File(context.cacheDir,"mac-audio-server-log-private.txt").writeText(cmd.inputStream.bufferedReader().readText())
                cmd.join(10,TimeUnit.SECONDS)
                File(context.cacheDir,"mac-audio-server-log-error-private.txt").writeText(cmd.errorStream.bufferedReader().readText())
                record("Read-only server log exit=${cmd.exitStatus}")
            }
        }
        check(pm.getProfiles().map {it.toJson().toString()}==before) {"Saved profiles changed"}
        record("PASS bounded real-window collection, owned source closed, profiles unchanged; nonzero audio/playback must be checked separately")
        return report.readText()
    }
}
