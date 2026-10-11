package com.farside.workspace

import androidx.compose.runtime.Composable
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.SoundRedirection

/** Uses the same unscaled input overlay, gestures, toolbar, IME and fixed key panel as RDP. */
@Composable
fun VncSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit,
    windowInteraction: Long = 0L, onBridgeCreated: ((VncBridge) -> Unit)? = null) {
    DesktopSessionScreen(session.copy(enableCamera = false, redirectMicrophone = false,
        redirectStorage = false, redirectClipboard = true, soundRedirection = SoundRedirection.DO_NOT_PLAY, remoteApp = null),
        onInstanceCreated, onClose, windowInteraction, { onBridgeCreated?.invoke(it as VncBridge) })
}
