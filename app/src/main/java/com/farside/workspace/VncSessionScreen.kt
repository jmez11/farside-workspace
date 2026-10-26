package com.farside.workspace

import androidx.compose.runtime.Composable
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.SoundRedirection

/** Uses the same unscaled input overlay, gestures, toolbar, IME and fixed key panel as RDP. */
@Composable
fun VncSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit,
    windowInteraction: Long = 0L, onBridgeCreated: ((VncBridge) -> Unit)? = null, windowMovement: Long = 0L) {
    DesktopSessionScreen(session.copy(enableCamera = false, redirectMicrophone = false,
        redirectStorage = false, soundRedirection = if(session.expMacHevc && session.macAudioEnabled) SoundRedirection.PLAY_ON_DEVICE else SoundRedirection.DO_NOT_PLAY, remoteApp = null),
        onInstanceCreated, onClose, windowInteraction, { onBridgeCreated?.invoke(it as VncBridge) }, windowMovement = windowMovement)
}
