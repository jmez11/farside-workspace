package com.farside.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Session-scoped Android text clipboard binding, shared by desktop protocols. */
internal class DesktopClipboardSync(context: Context, private val bridge: DesktopSessionBridge,
    private val instance: Long, private val label: String) : Closeable {
    private val context = context.applicationContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var lastSent: String? = null
    private var lastReceived: String? = null
    @Volatile private var closed = false
    private val localChanged = ClipboardManager.OnPrimaryClipChangedListener { sendLocal() }

    init {
        bridge.clipboardListener = { text ->
            main.post {
                if (!closed && text.isNotEmpty() && text != lastSent) {
                    lastReceived = text
                    runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText(label, text)) }
                        .onFailure { android.util.Log.w("DesktopClipboard", "Clipboard update unavailable") }
                }
            }
        }
        clipboard?.addPrimaryClipChangedListener(localChanged)
        sendLocal()
    }

    private fun sendLocal() {
        if (closed || instance == 0L) return
        runCatching {
            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty() && text != lastSent && text != lastReceived &&
                bridge.sendClipboardText(instance, text)) lastSent = text
        }.onFailure { android.util.Log.w("DesktopClipboard", "Clipboard read/send unavailable") }
    }

    override fun close() {
        closed = true
        clipboard?.removePrimaryClipChangedListener(localChanged)
        bridge.clipboardListener = null
    }
}
