package com.farside.workspace.tunnel

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Stable loopback origin; closes existing clients whenever ADB changes or is disabled. */
class AdbLoopbackRelay(port: Int) : AutoCloseable {
    private val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
    private val target = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newFixedThreadPool(16)
    private val clients = java.util.concurrent.Semaphore(8)
    init {
        Thread({
            while (!closed.get()) {
                val incoming = runCatching { server.accept() }.getOrNull() ?: break
                if (!clients.tryAcquire()) { incoming.close(); continue }
                val remote = Socket()
                val accepted = synchronized(target) {
                    if (closed.get() || target.get() == 0) false
                    else { sockets.add(incoming); sockets.add(remote); true }
                }
                if (!accepted) { incoming.close(); remote.close(); clients.release(); continue }
                runCatching { workers.execute {
                    try {
                        val candidate = target.get()
                        if (candidate == 0) return@execute
                        remote.connect(InetSocketAddress("127.0.0.1", candidate), 1500)
                        workers.execute {
                            runCatching { remote.getInputStream().copyTo(incoming.getOutputStream()) }
                            runCatching { incoming.close() }; runCatching { remote.close() }
                        }
                        incoming.getInputStream().copyTo(remote.getOutputStream())
                    } catch (_: Exception) { }
                    finally {
                        runCatching { incoming.close() }; runCatching { remote.close() }
                        sockets.remove(incoming); sockets.remove(remote); clients.release()
                    }
                } }.onFailure {
                    incoming.close(); remote.close()
                    sockets.remove(incoming); sockets.remove(remote); clients.release()
                }
            }
        }, "adb-loopback-relay").apply { isDaemon = true; start() }
    }
    fun update(port: Int?) = synchronized(target) {
        val next = port ?: 0
        require(next in 0..65535 && next != server.localPort)
        if (target.getAndSet(next) != next) sockets.forEach { runCatching { it.close() } }
    }
    override fun close() = synchronized(target) {
        closed.set(true); target.set(0); server.close()
        sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
        Unit
    }
}
