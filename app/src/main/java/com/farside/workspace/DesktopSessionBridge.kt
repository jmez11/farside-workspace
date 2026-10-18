package com.farside.workspace

import android.view.Surface

/** Shared desktop input/render contract. Protocols translate the existing XT/pointer events. */
interface DesktopSessionBridge {
    var clipboardListener: ((String) -> Unit)?
    fun sendClipboardText(instance: Long, text: String): Boolean
    fun sendMouseEvent(instance: Long, x: Int, y: Int, flags: Int)
    fun sendKeyEvent(instance: Long, scancode: Int, isDown: Boolean)
    fun sendUnicodeKeyEvent(instance: Long, unicodeChar: Int, isDown: Boolean)
    fun getDesktopWidth(instance: Long): Int
    fun getDesktopHeight(instance: Long): Int
    fun updateDisplay(instance: Long, width: Int, height: Int, zoom: Float)
    // Media controls exist only in the RDP toolbar.
    fun setAudioMuted(instance: Long, speakerMuted: Boolean, micMuted: Boolean) {}
    fun isMicrophoneRecording(instance: Long): Boolean = false
    fun getAudioPlaybackSessionId(instance: Long): Int = 0
    fun setCameraMuted(instance: Long, muted: Boolean) {}
    fun isCameraRecording(instance: Long): Boolean = false
    fun isCameraChannelConnected(instance: Long): Boolean = false
    fun getDecoderDescription(instance: Long): String = "Default"
}

internal interface DesktopSessionConnection {
    fun attach(next: Surface)
    fun detach()
    fun close()
}
