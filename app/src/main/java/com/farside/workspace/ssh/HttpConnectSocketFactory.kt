package com.farside.workspace.ssh

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.SocketFactory

/** Returns the real connected socket so native clients can detach its file descriptor. */
internal fun openHttpConnectSocket(targetHost: String, targetPort: Int, proxyHost: String,
                                   proxyPort: Int, proxyUser: String?, proxyPassword: String?): Socket {
    val socket = Socket()
    establishHttpConnect(socket, InetSocketAddress.createUnresolved(targetHost, targetPort),
        proxyHost, proxyPort, proxyUser, proxyPassword, 10_000)
    return socket
}

private fun establishHttpConnect(transport: Socket, endpoint: InetSocketAddress,
                                 proxyHost: String, proxyPort: Int, proxyUser: String?,
                                 proxyPassword: String?, timeout: Int) {
    val host = endpoint.hostString
    if (host.any { it == '\r' || it == '\n' } || host.isBlank() || endpoint.port !in 1..65535)
        throw IOException("Invalid HTTP proxy target")
    val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]:${endpoint.port}" else "$host:${endpoint.port}"
    try {
        transport.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
        transport.soTimeout = 10_000
        val auth = if (!proxyUser.isNullOrEmpty()) {
            val credentials = "$proxyUser:${proxyPassword.orEmpty()}"
            "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))}\r\n"
        } else ""
        transport.getOutputStream().write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n$auth\r\n"
            .toByteArray(StandardCharsets.ISO_8859_1))
        transport.getOutputStream().flush()
        val input = transport.getInputStream()
        val header = java.io.ByteArrayOutputStream()
        while (header.size() < 8192) {
            val next = input.read()
            if (next < 0) throw IOException("HTTP proxy closed before CONNECT response")
            header.write(next)
            val bytes = header.toByteArray()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == 13.toByte() && bytes[n - 3] == 10.toByte() &&
                bytes[n - 2] == 13.toByte() && bytes[n - 1] == 10.toByte()) break
        }
        val response = header.toString(StandardCharsets.ISO_8859_1.name())
        val status = response.lineSequence().firstOrNull().orEmpty()
        if (!status.matches(Regex("HTTP/1\\.[01] 2[0-9][0-9]( .*)?")))
            throw IOException(if (status.startsWith("HTTP/")) "HTTP proxy CONNECT failed: ${status.take(100)}" else "Invalid HTTP proxy response")
        if (!response.endsWith("\r\n\r\n")) throw IOException("HTTP proxy response headers too large")
        transport.soTimeout = 0
    } catch (error: Exception) {
        transport.close()
        throw error
    }
}

/** Supplies SSHJ with a socket whose TCP stream is established through HTTP CONNECT. */
internal class HttpConnectSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val proxyUser: String?,
    private val proxyPassword: String?
) : SocketFactory() {
    override fun createSocket(): Socket = HttpConnectSocket(proxyHost, proxyPort, proxyUser, proxyPassword)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = createSocket(host.hostAddress ?: host.hostName, port)
    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress ?: host.hostName, port, localHost, localPort)
}

private class HttpConnectSocket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val proxyUser: String?,
    private val proxyPassword: String?
) : Socket() {
    private val transport = Socket()

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("Invalid HTTP proxy target")
        establishHttpConnect(transport, target, proxyHost, proxyPort, proxyUser, proxyPassword, timeout)
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun bind(bindpoint: SocketAddress?) = transport.bind(bindpoint)
    override fun getInputStream() = transport.getInputStream()
    override fun getOutputStream() = transport.getOutputStream()
    override fun close() = transport.close()
    override fun isConnected() = transport.isConnected
    override fun isClosed() = transport.isClosed
    override fun getInetAddress() = transport.inetAddress
    override fun getPort() = transport.port
    override fun getLocalAddress() = transport.localAddress
    override fun getLocalPort() = transport.localPort
    override fun setSoTimeout(timeout: Int) { transport.soTimeout = timeout }
    override fun getSoTimeout() = transport.soTimeout
}
