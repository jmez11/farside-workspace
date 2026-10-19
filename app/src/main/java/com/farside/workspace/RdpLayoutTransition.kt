package com.farside.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One mask for a burst of layout changes, including retries and intermediate GFX resets. */
internal class RdpLayoutTransition {
    var active by mutableStateOf(false)
        private set
    private var stableSince: Long? = null
    fun begin(freeze: () -> Unit) {
        if (!active) freeze()
        active = true
        stableSince = null
    }
    fun settle(now: Long, ready: Boolean) {
        if (!active) return
        if (!ready) { stableSince = null; return }
        val since = stableSince
        if (since == null) stableSince = now
        // ResetGraphics/first EndPaint can precede the final server redraw.
        else if (now - since >= 1500) cancel()
    }
    fun cancel() { active = false; stableSince = null }
}
