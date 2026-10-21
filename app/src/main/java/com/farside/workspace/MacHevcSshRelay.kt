package com.farside.workspace

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

/** Session-owned in-memory relay. No installation or remote files. */
internal class MacHevcSshRelay(context: Context, client: SSHClient, host: String,
    private val log: (String) -> Unit, targetPort: Int = 5900) : Closeable {
    private val channel = client.startSession()
    private val script = context.assets.open("mac-hevc-relay.pl").bufferedReader().use { it.readText() }
    private fun quote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
    private val command = channel.exec("exec /usr/bin/perl -e ${quote(script)} ${quote(host)} ${targetPort.also { require(it in 1..65535) }}")
    private val input = DataInputStream(command.inputStream)
    private val output = DataOutputStream(command.outputStream)
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val listener = ServerSocket(0, 8, loopback)
    val port = listener.localPort
    val videoDatagrams = AtomicLong()
    private val ready = input.readLine() ?: error("Relay startup failed: ${command.errorStream.bufferedReader().readText()}")
    private val remotePort = ready.removePrefix("READY ").toInt()
    private val forwarder = client.newLocalPortForwarder(Parameters("127.0.0.1", port, "127.0.0.1", remotePort), listener)
    private val forwardThread = Thread({ runCatching { forwarder.listen() } }, "MacRelayControl").apply { isDaemon=true; start() }
    private val receiver = Thread({
        DatagramSocket().use { socket ->
            try {
                while (true) {
                    val target = input.readUnsignedShort()
                    val length = input.readUnsignedShort()
                    check(target == 5900 || target == 5901)
                    val bytes = ByteArray(length).also { input.readFully(it) }
                    if (target == 5901) videoDatagrams.incrementAndGet()
                    socket.send(DatagramPacket(bytes, bytes.size, loopback, target))
                }
            } catch (e: Exception) { log("SSH media relay reader ended: ${e.javaClass.simpleName}") }
        }
    }, "MacRelayMedia").apply { isDaemon=true; start() }
    init { log("Owned SSH relay ready; remote UDP bound only to loopback; 600s idle expiry") }
    fun send(port: Int, bytes: ByteArray) = synchronized(output) {
        output.writeShort(port); output.writeShort(bytes.size); output.write(bytes); output.flush()
    }
    override fun close() {
        runCatching { synchronized(output) { output.writeInt(0); output.flush() } }
        runCatching { forwarder.close() }
        runCatching { listener.close() }
        runCatching { channel.close() }
        receiver.join(1000); forwardThread.join(1000)
        log("Owned SSH relay closed; relayed video datagrams=${videoDatagrams.get()}")
    }
}
