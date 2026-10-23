package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Owned header-only tcpdump around one actual SessionActivity; no extra media session. */
internal class MacPortVerification(private val test:Instrumentation) {
    fun run(id:String):String {
        val context=test.targetContext
        val pm=ProfileManager(context)
        val profile=pm.getProfiles().single {it.id==id && it.type==ConnectionType.VNC}
        val sshId=test.arguments?.getString("macPortSshProfile") ?: profile.sftpPairProfileId ?: profile.macSshRelayProfileId
        val ssh=pm.getProfiles().single {it.id==sshId && it.type==ConnectionType.SSH}
        require(ssh.pass.isNotEmpty()) {"Saved admin password required for owned packet headers"}
        SshConnectionManager.acquire(context,ssh.id).use {lease ->
            val client=lease.connect(ssh.copy(reconnectAutomatically=false))
            client.startSession().use {channel ->
                val rtp=test.arguments?.getString("macPortRtpHeaders")=="true"
                if(rtp) client.startSession().use {diagnostic ->
                    val script="for p in /usr/local/bin/zerotier-cli '/Library/Application Support/ZeroTier/One/zerotier-cli'; do if test -x \"\$p\"; then \"\$p\" info; \"\$p\" listpeers; \"\$p\" listnetworks; exit; fi; done; printf 'ZeroTier CLI not found in standard installed locations\\n'"
                    val quoted="'"+script.replace("'","'\"'\"'")+"'"
                    val info=diagnostic.exec("/usr/bin/sudo -S -p '' /bin/sh -c $quoted")
                    info.outputStream.write((ssh.pass+"\n").toByteArray());info.outputStream.flush()
                    File(context.cacheDir,"mac-zerotier-peer-private.txt").writeText(info.inputStream.bufferedReader().readText())
                    info.join(10,TimeUnit.SECONDS)
                }
                val remote=channel.exec("exec /usr/bin/sudo -S -p '' /usr/bin/perl -e 'alarm 60; exec @ARGV' -- /usr/sbin/tcpdump -nn -l -s 96 -c 8000 -i any ${if(rtp) "-T rtp -vv 'udp and port 5901'" else "'udp and (port 5900 or port 5901 or port 5902)'"}")
                remote.outputStream.write((ssh.pass+"\n").toByteArray());remote.outputStream.flush()
                val lines=java.util.Collections.synchronizedList(mutableListOf<String>())
                val errors=StringBuilder()
                val reader=Thread {runCatching {remote.inputStream.bufferedReader().forEachLine {if(lines.size<25000) lines.add(it)}}}.apply {start()}
                val errorReader=Thread {runCatching {remote.errorStream.bufferedReader().forEachLine {if(errors.length<10000) errors.append(it).append('\n')}}}.apply {start()}
                val outcome=try {Thread.sleep(1000);if(rtp) MacZerotierDiagnosis(test).run(id) else MacInputVerification(test).run(id)}
                finally {
                    if(remote.exitStatus==null) runCatching {remote.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM)}
                    remote.join(5,TimeUnit.SECONDS)
                    channel.close();reader.join(2000);errorReader.join(2000)
                    File(context.cacheDir,"mac-ports-headers-private.txt").writeText(lines.joinToString("\n")+"\nSTDERR:\n"+errors)
                }
                val snapshot=synchronized(lines) {lines.toList()}
                check(snapshot.isNotEmpty()) {"No packet headers captured: $errors"}
                val counts=listOf(5900,5901,5902).associateWith {port ->snapshot.count {Regex("[.:]$port(?:[ :>]|$)").containsMatchIn(it)}}
                return outcome+"\nHeader-only UDP packet counts=$counts; traffic is observed, not a declaration of each port's protocol role. Owned tcpdump closed."
            }
        }
    }
}
