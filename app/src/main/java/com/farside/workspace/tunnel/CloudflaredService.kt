package com.farside.workspace.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

class CloudflaredService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnel: CloudflaredTunnel? = null

    companion object {
        const val CHANNEL_ID = "CloudflaredTunnelChannel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_TUNNEL"
        const val ACTION_STOP = "ACTION_STOP_TUNNEL"

        const val EXTRA_HOSTNAME = "EXTRA_HOSTNAME"
        const val EXTRA_LOCAL_PORT = "EXTRA_LOCAL_PORT"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: return START_NOT_STICKY
            val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0)
            if (localPort == 0) return START_NOT_STICKY

            startForeground(NOTIFICATION_ID, buildNotification(hostname))
            startTunnel(hostname, localPort)
            
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun startTunnel(hostname: String, localPort: Int) {
        if (tunnel != null) return
        val active = CloudflaredTunnel(applicationContext)
        tunnel = active
        scope.launch {
            try {
                active.start(hostname, "", {}, { stopSelf() }, localPort)
            } catch (e: Exception) {
                Log.e("CloudflaredService", "Tunnel setup failed: ${e.message}")
                active.close()
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        tunnel?.close()
        tunnel = null
        scope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Persistent Tunnels"
            val descriptionText = "Maintains Cloudflare tunnels in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(hostname: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        
        return builder
            .setContentTitle("Cloudflare Tunnel Active")
            .setContentText("Tunneling to $hostname")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
    }
}
