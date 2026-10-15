package com.farside.workspace

import android.view.Surface

class FreeRDPBridge : DesktopSessionBridge {
    @Volatile internal var disconnectListener: ((String, Boolean) -> Unit)? = null
    @Volatile override var clipboardListener: ((String) -> Unit)? = null
    @Volatile internal var displayCapture: DisplayCapturePermission? = null

    @androidx.annotation.Keep
    fun onNativeDisplayStart(surface: Surface?): Boolean = displayCapture?.start(surface) ?: false

    @androidx.annotation.Keep
    fun onNativeDisplayStop() { displayCapture?.stopStream() }

    @androidx.annotation.Keep
    fun onNativeDisconnected(reason: String, established: Boolean) {
        disconnectListener?.invoke(reason, established)
    }

    @androidx.annotation.Keep
    fun onNativeClipboardChanged(text: String) {
        clipboardListener?.invoke(text)
    }

    external fun connect(hostname: String, port: Int, username: String, password: String, remoteApp: String?, adminMode: Boolean, enableCamera: Boolean, enableMicrophone: Boolean, enableStorage: Boolean, surface: Surface, certificateHost: String, certificateVerifier: RdpCertificateVerifier,
                         proxyHost: String? = null, proxyPort: Int = 0, proxyUser: String? = null, proxyPassword: String? = null,
                         socksProxy: Boolean = false): Long
    external override fun sendClipboardText(instance: Long, text: String): Boolean
    external fun isClipboardConnected(instance: Long): Boolean
    external fun certificateMatchesHost(pem: ByteArray, host: String): Boolean
    external override fun setAudioMuted(instance: Long, speakerMuted: Boolean, micMuted: Boolean)
    external override fun isMicrophoneRecording(instance: Long): Boolean
    external override fun setCameraMuted(instance: Long, muted: Boolean)
    external override fun isCameraRecording(instance: Long): Boolean
    external fun isCameraMuted(instance: Long): Boolean
    external override fun isCameraUnsupported(instance: Long): Boolean
    external fun setCameraMaxDimension(maxDim: Int)
    // Debug instrumentation only; release native builds refuse verification patterns.
    external fun setCameraVerificationPattern(enabled: Boolean): Boolean
    external fun isStorageConnected(instance: Long): Boolean
    // Playback/capture frames, then nonzero playback/capture samples (verification; no audio content).
    external fun getAudioFrameCounts(instance: Long): LongArray
    external fun getAudioLatencyMetrics(instance: Long): LongArray
    external fun stallAudioForVerification(instance: Long)
    external fun getAudioPulseTimes(instance: Long): LongArray
    external fun disconnect(instance: Long)
    external fun setSurface(instance: Long, surface: Surface?)
    external override fun sendMouseEvent(instance: Long, x: Int, y: Int, flags: Int)
    external override fun sendKeyEvent(instance: Long, scancode: Int, isDown: Boolean)
    external override fun sendUnicodeKeyEvent(instance: Long, unicodeChar: Int, isDown: Boolean)
    external fun addSurface(instance: Long, surface: Surface)
    external fun removeSurface(instance: Long, surface: Surface)
    external override fun updateDisplay(instance: Long, width: Int, height: Int, zoom: Float)
    external override fun getDesktopWidth(instance: Long): Int
    external override fun getDesktopHeight(instance: Long): Int

    companion object {
        init {
            System.loadLibrary("freerdp-jni")
        }
        
        // Mouse flags based on PTR_FLAGS
        const val PTR_FLAGS_WHEEL = 0x0200
        const val PTR_FLAGS_HWHEEL = 0x0400
        const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100
        const val PTR_FLAGS_MOVE = 0x0800
        const val PTR_FLAGS_DOWN = 0x8000
        const val PTR_FLAGS_BUTTON1 = 0x1000 // Left
        const val PTR_FLAGS_BUTTON2 = 0x2000 // Right
        const val PTR_FLAGS_BUTTON3 = 0x4000 // Middle
    }
}
