package com.farside.workspace

import android.view.Surface

/** Per-session LibVNCClient owner. The monitor serializes native handles with teardown. */
class VncBridge : DesktopSessionBridge {
    @Volatile override var clipboardListener: ((String) -> Unit)? = null
    @Volatile internal var disconnectListener: ((Int, Boolean) -> Unit)? = null
    private var active = 0L
    private val callbacks = android.os.Handler(android.os.Looper.getMainLooper())
    private val heldPrintable = mutableMapOf<Int, Int>()

    @Synchronized fun connect(host: String, port: Int, password: String, surface: Surface): Long {
        check(active == 0L)
        require(port in 1..65535)
        if (!(password.isNotEmpty() && password.toByteArray(Charsets.ISO_8859_1).size <= 8 && password.all { it.code in 1..255 }))
            throw LocalizableException(R.string.vnc_password_latin1)
        active = nativeConnect(host.removeSurrounding("[", "]"), port, password.toByteArray(Charsets.ISO_8859_1), surface)
        return active
    }
    @Synchronized fun disconnect(instance: Long) {
        if (instance == 0L || instance != active) return
        nativeDisconnect(instance)
        active = 0L
        heldPrintable.clear()
    }
    @Synchronized fun setSurface(instance: Long, surface: Surface?) { if (instance != 0L && instance == active) nativeSetSurface(instance, surface) }
    @Synchronized fun sendPointerEvent(instance: Long, x: Int, y: Int, buttonMask: Int) {
        if (instance != 0L && instance == active) nativePointer(instance, x, y, buttonMask)
    }
    @Synchronized override fun sendMouseEvent(instance: Long, x: Int, y: Int, flags: Int) {
        if (instance != 0L && instance == active) nativeMouse(instance, x, y, flags)
    }
    /** XT scancodes from the shared strip map to X11 keysyms, including Command/Super. */
    @Synchronized override fun sendKeyEvent(instance: Long, scancode: Int, isDown: Boolean) {
        if (instance == 0L || instance != active) return
        xtKeysym(scancode)?.let { nativeKey(instance, it, isDown) }
    }
    /** Unicode is one X11 keysym, including supplementary code points. */
    @Synchronized override fun sendUnicodeKeyEvent(instance: Long, unicodeChar: Int, isDown: Boolean) {
        if (instance == 0L || instance != active || !Character.isValidCodePoint(unicodeChar)) return
        val keysym = when (unicodeChar) { 8 -> 0xff08; 9 -> 0xff09; 10, 13 -> 0xff0d; 27 -> 0xff1b
            else -> if (unicodeChar <= 255) unicodeChar else 0x01000000 or unicodeChar }
        nativeKey(instance, keysym, isDown)
    }
    // Key release retains its down keysym if Shift/Ctrl changed in between.
    @Synchronized internal fun sendPhysicalKey(instance: Long, event: android.view.KeyEvent, down: Boolean) {
        if (instance == 0L || instance != active) return
        val key = event.keyCode
        val unicode = event.unicodeChar
        val symbol = if (!down) heldPrintable.remove(key) else null
        val chosen = symbol ?: if (unicode != 0 && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            if (unicode <= 255) unicode else 0x01000000 or unicode
        } else androidKeysym(key) ?: return
        if (down) heldPrintable[key] = chosen
        nativeKey(instance, chosen, down)
    }
    @Synchronized override fun sendClipboardText(instance: Long, text: String): Boolean =
        instance != 0L && instance == active && nativeClipboard(instance, text.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.ISO_8859_1))
    @Synchronized override fun getDesktopWidth(instance: Long): Int = if (instance != 0L && instance == active) nativeSize(instance, true) else 0
    @Synchronized override fun getDesktopHeight(instance: Long): Int = if (instance != 0L && instance == active) nativeSize(instance, false) else 0
    override fun updateDisplay(instance: Long, width: Int, height: Int, zoom: Float) = Unit // VNC follows server size; scale locally.
    @androidx.annotation.Keep fun onNativeDisconnected() { callbacks.post { disconnectListener?.invoke(R.string.vnc_server_disconnected, true) } }
    @androidx.annotation.Keep fun onNativeClipboardChanged(bytes: ByteArray, utf8: Boolean) {
        val text = bytes.toString(if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1)
        callbacks.post { clipboardListener?.invoke(text) }
    }
    private external fun nativeConnect(host: String, port: Int, password: ByteArray, surface: Surface): Long
    private external fun nativeDisconnect(instance: Long)
    private external fun nativeSetSurface(instance: Long, surface: Surface?)
    private external fun nativePointer(instance: Long, x: Int, y: Int, mask: Int)
    private external fun nativeMouse(instance: Long, x: Int, y: Int, flags: Int)
    private external fun nativeKey(instance: Long, keysym: Int, down: Boolean)
    private external fun nativeClipboard(instance: Long, utf8: ByteArray, latin1: ByteArray): Boolean
    private external fun nativeSize(instance: Long, width: Boolean): Int
    companion object {
        init { System.loadLibrary("vnc-jni") }
        internal fun xtKeysym(code: Int): Int? = when (code) {
            0x01 -> 0xff1b; 0x0e -> 0xff08; 0x0f -> 0xff09; 0x1c, 0x11c -> 0xff0d
            0x1d -> 0xffe3; 0x11d -> 0xffe4; 0x2a -> 0xffe1; 0x36 -> 0xffe2
            0x38 -> 0xffe9; 0x138 -> 0xffea; 0x15b -> 0xffeb; 0x15c -> 0xffec
            0x3a -> 0xffe5; 0x45 -> 0xff7f; 0x46 -> 0xff14; 0x137 -> 0xff61; 0x145 -> 0xff13
            0x147 -> 0xff50; 0x148 -> 0xff52; 0x149 -> 0xff55; 0x14b -> 0xff51
            0x14d -> 0xff53; 0x14f -> 0xff57; 0x150 -> 0xff54; 0x151 -> 0xff56
            0x152 -> 0xff63; 0x153 -> 0xffff; 0x39 -> 32; 0x0c -> 45; 0x0d -> 61
            0x33 -> 44; 0x34 -> 46; 0x35, 0x135 -> 47; 0x37 -> 42; 0x4a -> 45; 0x4e -> 43
            in 0x02..0x0b -> "1234567890"[code - 2].code
            in 0x3b..0x44 -> 0xffbe + code - 0x3b
            0x57 -> 0xffc8; 0x58 -> 0xffc9
            else -> letterScancodes[code]?.code
        }
        internal fun androidKeysym(code: Int): Int? = when (code) {
            in 29..54 -> 'a'.code + code - 29
            in 7..16 -> '0'.code + code - 7
            in 131..142 -> 0xffbe + code - 131
            114 -> 0xffe4; 58 -> 0xffea; 60 -> 0xffe2; 117 -> 0xffeb; 118 -> 0xffec
            115 -> 0xffe5; 116 -> 0xff14; 143 -> 0xff7f; 120 -> 0xff61; 121 -> 0xff13
            122 -> 0xff50; 123 -> 0xff57; 92 -> 0xff55; 93 -> 0xff56; 124 -> 0xff63; 112 -> 0xffff
            68 -> 96; 69 -> 45; 70 -> 61; 71 -> 91; 72 -> 93; 73 -> 92; 74 -> 59; 75 -> 39
            76 -> 47; 55 -> 44; 56 -> 46
            in 144..153 -> 0xffb0 + code - 144
            154 -> 0xffaf; 155 -> 0xffaa; 156 -> 0xffad; 157 -> 0xffab; 158 -> 0xffae; 159 -> 0xffac; 160 -> 0xff8d
            else -> xtKeysym(mapAndroidKeyCodeToXt(code))
        }
        private val letterScancodes = listOf(0x10 to "qwertyuiop", 0x1e to "asdfghjkl", 0x2c to "zxcvbnm")
            .flatMap { (base, letters) -> letters.mapIndexed { i, c -> base + i to c } }.toMap() +
            mapOf(0x1a to '[', 0x1b to ']', 0x27 to ';', 0x28 to '\'', 0x29 to '`', 0x2b to '\\')
    }
}
