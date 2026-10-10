package com.farside.workspace.tunnel

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import java.net.NetworkInterface
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class AdbState(val developer: Boolean, val wireless: Boolean, val port: Int? = null)

/** Uses ordinary app permissions; only accepts mDNS endpoints belonging to this headset. */
object AdbDiscovery {
    fun flags(context: Context): AdbState {
        fun enabled(key: String) = runCatching { Settings.Global.getInt(context.contentResolver, key, 0) == 1 }.getOrDefault(false)
        return AdbState(enabled(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), enabled("adb_wifi_enabled"))
    }

    @Synchronized
    fun discover(context: Context, timeoutMs: Long = 5000): AdbState {
        val flags = flags(context)
        if (!flags.developer || !flags.wireless) return flags
        val local = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val result = AtomicReference<Int?>()
        val complete = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val probes = java.util.concurrent.Executors.newSingleThreadExecutor()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { complete.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            // Discovery is a snapshot: teardown emits lost callbacks too. Only the
            // loopback probe decides whether an advertised endpoint is usable.
            override fun onServiceLost(info: NsdServiceInfo) {}
            private val pending = java.util.ArrayDeque<NsdServiceInfo>()
            private var resolving = false
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(pending) { pending.add(info) }
                resolveNext()
            }
            private fun resolveNext() {
                val info = synchronized(pending) {
                    if (!active.get() || resolving || pending.isEmpty()) return
                    resolving = true
                    pending.removeFirst()
                }
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) {
                        synchronized(pending) { resolving = false }
                        resolveNext()
                    }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        if (!active.get()) return
                        // Keep probes off NSD's callback thread and keep resolution
                        // serialized until this candidate has been checked.
                        runCatching { probes.execute {
                            val usable = service.host?.hostAddress in local && service.port in 1..65535 &&
                                runCatching { Socket().use {
                                    it.connect(InetSocketAddress("127.0.0.1", service.port), 500)
                                }; true }.getOrDefault(false)
                            synchronized(pending) { resolving = false }
                            if (active.get() && usable) {
                                result.set(service.port)
                                complete.countDown()
                            } else resolveNext()
                        } }
                    }
                })
            }
        }
        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            complete.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            active.set(false)
            runCatching { nsd.stopServiceDiscovery(listener) }
            probes.shutdownNow()
        }
        // Recheck switches after the asynchronous discovery.
        return flags(context).let { if (it.developer && it.wireless) it.copy(port = result.get()) else it }
    }
}
