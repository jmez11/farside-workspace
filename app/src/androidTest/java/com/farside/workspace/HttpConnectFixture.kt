package com.farside.workspace

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** One-shot HTTP CONNECT relay used to exercise the real native clients on Quest. */
internal class HttpConnectFixture(private val targetPort: Int? = null,
                                  private val targetHost: String = "127.0.0.1") : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    @Volatile var request: String? = null
        private set
    @Volatile var failure: Throwable? = null
        private set
    private var client: Socket? = null
    private var upstream: Socket? = null
    private val worker = thread(isDaemon = true) {
        try {
            listener.accept().use { downstream ->
                client = downstream
                downstream.soTimeout = 5000
                val data = java.io.ByteArrayOutputStream()
                while (data.size() < 8192 && !data.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                    val value = downstream.getInputStream().read()
                    if (value < 0) error("CONNECT request closed")
                    data.write(value)
                }
                request = data.toString("ISO-8859-1")
                if (targetPort == null) {
                    downstream.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                } else {
                    Socket(targetHost, targetPort).use { target ->
                        upstream = target
                        downstream.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        downstream.getOutputStream().flush()
                        downstream.soTimeout = 0
                        thread(isDaemon = true) { runCatching { downstream.getInputStream().copyTo(target.getOutputStream()) } }
                        runCatching { target.getInputStream().copyTo(downstream.getOutputStream()) }
                    }
                }
            }
        } catch (error: Throwable) { if (!listener.isClosed) failure = error }
    }
    override fun close() { runCatching { client?.close() }; runCatching { upstream?.close() }; listener.close(); worker.join(1000) }
}
