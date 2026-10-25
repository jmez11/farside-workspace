package com.farside.workspace

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** One-shot authenticated SOCKS5 relay; records destination without resolving it on Quest. */
internal class Socks5Fixture(private val targetPort: Int? = null,
                             private val targetHost: String = "127.0.0.1") : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    @Volatile var destination: String? = null
        private set
    @Volatile var authenticated = false
        private set
    @Volatile var failure: Throwable? = null
        private set
    private var client: Socket? = null
    private var upstream: Socket? = null
    private val worker = thread(isDaemon = true) {
        try {
            listener.accept().use { downstream ->
                client = downstream
                downstream.soTimeout = 10_000
                val input = java.io.DataInputStream(downstream.getInputStream())
                val output = downstream.getOutputStream()
                check(input.readUnsignedByte() == 5)
                val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                check(methods.contains(2.toByte()))
                output.write(byteArrayOf(5, 2)); output.flush()
                check(input.readUnsignedByte() == 1)
                val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                val password = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                authenticated = user == "proxy-fixture" && password == "secret"
                output.write(byteArrayOf(1, if (authenticated) 0 else 1)); output.flush()
                if (!authenticated) return@use
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                val address = when (input.readUnsignedByte()) {
                    1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                    4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                    3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                    else -> error("Invalid address type")
                }
                destination = "$address:${input.readUnsignedShort()}"
                if (targetPort == null) {
                    output.write(byteArrayOf(5, 5, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                } else Socket(targetHost, targetPort).use { target ->
                    upstream = target
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    downstream.soTimeout = 0
                    thread(isDaemon = true) {
                        try { downstream.getInputStream().copyTo(target.getOutputStream()) }
                        catch (_: Exception) {}
                        finally { runCatching { target.shutdownOutput() } }
                    }
                    runCatching { target.getInputStream().copyTo(output) }
                }
            }
        } catch (error: Throwable) { if (!listener.isClosed) failure = error }
    }
    override fun close() { runCatching { client?.close() }; runCatching { upstream?.close() }; listener.close(); worker.join(1000) }
}
