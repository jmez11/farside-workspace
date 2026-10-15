package com.farside.workspace.ssh

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** SOCKS5 CONNECT with proxy-side DNS and optional RFC 1929 username/password authentication. */
internal fun openSocks5Socket(targetHost: String, targetPort: Int, proxyHost: String,
                              proxyPort: Int, user: String?, password: String?): Socket = Socket().also {
    connectSocks5(it, InetSocketAddress.createUnresolved(targetHost, targetPort), proxyHost, proxyPort, user, password, 10_000)
}

private fun connectSocks5(socket: Socket, target: InetSocketAddress, proxyHost: String,
                          proxyPort: Int, user: String?, password: String?, timeout: Int) {
    try {
        val host = target.hostString.removeSurrounding("[", "]")
        val name = host.toByteArray(Charsets.UTF_8)
        if (host.isBlank() || target.port !in 1..65535 || proxyPort !in 1..65535 || name.size > 255)
            throw IOException("Invalid SOCKS5 endpoint")
        val username = user?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val secret = password.orEmpty().toByteArray(Charsets.UTF_8)
        if (username != null && (username.size > 255 || secret.size > 255))
            throw IOException("SOCKS5 credentials exceed 255 bytes")
        socket.connect(InetSocketAddress(proxyHost, proxyPort), if (timeout > 0) timeout else 10_000)
        socket.soTimeout = 10_000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(if (username == null) byteArrayOf(5, 1, 0) else byteArrayOf(5, 2, 0, 2))
        output.flush()
        if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS5 greeting")
        when (input.readUnsignedByte()) {
            0 -> Unit
            2 -> {
                if (username == null) throw IOException("SOCKS5 authentication required")
                output.write(byteArrayOf(1, username.size.toByte()))
                output.write(username)
                output.write(secret.size)
                output.write(secret)
                output.flush()
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0)
                    throw IOException("SOCKS5 authentication failed")
            }
            else -> throw IOException("SOCKS5 proxy rejected authentication methods")
        }
        val address = when {
            host.contains(':') -> 4 to InetAddress.getByName(host).address
            host.matches(Regex("[0-9.]+")) -> 1 to InetAddress.getByName(host).address
            else -> 3 to byteArrayOf(name.size.toByte(), *name)
        }
        output.write(byteArrayOf(5, 1, 0, address.first.toByte()))
        output.write(address.second)
        output.write(byteArrayOf((target.port ushr 8).toByte(), target.port.toByte()))
        output.flush()
        if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS5 response")
        val status = input.readUnsignedByte()
        if (status != 0) throw IOException("SOCKS5 CONNECT failed (code $status)")
        if (input.readUnsignedByte() != 0) throw IOException("Invalid SOCKS5 response")
        val length = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> throw IOException("Invalid SOCKS5 address type")
        }
        input.readFully(ByteArray(length + 2))
        socket.soTimeout = 0
    } catch (error: Exception) {
        socket.close()
        throw error
    }
}

internal class Socks5SocketFactory(private val host: String, private val port: Int,
                                   private val user: String?, private val password: String?) : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket(host, port, user, password)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress ?: host.hostName, port)
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress ?: host.hostName, port, localHost, localPort)
}

private class Socks5Socket(private val host: String, private val port: Int,
                           private val user: String?, private val password: String?) : Socket() {
    private val transport = Socket()
    override fun connect(endpoint: SocketAddress?, timeout: Int) = connectSocks5(transport,
        endpoint as? InetSocketAddress ?: throw IOException("Invalid SOCKS5 target"), host, port, user, password, timeout)
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
