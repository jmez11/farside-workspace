package com.multidesk.explorer.tunnel

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
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream

class CloudflaredService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null

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
            
            return START_STICKY
        } else if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun startTunnel(hostname: String, localPort: Int) {
        scope.launch {
            try {
                val executable = extractCloudflaredBinary()
                if (executable == null) {
                    Log.e("CloudflaredService", "Could not extract cloudflared binary")
                    stopSelf()
                    return@launch
                }

                val pb = ProcessBuilder(
                    executable.absolutePath,
                    "access",
                    "tcp",
                    "--hostname", hostname,
                    "--url", "localhost:$localPort"
                )
                pb.redirectErrorStream(true)
                process = pb.start()
                
                val reader = process?.inputStream?.bufferedReader()
                var line: String?
                while (reader?.readLine().also { line = it } != null) {
                    Log.i("CloudflaredService", "[cloudflared] $line")
                }
                
                Log.w("CloudflaredService", "Cloudflared process exited with code ${process?.exitValue()}")
            } catch (e: Exception) {
                Log.e("CloudflaredService", "Error running cloudflared: ${e.message}", e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopTunnel() {
        process?.destroy()
        process = null
        scope.cancel()
    }

    private fun extractCloudflaredBinary(): File? {
        // In a real implementation, we would extract the binary from res/raw or assets
        // For this spike/implementation, we assume it's in the app's files directory.
        val binaryFile = File(filesDir, "cloudflared")
        if (!binaryFile.exists()) {
            // Mock: Create a dummy shell script that just loops, for testing without the real binary.
            // In reality, we'd copy it from assets: assets.open("cloudflared-arm64").use { ... }
            binaryFile.writeText("#!/system/bin/sh\necho 'Mock cloudflared starting'\nwhile true; do sleep 1; done\n")
            binaryFile.setExecutable(true)
        }
        return binaryFile
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
            // .setSmallIcon(R.drawable.ic_tunnel) // Need an icon resource
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
    }
}
