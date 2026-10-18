package com.farside.workspace.tunnel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AdbTunnelService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    @Volatile private var relay: AdbLoopbackRelay? = null
    private var child: AdbTunnelProcess? = null
    companion object {
        const val STOP = "com.farside.workspace.STOP_ADB_TUNNEL"
        private const val CHANNEL = "AdbTunnel"
        private const val ID = 1002
        private val mutableStatus = MutableStateFlow("")
        val status = mutableStatus.asStateFlow()
        @Volatile var running = false
            private set
        @Volatile var currentPort: Int? = null
            private set
        @Volatile var connectorReady = false
            private set
        fun start(context: Context) { context.startForegroundService(Intent(context, AdbTunnelService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, AdbTunnelService::class.java)) }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val flags = AdbDiscovery.flags(this@AdbTunnelService)
            if (!flags.developer || !flags.wireless) {
                relay?.update(null)
                currentPort = null
                connectorReady = false
                update(getString(com.farside.workspace.R.string.adb_tunnel_paused_debugging))
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(com.farside.workspace.R.string.wireless_adb_tunnel), NotificationManager.IMPORTANCE_LOW))
        for (key in listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, "adb_wifi_enabled"))
            contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(ID, notification(getString(com.farside.workspace.R.string.checking_wireless_debugging)))
        if (job?.isActive == true) return START_STICKY
        val config = AdbTunnelSettings(this).load()
        if (config == null || !AdbDiscovery.flags(this).developer) { stopSelf(); return START_NOT_STICKY }
        running = true
        job = scope.launch {
            try {
                relay = AdbLoopbackRelay(config.port)
                while (isActive) {
                    val state = runCatching { runInterruptible { AdbDiscovery.discover(this@AdbTunnelService) } }.getOrDefault(AdbState(false, false))
                    ensureActive()
                    currentPort = state.port
                    relay?.update(state.port)
                    if (state.port == null) {
                        connectorReady = false
                        child?.close(); child = null
                        update(if (!state.developer || !state.wireless) getString(com.farside.workspace.R.string.adb_tunnel_paused_debugging) else getString(com.farside.workspace.R.string.adb_tunnel_paused_unavailable))
                    } else if (child?.alive() != true) {
                        connectorReady = false
                        child?.close()
                        update(getString(com.farside.workspace.R.string.adb_connecting_cloudflare, state.port))
                        child = AdbTunnelProcess(this@AdbTunnelService, config) { message ->
                            if (currentPort != null) update(getString(com.farside.workspace.R.string.adb_endpoint_status, currentPort, message))
                        }
                    } else {
                        val connector = child!!
                        connectorReady = connector.connected
                        update(getString(com.farside.workspace.R.string.adb_endpoint_status, state.port, when {
                            !connectorReady -> getString(com.farside.workspace.R.string.connecting_cloudflare)
                            !connector.routeConfigured -> getString(com.farside.workspace.R.string.cloudflare_check_adb_route)
                            else -> getString(com.farside.workspace.R.string.cloudflare_adb_route_ready)
                        }))
                    }
                    delay(15000)
                }
            } catch (_: Exception) {
                update(getString(com.farside.workspace.R.string.adb_tunnel_start_failed))
                stopSelf()
            } finally { child?.close(); child = null; relay?.close(); relay = null; currentPort = null }
        }
        return START_STICKY
    }
    // Updates the foreground service's own notification; POST_NOTIFICATIONS is not required for this.
    @SuppressLint("NotificationPermission")
    private fun update(message: String) {
        mutableStatus.value = message
        getSystemService(NotificationManager::class.java).notify(ID, notification(message))
    }
    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, AdbTunnelService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, com.farside.workspace.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(com.farside.workspace.R.string.wireless_adb_tunnel)).setContentText(message).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(com.farside.workspace.R.string.stop), stop).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        relay?.update(null)
        scope.cancel()
        running = false; currentPort = null; connectorReady = false; mutableStatus.value = getString(com.farside.workspace.R.string.stopped)
        super.onDestroy()
    }
}
