package com.multidesk.explorer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.multidesk.explorer.data.ConnectionProfile
import com.multidesk.explorer.tunnel.CloudflaredService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes connect, surface replacement and teardown; resizing never closes the RDP session. */
internal class RdpSessionConnection(
    context: Context,
    private val profile: ConnectionProfile,
    private val bridge: FreeRDPBridge,
    private val onConnected: (Long) -> Unit,
) {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    // Accessed only on worker.
    private var instance = 0L
    private var surface: Surface? = null

    fun attach(next: Surface) {
        if (closed.get()) {
            next.release()
            return
        }
        worker.execute {
            if (closed.get()) {
                next.release()
                return@execute
            }
            if (instance == 0L) {
                var host = profile.host
                var port = profile.port
                if (profile.useCloudflare) {
                    port = 33890 + (Math.random() * 1000).toInt()
                    context.startForegroundService(Intent(context, CloudflaredService::class.java).apply {
                        action = CloudflaredService.ACTION_START
                        putExtra(CloudflaredService.EXTRA_HOSTNAME, profile.host)
                        putExtra(CloudflaredService.EXTRA_LOCAL_PORT, port)
                    })
                    host = "127.0.0.1"
                    Thread.sleep(2000)
                }
                instance = bridge.connect(host, port, profile.user, profile.pass, profile.remoteApp,
                    profile.enableCamera, profile.cameraId, profile.redirectMicrophone && hasMicrophonePermission(context), next)
                val connected = instance
                main.post { if (!closed.get()) onConnected(connected) }
            } else {
                bridge.setSurface(instance, next)
            }
            surface?.release()
            surface = next
        }
    }

    fun detach() {
        if (closed.get()) return
        worker.execute {
            if (instance != 0L) bridge.setSurface(instance, null)
            surface?.release()
            surface = null
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            if (instance != 0L) bridge.disconnect(instance)
            instance = 0L
            surface?.release()
            surface = null
            if (profile.useCloudflare) {
                context.startService(Intent(context, CloudflaredService::class.java).apply {
                    action = CloudflaredService.ACTION_STOP
                })
            }
        }
        worker.shutdown()
    }
}
