package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.*
import com.farside.workspace.ssh.SshConnectionManager
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owned temporary UDP echo endpoint; isolates the saved overlay route from codecs/RTP. */
internal class MacZerotierUdpProbe(private val test:Instrumentation) {
    fun run(id:String):String {
        val pm=ProfileManager(test.targetContext)
        val profile=pm.getProfiles().single {it.id==id && it.proxyType==ProxyType.NONE}
        val ssh=pm.getProfiles().single {it.id==test.arguments?.getString("macPortSshProfile")}
        val script="""
use strict;use warnings;use IO::Socket::INET;use IO::Select;use Socket qw(SOL_SOCKET SO_RCVBUF sockaddr_in inet_aton);${'$'}|=1;alarm 45;
my ${'$'}s=IO::Socket::INET->new(LocalAddr=>'0.0.0.0',LocalPort=>0,Proto=>'udp') or die 'socket';
bind(${'$'}s,sockaddr_in(0,inet_aton('0.0.0.0'))) or die 'bind' if ${'$'}s->sockport==0;
setsockopt(${'$'}s,SOL_SOCKET,SO_RCVBUF,pack('i',4194304));
print 'READY ',${'$'}s->sockport,"\n";my ${'$'}select=IO::Select->new(${'$'}s,\*STDIN);my %seen;
while(1){my @ready=${'$'}select->can_read(1);for my ${'$'}f(@ready){if(fileno(${'$'}f)==fileno(STDIN)){my ${'$'}n=sysread(STDIN,my ${'$'}q,1);goto done;}
else{my ${'$'}from=recv(${'$'}s,my ${'$'}data,8192,0);next unless defined ${'$'}from && length(${'$'}data)>=8;
my(${'$'}block,${'$'}seq)=unpack('NN',${'$'}data);${'$'}seen{${'$'}block}{${'$'}seq}=1;send(${'$'}s,${'$'}data,0,${'$'}from);}}}
done: print STDERR 'PROBE_RX ',join(',',map {${'$'}_.':'.scalar(keys %{${'$'}seen{${'$'}_}})} sort {${'$'}a<=>${'$'}b} keys %seen),"\n";
""".trimIndent()
        val quoted="'"+script.replace("'","'\"'\"'")+"'"
        val report=StringBuilder()
        SshConnectionManager.acquire(test.targetContext,ssh.id).use {lease ->
            val client=lease.connect(ssh.copy(reconnectAutomatically=false))
            client.startSession().use {channel ->
                val remote=channel.exec("exec /usr/bin/perl -e $quoted")
                val port=MacRelayStartup.port({remote.inputStream.bufferedReader().readLine()},{channel.close()})
                val target=InetAddress.getByName(profile.host)
                DatagramSocket().use {socket ->
                    socket.receiveBufferSize=4*1024*1024;socket.soTimeout=100
                    val running=AtomicBoolean(true)
                    val received=ConcurrentHashMap<Int,MutableSet<Int>>()
                    val reader=Thread {
                        val bytes=ByteArray(8192)
                        while(running.get()) try {
                            val packet=DatagramPacket(bytes,bytes.size);socket.receive(packet)
                            if(packet.address==target && packet.port==port && packet.length>=8) {
                                val b=ByteBuffer.wrap(bytes,0,packet.length)
                                received.computeIfAbsent(b.int) {ConcurrentHashMap.newKeySet<Int>()}.add(b.int)
                            }
                        } catch(_:SocketTimeoutException) {} catch(_:SocketException) {break}
                    }.apply {start()}
                    try {
                        val cases=listOf(Triple(64,20,50L),Triple(1400,20,50L),Triple(900,300,0L),Triple(1400,300,0L),Triple(1400,300,5L))
                        cases.forEachIndexed {i,(size,count,spacing) ->
                            repeat(count) {seq ->
                                val bytes=ByteBuffer.allocate(size).putInt(i).putInt(seq).array()
                                socket.send(DatagramPacket(bytes,bytes.size,target,port));if(spacing>0) Thread.sleep(spacing)
                            }
                            Thread.sleep(2000)
                            report.appendLine("block=$i size=$size sent=$count spacingMs=$spacing echoed=${received[i]?.size ?: 0} receiveBuffer=${socket.receiveBufferSize}")
                        }
                    } finally {running.set(false);socket.close();reader.join(1000)}
                }
                remote.outputStream.write(0);remote.outputStream.flush();remote.join(5,TimeUnit.SECONDS)
                report.appendLine(remote.errorStream.bufferedReader().readText())
                check(remote.exitStatus==0)
            }
        }
        File(test.targetContext.cacheDir,"mac-zerotier-udp-probe.txt").writeText(report.toString())
        return "PASS owned probe closed; delivery results:\n$report"
    }
}
