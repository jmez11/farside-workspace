package com.multidesk.explorer

import android.view.Surface

class FreeRDPBridge {
    @Volatile internal var disconnectListener: ((String, Boolean) -> Unit)? = null

    @androidx.annotation.Keep
    fun onNativeDisconnected(reason: String, established: Boolean) {
        disconnectListener?.invoke(reason, established)
    }

    external fun connect(hostname: String, port: Int, username: String, password: String, remoteApp: String?, enableCamera: Boolean, cameraId: String?, enableMicrophone: Boolean, surface: Surface, certificateHost: String, certificateVerifier: RdpCertificateVerifier): Long
    external fun certificateMatchesHost(pem: ByteArray, host: String): Boolean
    external fun setAudioMuted(instance: Long, speakerMuted: Boolean, micMuted: Boolean)
    external fun isMicrophoneRecording(instance: Long): Boolean
    // Playback/capture frames, then nonzero playback/capture samples (verification; no audio content).
    external fun getAudioFrameCounts(instance: Long): LongArray
    external fun disconnect(instance: Long)
    external fun setSurface(instance: Long, surface: Surface?)
    external fun sendMouseEvent(instance: Long, x: Int, y: Int, flags: Int)
    external fun sendKeyEvent(instance: Long, scancode: Int, isDown: Boolean)
    external fun sendUnicodeKeyEvent(instance: Long, unicodeChar: Int, isDown: Boolean)
    external fun addSurface(instance: Long, surface: Surface)
    external fun removeSurface(instance: Long, surface: Surface)
    external fun updateDisplay(instance: Long, width: Int, height: Int, zoom: Float)
    external fun getDesktopWidth(instance: Long): Int
    external fun getDesktopHeight(instance: Long): Int

    companion object {
        init {
            System.loadLibrary("freerdp-jni")
        }
        
        // Mouse flags based on PTR_FLAGS
        const val PTR_FLAGS_WHEEL = 0x0200
        const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100
        const val PTR_FLAGS_MOVE = 0x0800
        const val PTR_FLAGS_DOWN = 0x8000
        const val PTR_FLAGS_BUTTON1 = 0x1000 // Left
        const val PTR_FLAGS_BUTTON2 = 0x2000 // Right
        const val PTR_FLAGS_BUTTON3 = 0x4000 // Middle
    }
}
