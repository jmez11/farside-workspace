package com.farside.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import metavrx.layout.compose.SpatialScene

class SessionActivity : ComponentActivity() {
    internal var keyboardPanelState: metavrx.layout.window.compose.state.WindowState? = null
    internal var keyboardEditorHasFocus = false
    internal var keyboardPanelEditorHasFocus = false
    internal var keyboardEditorView: android.view.View? = null
    internal var keyboardPanelEditorView: android.view.View? = null
    internal var keyboardEditorActions: ImeEditorActions? = null
    internal var keyboardPanelEditorActions: ImeEditorActions? = null
    internal var remoteScroll: ((Int) -> Unit)? = null
    internal var setZoom: ((Float) -> Unit)? = null
    internal var setPan: ((Float, Float) -> Unit)? = null
    internal var onTouchDebug: ((android.view.MotionEvent, Float, Float, Float) -> Unit)? = null
    private val controllerScroll = RdpControllerScroll { remoteScroll?.invoke(it) }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (rdpInstance != 0L && remoteScroll != null && controllerScroll.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        controllerScroll.stop()
        super.onPause()
    }
    internal var rdpInstance = 0L
        private set
    private val windowInteraction = androidx.compose.runtime.mutableStateOf(0L)
    private var placement: SessionWindowPlacement? = null
    private var placementProfile: String? = null
    internal var positionSlot = 0
        private set
    private val movementObserver = WindowMovementObserver(this,
        onMoved = { windowInteraction.value += 1L },
        onPose = { placement?.observe(it) })

    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post {
            if (!isDestroyed) {
                movementObserver.start()
                placement?.restoreOnce()
            }
        }
    }

    override fun onStop() {
        placement?.saveOnClose()
        super.onStop()
    }

    override fun onDestroy() {
        placement?.saveOnClose()
        movementObserver.close()
        if (!isChangingConfigurations) placementProfile?.let { SessionWindowSlots.release(it, positionSlot) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) controllerScroll.stop()
        if (keyboardPanelState?.lifecycle?.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) return
        // System window affordances may take focus while moving/resizing the panel.
        windowInteraction.value += 1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val profileId = intent.getStringExtra("profileId")
        if (profileId == null) {
            finish()
            return
        }
        
        val profileManager = ProfileManager(this)
        val profile = profileManager.getProfiles().find { it.id == profileId }
        
        if (profile == null) {
            finish()
            return
        }
        placementProfile = profile.id
        positionSlot = if (intent.hasExtra(SessionWindowSlots.EXTRA_SLOT)) intent.getIntExtra(SessionWindowSlots.EXTRA_SLOT, 0).coerceAtLeast(0)
            else SessionWindowSlots.reserve(this, profile.id, taskId)
        intent.putExtra(SessionWindowSlots.EXTRA_SLOT, positionSlot)
        SessionWindowSlots.claim(profile.id, positionSlot)
        placement = SessionWindowPlacement(this, profile.id, positionSlot)
        setTitle(profile.name)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(profile.name))
        }

        setContent {
            val credential = profileManager.getCredentials().find { it.id == profile.credentialId }
            val requiresPin = credential?.requirePin == true && profileManager.getAppPinVerifier() != null
            var unlocked by remember { mutableStateOf(!requiresPin) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    if (!unlocked) {
                        com.farside.workspace.ui.CredentialPinDialog(
                            title = "Unlock connection",
                            message = "Enter the App PIN to connect to ${profile.name}.",
                            onUnlocked = { unlocked = true },
                            onCancel = { finish() }
                        )
                    } else if (profile.type == ConnectionType.RDP) {
                        SpatialScene {
                        RdpSessionScreen(
                            session = profile,
                            windowInteraction = windowInteraction.value,
                            onInstanceCreated = { rdpInstance = it },
                            onClose = { finish() }
                        )
                        }
                    } else {
                        SshSessionScreen(
                            session = profile,
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }
}
