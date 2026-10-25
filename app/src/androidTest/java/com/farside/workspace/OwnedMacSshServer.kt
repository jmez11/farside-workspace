package com.farside.workspace

import android.content.Context
import com.farside.workspace.data.*
import com.farside.workspace.ssh.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Owned encrypted transport; exec simulates helper readiness, never runs commands on user hosts. */
internal class OwnedMacSshServer(private val context: Context, private val vncPort: Int) : AutoCloseable {
    private val oldUserHome=System.getProperty("user.home")
    init {
        SshConnectionManager.ensureBouncyCastleProvider()
        System.setProperty("user.home",context.cacheDir.absolutePath)
    }
    val commands=CopyOnWriteArrayList<String>()
    val authentications=AtomicInteger()
    val authenticationAttempts=AtomicInteger()
    private val echo=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    val socksPort=ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).use {it.localPort}
    private val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
    private val server=SshServer.setUpDefaultServer().apply {
        host="127.0.0.1";port=0
        userAuthFactories=listOf(org.apache.sshd.server.auth.password.UserAuthPasswordFactory.INSTANCE)
        keyPairProvider=org.apache.sshd.common.keyprovider.KeyPairProvider {listOf(pair)}
        passwordAuthenticator=org.apache.sshd.server.auth.password.PasswordAuthenticator {user,pass,_ ->
            authenticationAttempts.incrementAndGet()
            (user=="fixture" && pass=="fixture").also {if(it) authentications.incrementAndGet()}
        }
        forwardingFilter=AcceptAllForwardingFilter.INSTANCE
        commandFactory=org.apache.sshd.server.command.CommandFactory {_,command ->
            object:org.apache.sshd.server.command.Command {
                lateinit var input:java.io.InputStream
                lateinit var output:java.io.OutputStream
                lateinit var callback:org.apache.sshd.server.ExitCallback
                override fun setInputStream(stream:java.io.InputStream) {input=stream}
                override fun setOutputStream(stream:java.io.OutputStream) {output=stream}
                override fun setErrorStream(stream:java.io.OutputStream) {}
                override fun setExitCallback(exit:org.apache.sshd.server.ExitCallback) {callback=exit}
                override fun start(channel:org.apache.sshd.server.channel.ChannelSession,environment:org.apache.sshd.server.Environment) {
                    thread(isDaemon=true) {
                        try {
                            when {
                                command=="/usr/bin/id -un && /usr/bin/id -F" -> {output.write("fixture\nFixture\n".toByteArray());output.flush()}
                                command.endsWith(" local media") -> {
                                    commands+="media";output.write("UNAVAILABLE\n".toByteArray());output.flush()
                                }
                                command.endsWith(" local control") -> {
                                    check(command.endsWith(" $vncPort local control"))
                                    val relay=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
                                    val connections=CopyOnWriteArrayList<java.net.Socket>()
                                    thread(isDaemon=true) {
                                        runCatching {
                                            val local=relay.accept();connections+=local
                                            val remote=java.net.Socket("127.0.0.1",vncPort);connections+=remote
                                            thread(isDaemon=true) {runCatching {remote.getInputStream().copyTo(local.getOutputStream());local.shutdownOutput()}}
                                            local.getInputStream().copyTo(remote.getOutputStream());remote.shutdownOutput()
                                        }
                                    }
                                    commands+="control";output.write("READY ${relay.localPort}\n".toByteArray());output.flush()
                                    try {
                                    val frames=java.io.DataInputStream(input)
                                    while(true) {
                                        val port=frames.readUnsignedShort()
                                        val size=frames.readUnsignedShort()
                                        check(port==0)
                                        if(size==0) break
                                        check(size==1)
                                        frames.readByte() // owner heartbeat
                                    }
                                    } finally {relay.close();connections.forEach {runCatching {it.close()}}}
                                }
                                else -> error("Unexpected owned helper command")
                            }
                        } finally {callback.onExit(0)}
                    }
                }
                override fun destroy(channel:org.apache.sshd.server.channel.ChannelSession) {runCatching {input.close()}}
            }
        }
    }
    val profile:ConnectionProfile
    private val store=SshHostKeyStore(context)
    init {
        server.start()
        check(store.save("127.0.0.1",server.port,SshHostKey.from(pair.public)))
        profile=ConnectionProfile(UUID.randomUUID().toString(),"Owned Mac SSH",ConnectionType.SSH,"127.0.0.1",
            server.port,"fixture","fixture",enableSftp=false,sshForwards=listOf(
                SshForward(kind=ForwardKind.DYNAMIC,sourcePort=socksPort,family=ForwardFamily.IPV4)))
        ProfileManager(context).addProfile(profile)
        thread(isDaemon=true) {
            while(!echo.isClosed) try {
                val socket=echo.accept()
                thread(isDaemon=true) {socket.use {it.getInputStream().copyTo(it.getOutputStream())}}
            } catch(_:Exception) {}
        }
    }
    fun verifyDynamic() {
        val deadline=android.os.SystemClock.uptimeMillis()+5000
        var failure:Exception?=null
        do {
            try {
                openSocks5Socket("127.0.0.1",echo.localPort,"127.0.0.1",socksPort,null,null).use {
                    it.soTimeout=2000
                    val payload="owned shared SSH dynamic forwarding".toByteArray()
                    it.getOutputStream().write(payload);it.getOutputStream().flush()
                    val response=ByteArray(payload.size);java.io.DataInputStream(it.getInputStream()).readFully(response)
                    check(response.contentEquals(payload))
                }
                return
            } catch(error:Exception) {failure=error;Thread.sleep(50)}
        } while(android.os.SystemClock.uptimeMillis()<deadline)
        throw AssertionError("Shared dynamic forward unavailable",failure)
    }
    override fun close() {
        ProfileManager(context).deleteProfile(profile.id)
        store.remove("127.0.0.1",server.port)
        echo.close();server.stop(true)
        if(oldUserHome==null) System.clearProperty("user.home") else System.setProperty("user.home",oldUserHome)
    }
}
