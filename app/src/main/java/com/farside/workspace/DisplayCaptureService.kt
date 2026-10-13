package com.farside.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder

/** Bound for the lifetime of each consenting RDP session; the last unbind destroys the service. */
class DisplayCaptureService : Service() {
    internal inner class CaptureBinder : Binder() {
        fun promote() {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.headset_display_sharing), NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this@DisplayCaptureService, CHANNEL)
                .setContentTitle(getString(R.string.headset_display_sharing))
                .setContentText(getString(R.string.rdp_capture_notice))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
    }

    override fun onBind(intent: Intent): IBinder = CaptureBinder()

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "HeadsetDisplayCapture"
        const val NOTIFICATION = 1002
    }
}
