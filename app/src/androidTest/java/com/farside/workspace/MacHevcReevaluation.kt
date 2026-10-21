package com.farside.workspace

import android.app.Instrumentation
import android.graphics.SurfaceTexture
import android.media.MediaCodecList
import android.os.SystemClock
import android.view.Surface
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit saved profiles only. No setup, wrong-password, login typing, or retry loops. */
internal class MacHevcReevaluation(private val test: Instrumentation) {
    private val output = File(test.targetContext.cacheDir, "mac-hevc-reevaluation.txt")
    private val lastAttempt = File(test.targetContext.cacheDir, "mac-hevc-last-auth.txt")
    private fun record(s: String) { synchronized(output) { output.appendText("${System.currentTimeMillis()}: $s\n") } }
    private fun space() {
        val previous = lastAttempt.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0
        val remaining = 5_000 - (System.currentTimeMillis() - previous)
        if (remaining > 0) { record("Authentication cooldown ${remaining}ms"); Thread.sleep(remaining) }
        lastAttempt.writeText(System.currentTimeMillis().toString())
    }
    private class TestInputGateClosed(val credentialSubmitted:Boolean):java.io.IOException("Test input visual confirmation was cancelled or timed out")
    fun run(id: String, transportOnly: Boolean = false, relayMedia: Boolean = false, audioTest: Boolean = false, preferAvc: Boolean = false, production: Boolean = false, guiAudio: Boolean = false, interactive: Boolean = false, hostAudioDiagnostics: Boolean = false, guiRouting: Boolean = false, stability:Boolean=false, autoUnlock:Boolean=false, srp:Boolean=false, baselineOnly:Boolean=false, flicker:Boolean=false, noControlPoll:Boolean=false, cleanAgent:Boolean=false, fullSessionAlias:Boolean=false, hevcCapture:Boolean=false, selectDisplay:Boolean=false, consoleInfo:Boolean=false, selectIndex:Boolean=false): String {
        output.writeText("")
        val pm = ProfileManager(test.targetContext)
        val vnc = pm.getProfiles().single { it.id == id && it.type == ConnectionType.VNC }
        require(vnc.proxyType == ProxyType.NONE && !vnc.useCloudflare && !vnc.expMacHevc && !vnc.reconnectAutomatically)
        val ssh = pm.getProfiles().single { it.type == ConnectionType.SSH && it.host == vnc.host && it.proxyType == ProxyType.NONE }
        val vc = pm.getCredentials().single { it.id == vnc.credentialId }
        val sc = pm.getCredentials().single { it.id == ssh.credentialId }
        require(vc.user.isNotBlank() && sc.user.isNotBlank() && vc.pass.isNotEmpty() && sc.pass.isNotEmpty())
        record("Explicit same-host direct SSH/VNC profiles; separate saved usernames; no profile changes")
        for (codec in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } }) {
            val caps = codec.getCapabilitiesForType("video/hevc")
            record("HEVC codec=${codec.name} hardware=${codec.isHardwareAccelerated} profiles=${caps.profileLevels.map { it.profile }.distinct()}")
        }
        space()
        val lease = SshConnectionManager.acquire(test.targetContext, if(production) "mac-media-${vnc.id}" else "mac-hevc-reevaluation")
        var awakeSession: net.schmizz.sshj.connection.channel.direct.Session? = null
        var awakeCommand: net.schmizz.sshj.connection.channel.direct.Session.Command? = null
        try {
            val client = lease.connect(ssh.copy(reconnectAutomatically = false))
            fun command(cmd: String): String = client.startSession().use { session ->
                session.exec(cmd).use { exec ->
                    val value = exec.inputStream.bufferedReader().readText().trim()
                    exec.join(10, TimeUnit.SECONDS)
                    check(exec.exitStatus == 0) { "Read-only SSH command failed" }
                    value
                }
            }
            check(command("id -un") == sc.user) { "Saved SSH username differs from authenticated short username; stop" }
            if(fullSessionAlias) {
                val identity=command("/usr/bin/dscl . -read /Users/\$(id -un) RealName")
                File(test.targetContext.cacheDir,"mac-flicker-account-alias.txt").writeText(identity)
                check(identity.lineSequence().map { it.trim().removePrefix("RealName:").trim() }.any { it==vc.user }) { "Saved VNC full alias does not match the authenticated SSH account" }
                record("PASS saved VNC full alias matches authenticated SSH account directory identity; no guessed username")
            }
            record("PASS baseline SSH authentication + command; macOS=${command("sw_vers -productVersion")} arch=${command("uname -m")}")
            val restoreAudio=File(test.targetContext.cacheDir,"mac-audio-restore.json")
            if(restoreAudio.exists()) {
                val saved=org.json.JSONObject(restoreAudio.readText())
                val level=saved.getInt("volume");require(level in 0..100)
                val mute=saved.getBoolean("muted")
                command("/usr/bin/osascript -e 'set volume output volume $level ${if(mute) "with" else "without"} output muted'")
                check(command("/usr/bin/osascript -e 'output volume of (get volume settings)'")==level.toString())
                check(command("/usr/bin/osascript -e 'output muted of (get volume settings)'")==mute.toString())
                restoreAudio.delete();record("PASS original pre-test Mac volume/mute restored")
            }
            if(consoleInfo) {
                val identity=command("/usr/bin/stat -f '%Su %u' /dev/console; /usr/bin/id -u")
                File(test.targetContext.cacheDir,"mac-console-identity.txt").writeText(identity)
                val tools=command("for p in '/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession' '/System/Library/CoreServices/loginwindow.app/Contents/MacOS/CGSession'; do if test -x \"\$p\"; then printf '%s\\n' \"\$p\"; /usr/bin/strings \"\$p\" | /usr/bin/grep -E 'switchToUserID|suspend|Usage|usage|[Ff]ast [Uu]ser' || true; fi; done")
                File(test.targetContext.cacheDir,"mac-console-tools.txt").writeText(tools)
                record("Read-only console identity and available built-in session-switch tool metadata saved privately; no console switch attempted")
                return output.readText()
            }
            if(hostAudioDiagnostics) {
                val logs=command("/usr/bin/log show --last 10m --style compact --predicate '(process == \"avconferenced\" OR process == \"ScreensharingAgent\" OR process == \"screensharingd\" OR process == \"coreaudiod\") AND (eventMessage CONTAINS[c] \"audio\" OR eventMessage CONTAINS[c] \"capture\" OR eventMessage CONTAINS[c] \"permission\" OR eventMessage CONTAINS[c] \"displayID\")' 2>&1 | /usr/bin/tail -n 350")
                File(test.targetContext.cacheDir,"mac-audio-host-logs.txt").writeText(logs)
                val context=command("/bin/launchctl asuser \"\$(id -u)\" /usr/bin/osascript -e 'get volume settings' 2>&1 || true")
                File(test.targetContext.cacheDir,"mac-audio-gui-context.txt").writeText(context)
                record("Host audio/capture diagnostics saved privately (${logs.length} chars); log access denied=${logs.contains("Operation not permitted")}; no media connection or input")
                return output.readText()
            }
            check(command("test -x /usr/bin/caffeinate && printf available") == "available")
            awakeSession = client.startSession()
            val awake = awakeSession!!.exec("exec /usr/bin/caffeinate -diu -t 900").also { awakeCommand = it }
            Thread.sleep(300)
            check(awake.exitStatus == null && command("pmset -g assertions").contains("caffeinate")) { "Sleep assertion not established" }
            record("PASS bounded caffeinate system/display idle assertion active (maximum 900s; no permanent power settings; does not unlock Mac)")
            fun baseline(label: String) {
                space()
                val evidence = VncVerification(test,liveFrameTimeoutMs=45000).run("live", vnc.id)
                check(evidence.contains("LIVE PASS"))
                    record("PASS $label VNC: authenticated nonblank surface, unlock state unverified, synthetic pointer, clean teardown")
            }
            if(baselineOnly) {
                baseline("baseline-only")
                val sound=command("/usr/bin/osascript -e 'get volume settings'")
                record("Read-only final sound state: $sound")
                return output.readText()
            }
            // Owned ephemeral UDP echo over the actual Quest VPN; no host files/settings or credentials.
            val tcpPeer = command("printf '%s' \"\$SSH_CONNECTION\"").trim().split(" ").first()
            File(test.targetContext.cacheDir, "mac-hevc-tcp-peer.txt").writeText(tcpPeer)
            for (sourcePort in if (transportOnly) listOf(5900, 5901) else listOf(0)) {
            if (command("test -x /usr/bin/perl && printf available || true") == "available") {
                client.startSession().use { remote ->
                    val script = "\$|=1; alarm 12; socket(my \$s,Socket::PF_INET(),Socket::SOCK_DGRAM(),0) or die \"socket: \$!\"; bind(\$s,Socket::sockaddr_in(0,Socket::INADDR_ANY())) or die \"bind: \$!\"; my (\$port)=Socket::sockaddr_in(getsockname(\$s)); print \$port,\"\\n\"; my \$peer=recv(\$s,my \$b,128,0); my (\$pp,\$pa)=Socket::sockaddr_in(\$peer); print STDERR Socket::inet_ntoa(\$pa),\":\",\$pp,\"\\n\"; send(\$s,\$b,0,\$peer);"
                    remote.exec("/usr/bin/perl -MSocket -e '$script'").use { echo ->
                        val line = echo.inputStream.bufferedReader().readLine()
                        val port = line?.trim()?.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            File(test.targetContext.cacheDir, "mac-hevc-echo-error.txt").writeText(echo.errorStream.bufferedReader().readText())
                            record("UDP echo helper unavailable: invalid bound port; private stderr saved")
                        } else java.net.DatagramSocket(sourcePort).use { socket ->
                            socket.soTimeout = 5000
                            val token = java.util.UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
                            val address = java.net.InetAddress.getByName(vnc.host)
                            val packet = java.net.DatagramPacket(ByteArray(128), 128)
                            try {
                                socket.send(java.net.DatagramPacket(token, token.size, address, port))
                                socket.receive(packet)
                                check(packet.address == address && packet.port == port && packet.data.copyOfRange(0, packet.length).contentEquals(token))
                                val peer = echo.errorStream.bufferedReader().readText().trim()
                                File(test.targetContext.cacheDir, "mac-hevc-echo-peer-$sourcePort.txt").writeText(peer)
                                val translated = peer.substringAfterLast(":").toIntOrNull()
                                val origin = peer.substringBeforeLast(":")
                                record("PASS UDP echo round trip sourcePort=$sourcePort portPreserved=${translated == socket.localPort} udpSourceMatchesTcpPeer=${origin == tcpPeer}")
                            } catch (e: java.io.IOException) { record("UDP ECHO FAILED ${e.javaClass.simpleName}: ${e.message}") }
                        }
                    }
                }
            } else record("UDP echo unavailable: host Perl missing")
            }
            if (transportOnly) return output.readText()
            fun diagnostic(name: String, cmd: String) {
                runCatching { command(cmd) }.onSuccess {
                    File(test.targetContext.cacheDir, "mac-hevc-host-$name.txt").writeText(it)
                    record("Host diagnostic $name saved privately (${it.length} chars)")
                }.onFailure { record("Host diagnostic $name unavailable: ${it.javaClass.simpleName}") }
            }
            fun sessionDiagnostic(label:String) {
                if(!flicker) return
                diagnostic("sessions-$label", "/bin/ps -axo pid,ppid,uid,comm | /usr/bin/grep -E 'screensharing|Screensharing|avconferenced|WindowServer' || true")
                diagnostic("connections-$label", "/usr/sbin/lsof -nP -iTCP:5900 -sTCP:ESTABLISHED 2>&1 || true")
                diagnostic("displays-$label", "/usr/sbin/system_profiler SPDisplaysDataType -json")
            }
            sessionDiagnostic("before-baseline")
            baseline("before probe")
            sessionDiagnostic("after-baseline")
            if(cleanAgent) {
                val owner=command("id -u").toInt()
                val expected="/System/Library/CoreServices/RemoteManagement/ScreensharingAgent.bundle/Contents/MacOS/ScreensharingAgent"
                val rows=command("/bin/ps -axo pid=,uid=,comm=").lineSequence().map { it.trim().split(Regex("\\s+"),limit=3) }
                val pids=rows.filter { it.size==3 && it[1].toIntOrNull()==owner && it[2]==expected }.map { it[0].toInt() }.toList()
                check(pids.size==1 && pids.single()>1) { "Target account Screen Sharing agent is not uniquely identified" }
                command("/bin/kill -TERM ${pids.single()}")
                record("Stopped only target account's idle Screen Sharing agent after owned baseline teardown; no other-account process or settings changes")
                Thread.sleep(1500)
                sessionDiagnostic("agent-reset")
            }
            space()
            if(production) {
                var result: Throwable?=null
                try { productionProbe(vnc,ssh,::command,guiAudio,interactive,guiRouting,stability,autoUnlock) } catch(e:Exception) { result=e; record("PRODUCTION PROBE FAILED ${e.message}") }
                lastAttempt.writeText(System.currentTimeMillis().toString())
                check(command("printf farside_ssh_still_alive")=="farside_ssh_still_alive")
                record("PASS retained SSH after production probe")
                if(result is VncAuthenticationException) throw result
                baseline("after production probe")
                result?.let { throw it }
                return output.readText()
            }
            val texture = SurfaceTexture(0)
            val displayed = if(preferAvc) MacMediaSurface(test) else null
            val surface = displayed?.surface ?: Surface(texture)
            var pixels=false
            var probeFailure: Throwable? = null
            var frames = 0L
            var packets = 0L
            var selectionRejected = false
            var relay: MacHevcSshRelay? = null
            val parameterSets = mutableSetOf<Int>()
            var keyframeSaved = false
            var audioFrames = 0L
            var audioPlayed = 0L
            var audioPeak = 0L
            var audioUnits = 0
            val hevcParameters=mutableMapOf<Long,MutableMap<Int,ByteArray>>()
            var hevcCaptures=0;var hevcCaptureBytes=0L
            try {
                if (relayMedia) relay = MacHevcSshRelay(test.targetContext, client, vnc.host, ::record, vnc.port)
                MacHevcSession(if (relayMedia) "127.0.0.1" else vnc.host, relay?.port ?: vnc.port, vc.user, vc.pass,
                    sessionUsername = sc.user, authenticationSpacingMs = 5_000,
                    mediaDatagramSender = relay?.let { owned -> { port, bytes -> owned.send(port, bytes) } },
                    audioEnabled = audioTest,
                    preferAvc = preferAvc,
                    encodedVideoObserver=if(hevcCapture) ({ source:Long,nal:ByteArray ->
                        if(nal.size>5 && (source in hevcParameters || hevcParameters.size<8)) {
                            val type=(nal[4].toInt() and 0x7e) ushr 1
                            val params=hevcParameters.getOrPut(source) { mutableMapOf() }
                            if(type in 32..34 && nal.size<=4096) params[type]=nal.copyOf()
                            if(type in 16..21 && (32..34).all { it in params } && hevcCaptures<20) {
                                val picture=checkNotNull(params[32])+checkNotNull(params[33])+checkNotNull(params[34])+nal
                                if(hevcCaptureBytes+picture.size<=32*1024*1024) {
                                    File(test.targetContext.cacheDir,"mac-flicker-hevc-${source.toString(16)}-${hevcCaptures++}.hevc").writeBytes(picture)
                                    hevcCaptureBytes+=picture.size
                                }
                            }
                        }
                    }) else null,
                    preferSrpAuthentication=srp,
                    sessionLoginUsername=if(fullSessionAlias) vc.user else sc.user,
                    selectVirtualDisplay=selectDisplay,
                    selectVirtualDisplayByIndex=selectIndex,
                    audioObserver = { bytes ->
                        if(audioUnits++ < 100) File(test.targetContext.cacheDir,"mac-audio-${audioUnits.toString().padStart(3,'0')}.bin").writeBytes(bytes)
                    },
                    parameterSetObserver = { type, bytes ->
                        if (type in 16..21 && !keyframeSaved) {
                            File(test.targetContext.cacheDir, "mac-hevc-keyframe.bin").writeBytes(bytes)
                            keyframeSaved = true
                            record("Bounded HEVC keyframe type=$type bytes=${bytes.size} saved privately for offline replay")
                        } else if (type !in 16..21 && parameterSets.add(type)) {
                            File(test.targetContext.cacheDir, "mac-hevc-parameter-$type.bin").writeBytes(bytes)
                            record("HEVC parameter set type=$type bytes=${bytes.size} saved privately")
                        }
                    },
                    logCallback = { line ->
                        if (line.contains("Received SessionSelect reply") && !line.contains("status=0)")) selectionRejected = true
                        // Suppress active-user and credential identifiers in durable evidence.
                        record(line.replace(Regex("(activeUser|user)='[^']*'"), "$1='<redacted>'"))
                    }).use { session ->
                    session.controlUpdatesEnabled=!noControlPoll
                    session.setSurface(surface)
                    val first = session.start()
                    check(session.stage == "S8" && first.isNotEmpty())
                    record("PASS S8 encrypted control records; not a video success")
                    session.sessionSelectReply?.let { File(test.targetContext.cacheDir,"mac-session-select-reply.bin").writeBytes(it) }
                    session.cachedHandshakeRecords.forEachIndexed { index, bytes ->
                        File(test.targetContext.cacheDir, "mac-hevc-record-$index.bin").writeBytes(bytes)
                    }
                    diagnostic("udp", "/usr/sbin/lsof -nP -a -u \"\$(id -un)\" -iUDP 2>&1 || true")
                    session.initStreamingLayer(first)
                    check(session.stage == "S9")
                    session.startStreaming(surface)
                    if(selectDisplay) {
                        val until=SystemClock.uptimeMillis()+5000
                        while(session.selectedVirtualDisplay==null && SystemClock.uptimeMillis()<until) Thread.sleep(50)
                        check(session.selectedVirtualDisplay!=null) { "No unambiguous negotiated virtual display to select" }
                        Thread.sleep(3000)
                        record("Selection requested for the sole negotiated virtual display; capture begins after keyframe/settle window, server acceptance still requires image verification")
                    }
                    sessionDiagnostic("streaming")
                    if(autoUnlock) directTestUnlock(session,checkNotNull(displayed),vc.pass)
                    val srpCapture=if(srp) File(test.targetContext.cacheDir,"mac-srp-avc-input.h264") else null
                    srpCapture?.writeBytes(session.videoPipeline?.avcConfiguration ?: byteArrayOf())
                    var srpUnits=0
                    if(srp) session.videoPipeline?.avcAccessUnitObserver={ bytes ->
                        if(srpUnits++<400 && checkNotNull(srpCapture).length()+bytes.size<=32*1024*1024) srpCapture.appendBytes(bytes)
                    }
                    if(audioTest) playSource(::command)
                    if(autoUnlock) {
                        val confirmed=File(test.targetContext.cacheDir,"mac-audio-play-confirmed").also { it.delete() }
                        command("/usr/bin/open -a '/System/Applications/QuickTime Player.app' /System/Library/Sounds/Glass.aiff")
                        Thread.sleep(1500)
                        record("DIRECT_GUI_PLAYER_READY: visually confirm the owned QuickTime Glass window before creating mac-audio-play-confirmed")
                        val limit=SystemClock.uptimeMillis()+60_000
                        while(!confirmed.exists() && SystemClock.uptimeMillis()<limit) { displayed!!.capture("mac-audio-player.png");Thread.sleep(500) }
                        check(confirmed.exists()) { "No confirmed owned GUI audio source; no playback keys sent" }
                        val originalMute=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
                        check(originalMute in listOf("true","false"))
                        val restore=if(originalMute=="true") "set volume with output muted" else "set volume without output muted"
                        val guard=java.util.concurrent.CompletableFuture.runAsync {
                            command("set -e; restore_audio() { /usr/bin/osascript -e '$restore' >/dev/null 2>&1; }; trap restore_audio EXIT HUP INT TERM; /usr/bin/osascript -e 'set volume without output muted'; /bin/sleep 12")
                        }
                        try {
                            Thread.sleep(700)
                            session.sendKeyEvent(32,true);session.sendKeyEvent(32,false)
                            Thread.sleep(5000)
                            displayed!!.capture("mac-audio-player-after.png")
                            session.sendKeyEvent(0xffeb,true);session.sendKeyEvent('w'.code,true);session.sendKeyEvent('w'.code,false);session.sendKeyEvent(0xffeb,false)
                        } finally { guard.get(20,TimeUnit.SECONDS) }
                        check(command("/usr/bin/osascript -e 'output muted of (get volume settings)'")==originalMute)
                        record("PASS exact original mute restored after the visually confirmed direct GUI source")
                    }
                    val deadline = SystemClock.uptimeMillis() + 20_000
                    var snapshots=0
                    while (SystemClock.uptimeMillis() < deadline) {
                        Thread.sleep(1000)
                        if((srp || flicker) && displayed!=null && snapshots<12) displayed.capture("mac-srp-stability-${snapshots++}.png")
                    }
                    frames = session.videoPipeline?.framesDecoded?.get() ?: 0
                    if(preferAvc) { pixels=displayed!!.capture("mac-avc-live.png"); record("ACTUAL AVC TEXTURE nonblank=$pixels decoder=${session.videoPipeline?.decoderDescription}") }
                    packets = session.videoPipeline?.packetsReceived?.get() ?: 0
                    session.videoPipeline?.audio?.let { audio ->
                        audioFrames=audio.decodedFrames.get(); audioPlayed=audio.playedFrames; audioPeak=audio.peak.get()
                        record("AUDIO RESULT authenticated=${audio.authenticated.get()} decoded=$audioFrames written=${audio.writtenFrames.get()} played=$audioPlayed peak=$audioPeak dropped=${audio.dropped.get()} error=${audio.error}")
                    }
                    record("MEDIA RESULT decodedFrames=$frames udpDatagrams=$packets authenticated=${session.videoPipeline?.authenticatedPackets?.get()} nals=${session.videoPipeline?.nalUnits?.get()}")
                    if(hevcCapture) record("HEVC CAPTURE ONLY: $hevcCaptures source-separated random-access pictures, $hevcCaptureBytes bytes; hardware output not expected/claimed")
                    if(srp && preferAvc) record("SRP AVC ASSEMBLY ${session.videoPipeline?.avcAssemblyDiagnostics}; no password keystrokes sent; inspect $snapshots hardware captures for unlock/stability")
                    diagnostic("logs", "/usr/bin/log show --last 2m --style compact --predicate 'process == \"screensharingd\" OR process == \"ScreensharingAgent\"' 2>&1 | /usr/bin/tail -n 180")
                }
            } catch (e: Exception) {
                probeFailure = e
                record("PROBE FAILED ${e.javaClass.simpleName}: ${e.message}")
            } finally { runCatching { relay?.close() }; if(displayed!=null) displayed.close() else surface.release(); texture.release() }
            sessionDiagnostic("after-media")
            // Preserve a long quiet period after the last possible protocol-level authentication.
            lastAttempt.writeText(System.currentTimeMillis().toString())
            check(command("printf farside_ssh_still_alive") == "farside_ssh_still_alive")
            record("PASS retained SSH transport after probe")
            if (probeFailure is VncAuthenticationException || selectionRejected) {
                error("Authentication rejection: stop new authentication attempts; retained SSH works")
            }
            if(probeFailure is TestInputGateClosed && probeFailure.credentialSubmitted)
                error("Test unlock outcome unconfirmed: stop new authentication attempts; retained SSH works")
            baseline("after probe")
            record("BASELINES PRESERVED. HEVC decodedFrames=$frames; udpDatagrams=$packets")
            probeFailure?.let { throw AssertionError("HEVC path failed; surrounding baselines passed", it) }
            if(audioTest) check(audioFrames>0 && audioPlayed>0 && audioPeak>0) { "No non-silent decoded/played Apple audio; inspect AUDIO RESULT" }
            if(hevcCapture) check(hevcCaptures>0) { "No complete HEVC random-access captures" }
            if(!hevcCapture && (!audioTest || preferAvc)) check(frames > 0) { "No decoded video frames; control/UDP activity is insufficient" }
            if(preferAvc) check(pixels) { "Hardware output lacks verified nonblank desktop pixels" }
            return output.readText()
        } finally { runCatching { awakeCommand?.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM) }; runCatching { awakeSession?.close() }; lease.close() }
    }

    private fun playSource(command:(String)->String) {
        val devices=command("/usr/bin/say -a '?' 2>&1 || true")
        File(test.targetContext.cacheDir,"mac-audio-devices.txt").writeText(devices)
        val inventory=command("/usr/sbin/system_profiler SPAudioDataType -json 2>&1; /usr/bin/osascript -e 'get volume settings' 2>&1")
        File(test.targetContext.cacheDir,"mac-audio-inventory.txt").writeText(inventory)
        val muted=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
        check(muted in listOf("true","false")) { "Cannot preserve Mac mute setting" }
        val restore=if(muted=="true") "set volume with output muted" else "set volume without output muted"
        record("Audio inventory saved privately; bounded speech test with temporary unmute, originalMuted=$muted")
        command("set -e; restore_audio() { /usr/bin/osascript -e '$restore' >/dev/null 2>&1; }; trap restore_audio EXIT HUP INT TERM; /usr/bin/osascript -e 'set volume without output muted'; /usr/bin/say -r 180 'Farside audio test. One two three. One two three.'; /usr/bin/afplay /System/Library/Sounds/Glass.aiff")
        check(command("/usr/bin/osascript -e 'output muted of (get volume settings)'")==muted) { "Mac mute state was not restored" }
        record("PASS Mac mute state restored after test source")
    }

    private fun directTestUnlock(session:MacHevcSession,displayed:MacMediaSurface,password:String) {
        val confirm=File(test.targetContext.cacheDir,"mac-auto-unlock-confirmed").also { it.delete() }
        val desktop=File(test.targetContext.cacheDir,"mac-auto-desktop-confirmed").also { it.delete() }
        record("DIRECT_AUTO_UNLOCK_READY: operator must confirm exact saved account and stable password field before writing type; one test-only attempt, no retry")
        val before=SystemClock.uptimeMillis()+60_000
        while(!confirm.exists() && SystemClock.uptimeMillis()<before) { displayed.capture("mac-auto-unlock.png");Thread.sleep(500) }
        if(!confirm.exists() || confirm.readText().trim()!="type") throw TestInputGateClosed(false)
        space()
        // Operator-confirmed password field for this test host; never used in normal connections.
        val x=session.width/2;val y=(session.height*0.91).toInt()
        session.sendPointer(x,y,0);Thread.sleep(100)
        session.sendPointer(x,y,1);Thread.sleep(100);session.sendPointer(x,y,0);Thread.sleep(100)
        password.codePoints().forEach { code ->
            val key=if(code<=255) code else 0x01000000 or code
            session.sendKeyEvent(key,true);session.sendKeyEvent(key,false);Thread.sleep(40)
        }
        session.sendKeyEvent(0xff0d,true);session.sendKeyEvent(0xff0d,false)
        record("Single authorized test unlock submitted without credential/key logging; no retry")
        Thread.sleep(5000)
        val after=SystemClock.uptimeMillis()+60_000
        while(!desktop.exists() && SystemClock.uptimeMillis()<after) { displayed.capture("mac-auto-desktop.png");Thread.sleep(500) }
        if(!desktop.exists()) throw TestInputGateClosed(true)
        record("PASS operator visually confirmed unlocked direct SRP desktop; this was test-only typing, not native handoff")
    }

    private fun productionProbe(vnc:ConnectionProfile, ssh:ConnectionProfile, command:(String)->String, guiAudio:Boolean, interactive:Boolean, guiRouting:Boolean, stability:Boolean, autoUnlock:Boolean) {
        val profile=vnc.copy(expMacHevc=true,macPreferAvc=true,macAudioEnabled=true,macSshRelayProfileId=ssh.id,reconnectAutomatically=false)
        val displayed=MacMediaSurface(test,if(interactive) profile else null)
        val ready=java.util.concurrent.CountDownLatch(1)
        var handle=0L
        var failure:String?=null
        val bridge=if(interactive) {
            if(!displayed.awaitConnection()) { displayed.close();throw VncAuthenticationException() }
            handle=displayed.handle
            ready.countDown()
            checkNotNull(displayed.bridge)
        } else VncBridge()
        val connection=if(interactive) null else VncSessionConnection(test.targetContext,profile,bridge,
            onDisconnected={ text,_ -> failure=text;ready.countDown() },onStatus={ record("Production connection status updated") },
            onConnected={ handle=it;ready.countDown() })
        var privateRtpCapture:java.io.Closeable?=null
        try {
            connection?.attach(displayed.surface)
            if(!ready.await(180,TimeUnit.SECONDS) || handle==0L) {
                record("Production connection failed: $failure; stop further authentication")
                throw VncAuthenticationException()
            }
            val audio=checkNotNull(bridge.macMediaPipeline?.audio)
            val capturePipeline=checkNotNull(bridge.macMediaPipeline)
            // Private bounded authenticated RTP capture, including packets rejected by assembly.
            // Format: payload size u32, sequence u16, timestamp u32, marker u8, SSRC u32, payload.
            val rtpCapture=java.io.DataOutputStream(java.io.BufferedOutputStream(
                File(test.targetContext.cacheDir,"mac-avc-rtp.bin").outputStream()))
            privateRtpCapture=rtpCapture
            var rtpBytes=0L
            capturePipeline.avcPacketObserver={ bytes,seq,ts,marker,ssrc ->
                if(rtpBytes+bytes.size+15<=64*1024*1024) {
                    rtpCapture.writeInt(bytes.size);rtpCapture.writeShort(seq);rtpCapture.writeInt(ts.toInt())
                    rtpCapture.writeBoolean(marker);rtpCapture.writeInt(ssrc.toInt());rtpCapture.write(bytes)
                    rtpBytes+=bytes.size+15
                }
            }
            val avcCapture=File(test.targetContext.cacheDir,"mac-avc-decoder-input.h264")
            avcCapture.writeBytes(capturePipeline.avcConfiguration ?: byteArrayOf())
            var videoCaptures=0
            capturePipeline.avcAccessUnitObserver={ bytes ->
                if(videoCaptures++<400 && avcCapture.length()+bytes.size<=16*1024*1024) avcCapture.appendBytes(bytes)
            }
            var captured=0
            audio.accessUnitObserver={ bytes ->
                if(bytes.size>8 && captured<30) File(test.targetContext.cacheDir,"mac-audio-active-${captured++}.bin").writeBytes(bytes)
            }
            if(autoUnlock) {
                val confirm=File(test.targetContext.cacheDir,"mac-auto-unlock-confirmed").also { it.delete() }
                val desktop=File(test.targetContext.cacheDir,"mac-auto-desktop-confirmed").also { it.delete() }
                record("AUTO_UNLOCK_READY: visually confirm the exact saved account and stable password field, then write type or skip to mac-auto-unlock-confirmed; one attempt only")
                val before=SystemClock.uptimeMillis()+60_000
                while(!confirm.exists() && SystemClock.uptimeMillis()<before) { displayed.capture("mac-auto-unlock.png");Thread.sleep(500) }
                if(!confirm.exists()) throw VncAuthenticationException()
                when(confirm.readText().trim()) {
                    "skip" -> record("Already-unlocked GUI visually confirmed; no password input")
                    "type" -> {
                        space()
                        require(vnc.pass.isNotEmpty())
                        vnc.pass.codePoints().forEach { code -> bridge.sendUnicodeKeyEvent(handle,code,true);bridge.sendUnicodeKeyEvent(handle,code,false);Thread.sleep(40) }
                        bridge.sendUnicodeKeyEvent(handle,13,true);bridge.sendUnicodeKeyEvent(handle,13,false)
                        record("Single user-authorized test-only unlock submitted; no credential/key logging; no retry")
                        Thread.sleep(5000)
                        val after=SystemClock.uptimeMillis()+60_000
                        while(!desktop.exists() && SystemClock.uptimeMillis()<after) { displayed.capture("mac-auto-desktop.png");Thread.sleep(500) }
                        if(!desktop.exists()) throw VncAuthenticationException()
                        record("PASS unlocked desktop visually confirmed after the single test-only attempt")
                    }
                    else -> throw VncAuthenticationException()
                }
            } else if(interactive && !stability) {
                val unlocked=File(test.targetContext.cacheDir,"mac-media-unlock-confirmed").also { it.delete() }
                displayed.capture("mac-media-unlock.png")
                record("INTERACTIVE_READY: unlock this displayed streaming session using normal app controls; awaiting visual operator confirmation, maximum 600s")
                val limit=SystemClock.uptimeMillis()+600_000
                while(!unlocked.exists() && SystemClock.uptimeMillis()<limit) Thread.sleep(200)
                check(unlocked.exists()) { "Streaming desktop unlock was not confirmed; no automated password input" }
            }
            if(guiRouting) guiSoundRouting(displayed,bridge,handle,command,audio)
            if(stability) {
                repeat(12) { Thread.sleep(2000);displayed.capture("mac-stability-$it.png") }
            } else playSource(command)
            if(guiAudio) {
                val confirmed=File(test.targetContext.cacheDir,"mac-audio-play-confirmed").also { it.delete() }
                command("/usr/bin/open -a '/System/Applications/QuickTime Player.app' /System/Library/Sounds/Glass.aiff")
                Thread.sleep(2000)
                displayed.capture("mac-audio-player.png")
                record("GUI_PLAYER_READY: inspect mac-audio-player.png then create private mac-audio-play-confirmed; no blind input")
                val limit=SystemClock.uptimeMillis()+60_000
                while(!confirmed.exists() && SystemClock.uptimeMillis()<limit) Thread.sleep(200)
                check(confirmed.exists()) { "GUI sound player was not visually confirmed; no key sent" }
                val originalMute=command("/usr/bin/osascript -e 'output muted of (get volume settings)'")
                check(originalMute in listOf("true","false"))
                val restore=if(originalMute=="true") "set volume with output muted" else "set volume without output muted"
                val guard=java.util.concurrent.CompletableFuture.runAsync {
                    command("set -e; restore_audio() { /usr/bin/osascript -e '$restore' >/dev/null 2>&1; }; trap restore_audio EXIT HUP INT TERM; /usr/bin/osascript -e 'set volume without output muted'; /bin/sleep 12")
                }
                try {
                    Thread.sleep(700)
                    bridge.sendUnicodeKeyEvent(handle,32,true);bridge.sendUnicodeKeyEvent(handle,32,false)
                    Thread.sleep(5000)
                    displayed.capture("mac-audio-player-after.png")
                    bridge.sendKeyEvent(handle,0x15b,true)
                    bridge.sendUnicodeKeyEvent(handle,'w'.code,true);bridge.sendUnicodeKeyEvent(handle,'w'.code,false)
                    bridge.sendKeyEvent(handle,0x15b,false)
                } finally { guard.get(20,TimeUnit.SECONDS) }
                check(command("/usr/bin/osascript -e 'output muted of (get volume settings)'")==originalMute)
                record("Bounded GUI playback attempted; closed the confirmed owned sound window")
            }
            Thread.sleep(15_000)
            val pipeline=checkNotNull(bridge.macMediaPipeline)
            val pixels=displayed.capture("mac-production-live.png")
            record("PRODUCTION VIDEO frames=${pipeline.framesDecoded.get()} pixels=$pixels decoder=${pipeline.decoderDescription}")
            record("PRODUCTION VIDEO STREAMS ${pipeline.videoStreamSummary} decoderInputDrops=${pipeline.avcInputDrops}")
            record("PRODUCTION AVC ASSEMBLY ${pipeline.avcAssemblyDiagnostics}")
            capturePipeline.avcPacketObserver=null
            record("PRODUCTION AUDIO authenticated=${audio.authenticated.get()} bytes=${audio.payloadBytes.get()} largestAU=${audio.largestAccessUnit.get()} decoded=${audio.decodedFrames.get()} played=${audio.playedFrames} peak=${audio.peak.get()} error=${audio.error}")
            bridge.setAudioMuted(handle,true,false);check(audio.muted);Thread.sleep(500)
            bridge.setAudioMuted(handle,false,false);check(!audio.muted)
            record("PASS production speaker mute/unmute state")
            check(pixels && pipeline.framesDecoded.get()>0) { "No actual production video pixels" }
            if(!stability) check(audio.error==null && audio.playedFrames>0 && audio.peak.get()>0) { "Production audio has no verified non-silent output" }
        } finally {
            connection?.close()
            displayed.close()
            val deadline=SystemClock.uptimeMillis()+10_000
            while(bridge.isMacHevc && SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
            privateRtpCapture?.close()
            check(!bridge.isMacHevc) { "Production streaming teardown did not complete" }
        }
    }

    /** Bounded operator-guided clicks on visually inspected owned sound/player windows; no typing. */
    private fun guiSoundRouting(displayed:MacMediaSurface, bridge:VncBridge, handle:Long,
        command:(String)->String, audio:MacScreenAudio) {
        val action=File(test.targetContext.cacheDir,"mac-audio-routing-action.json").also { it.delete() }
        val done=File(test.targetContext.cacheDir,"mac-audio-routing-done").also { it.delete() }
        fun sound() { command("/usr/bin/open 'x-apple.systempreferences:com.apple.Sound-Settings.extension'");Thread.sleep(1200) }
        fun click(x:Double,y:Double) {
            require(x in 0.0..1.0 && y in 0.0..1.0)
            val px=(x*bridge.getDesktopWidth(handle)).toInt();val py=(y*bridge.getDesktopHeight(handle)).toInt()
            bridge.sendPointerEvent(handle,px,py,0);Thread.sleep(80)
            bridge.sendPointerEvent(handle,px,py,1);Thread.sleep(100);bridge.sendPointerEvent(handle,px,py,0)
        }
        var undoMute:Pair<Double,Double>?=null
        var actions=0
        try {
            sound()
            record("GUI_ROUTING_READY: inspect mac-audio-routing.png; private bounded click controls only; maximum 600s")
            val limit=SystemClock.uptimeMillis()+600_000
            var nextMetrics=0L
            while(!done.exists() && SystemClock.uptimeMillis()<limit) {
                if(action.exists()) {
                    val request=org.json.JSONObject(action.readText());action.delete()
                    check(++actions<=24)
                    when(request.getString("action")) {
                        "click" -> click(request.getDouble("x"),request.getDouble("y"))
                        "mute" -> {
                            val x=request.getDouble("x");val y=request.getDouble("y")
                            click(x,y);undoMute=if(undoMute==null) x to y else null
                        }
                        "sound" -> sound()
                        "player" -> command("/usr/bin/open -a '/System/Applications/QuickTime Player.app' /System/Library/Sounds/Glass.aiff")
                        "close" -> { bridge.sendKeyEvent(handle,0x15b,true);bridge.sendUnicodeKeyEvent(handle,'w'.code,true);bridge.sendUnicodeKeyEvent(handle,'w'.code,false);bridge.sendKeyEvent(handle,0x15b,false) }
                        else -> error("Unknown sound diagnostic action")
                    }
                    Thread.sleep(500)
                }
                displayed.capture("mac-audio-routing.png")
                if(SystemClock.uptimeMillis()>=nextMetrics) {
                    record("GUI ROUTING AUDIO largestAU=${audio.largestAccessUnit.get()} peak=${audio.peak.get()}")
                    nextMetrics=SystemClock.uptimeMillis()+10_000
                }
                Thread.sleep(700)
            }
            check(done.exists()) { "GUI audio routing diagnostic expired" }
        } finally {
            undoMute?.let { (x,y) -> sound();click(x,y);Thread.sleep(600);displayed.capture("mac-audio-routing-restored.png");record("Restored owned GUI mute toggle") }
        }
    }
}
