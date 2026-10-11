package com.farside.workspace

import com.farside.workspace.data.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder.Forward
import java.io.Closeable
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** All SSH transport/channel creation stays in SSHJ. Only SOCKS5 CONNECT framing is local. */
class SshForwarding(private val status: (Map<String, String>) -> Unit) : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val active = mutableMapOf<String, Running>()
    private val statuses = mutableMapOf<String, String>()
    private var desired = emptyList<SshForward>()
    private var ssh: SSHClient? = null
    @Volatile private var closed = false

    private class Running(val rule: SshForward, val stop: () -> Unit) : Closeable {
        val resources = ConcurrentHashMap.newKeySet<Closeable>()
        @Volatile var closed = false
        @Synchronized fun own(c: Closeable) { if (closed) c.close() else resources.add(c) }
        @Synchronized override fun close() {
            closed = true
            resources.forEach { runCatching { it.close() } }
            resources.clear()
            stop()
        }
    }

    @Synchronized fun update(rules: List<SshForward>) {
        SshForward.validateList(rules)
        if (!closed) executor.execute { desired = rules; reconcile() }
    }
    @Synchronized fun attach(client: SSHClient) {
        if (!closed) executor.execute { ssh = client; reconcile() }
    }
    private fun publish() { status(statuses.toMap()) }
    private fun reconcile() {
        if (closed) return
        val client = ssh
        active.keys.toList().forEach { id ->
            if (desired.none { it.id == id && it.enabled && it == active[id]?.rule }) {
                runCatching { active.remove(id)?.close() }
            }
        }
        statuses.keys.retainAll(desired.map { it.id }.toSet())
        desired.forEach { rule ->
            if (!rule.enabled) statuses[rule.id] = "Disabled"
            else if (client == null) statuses[rule.id] = "Starts after login"
            else if (!active.containsKey(rule.id)) {
                try {
                    active[rule.id] = start(client, rule)
                    statuses[rule.id] = "Listening"
                } catch (e: Exception) { statuses[rule.id] = "Failed: ${e.message ?: "Could not open tunnel"}" }
            }
        }
        publish()
    }

    private fun start(client: SSHClient, rule: SshForward): Running {
        if (rule.kind == ForwardKind.REMOTE) {
            val forward = Forward(rule.bindHost, rule.sourcePort)
            val running = Running(rule) { if (client.isConnected) client.remotePortForwarder.cancel(forward) }
            client.remotePortForwarder.bind(forward, object : ConnectListener {
                override fun gotConnect(channel: Channel.Forwarded) {
                    running.own(channel)
                    val socket = Socket()
                    running.own(socket)
                    try {
                        socket.connect(InetSocketAddress(rule.destinationHost, rule.destinationPort), 10000)
                        if (running.closed) return
                        channel.confirm()
                        relay(running, socket, channel)
                    } finally { runCatching { socket.close() }; runCatching { channel.close() }; running.resources.remove(socket); running.resources.remove(channel) }
                }
            })
            return running
        }
        val running = Running(rule) { }
        val servers = mutableListOf<ServerSocket>()
        fun bind(host: String) {
            val socket = ServerSocket()
            try { socket.bind(InetSocketAddress(host, rule.sourcePort)); servers.add(socket); running.own(socket) }
            catch (e: Exception) { socket.close(); throw e }
        }
        try {
            if (rule.family == ForwardFamily.AUTO) {
                if (rule.allowOtherHosts) {
                    try { bind("::") } catch (_: Exception) { bind("0.0.0.0") }
                } else {
                    bind("127.0.0.1")
                    // IPv6 is optional in Auto; explicit IPv6 reports errors rather than falling back.
                    runCatching { bind("::1") }
                }
            } else bind(rule.bindHost)
        } catch (e: Exception) { running.close(); throw e }
        servers.forEach { server ->
        thread(isDaemon = true, name = "ssh-forward-listener") {
            try {
                while (!running.closed) {
                    val socket = server.accept()
                    running.own(socket)
                    thread(isDaemon = true, name = "ssh-forward-client") {
                        var channel: Channel? = null
                        var connected = false
                        try {
                            socket.soTimeout = 10000
                            val target = if (rule.kind == ForwardKind.DYNAMIC) socksTarget(socket) else Pair(rule.destinationHost, rule.destinationPort)
                            if (running.closed) return@thread
                            channel = client.newDirectConnection(target.first, target.second)
                            running.own(channel!!)
                            if (rule.kind == ForwardKind.DYNAMIC) socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                            connected = true
                            socket.soTimeout = 0
                            relay(running, socket, channel!!)
                        } catch (e: Exception) {
                            if (rule.kind == ForwardKind.DYNAMIC && !connected) runCatching { socket.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)) }
                        } finally {
                            runCatching { channel?.close() }; runCatching { socket.close() }
                            channel?.let { running.resources.remove(it) }; running.resources.remove(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                synchronized(this@SshForwarding) {
                if (!running.closed && !closed) executor.execute {
                    if (active[rule.id] === running) { active.remove(rule.id)?.close(); statuses[rule.id] = "Failed: listener stopped"; publish() }
                }
                }
            }
        }
        }
        return running
    }

    private fun socksTarget(socket: Socket): Pair<String, Int> {
        val input = DataInputStream(socket.getInputStream())
        require(input.readUnsignedByte() == 5) { "SOCKS5 required" }
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val output = socket.getOutputStream()
        if (methods.none { it.toInt() == 0 }) { output.write(byteArrayOf(5, -1)); error("SOCKS authentication unsupported") }
        output.write(byteArrayOf(5, 0))
        require(input.readUnsignedByte() == 5)
        val command = input.readUnsignedByte()
        require(input.readUnsignedByte() == 0)
        if (command != 1) { output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); error("SOCKS CONNECT only") }
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
            else -> error("Unsupported SOCKS address")
        }
        return Pair(host, input.readUnsignedShort().also { require(it > 0) })
    }
    private fun relay(running: Running, socket: Socket, channel: Channel) {
        if (running.closed) return
        val upstream = thread(isDaemon = true, name = "ssh-forward-upload") {
            try {
                val input = socket.getInputStream()
                val output = channel.outputStream
                val buffer = ByteArray(16384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
                output.close()
            }
            catch (e: Exception) { runCatching { socket.close() }; runCatching { channel.close() } }
        }
        try { channel.inputStream.copyTo(socket.getOutputStream()); socket.shutdownOutput(); upstream.join() }
        finally { runCatching { socket.close() }; runCatching { channel.close() } }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        executor.execute { active.values.forEach { runCatching { it.close() } }; active.clear() }
        executor.shutdown()
    }
}
