package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only discovery under ordinary app identity, without shell permission adoption. */
class AdbCapabilityVerification(private val instrumentation: Instrumentation) {
    fun run(): String {
        val context = instrumentation.targetContext
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        lines.add("uid=${android.os.Process.myUid()}")
        for (key in listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, "adb_wifi_enabled")) {
            lines.add("$key=" + runCatching { Settings.Global.getInt(context.contentResolver, key) }.fold({ it.toString() }, { it.toString() }))
        }
        val state = com.farside.workspace.tunnel.AdbDiscovery.flags(context)
        val debuggingEnabled = listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, "adb_wifi_enabled")
            .any { Settings.Global.getInt(context.contentResolver, it, 0) == 1 }
        check(state.developer == debuggingEnabled) { "Developer gate disagrees with the headset's debugging settings" }
        lines.add("developerGate=${state.developer} wireless=${state.wireless}")
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { lines.add("discovery started: $type") }
            override fun onStartDiscoveryFailed(type: String, code: Int) { lines.add("start failed: $code"); found.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) { lines.add("stop failed: $code") }
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                lines.add("found: ${info.serviceName}")
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) { lines.add("resolve failed: $code") }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        lines.add("resolved: ${service.serviceName} ${service.host}:${service.port}")
                        found.countDown()
                    }
                })
            }
        }
        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            found.await(20, TimeUnit.SECONDS)
        } catch (error: Exception) { lines.add("discovery exception: $error") }
        finally { runCatching { nsd.stopServiceDiscovery(listener) } }
        val report = synchronized(lines) { lines.joinToString("\n") }
        File(context.cacheDir, "adb-capability.txt").writeText(report)
        return report
    }
}
