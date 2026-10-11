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
    internal val displayCapture = DisplayCapturePermission(this)
    internal var keyboardPanelState: metavrx.layout.window.compose.state.WindowState? = null
    internal var keyboardBankState: metavrx.layout.window.compose.state.WindowState? = null
    internal var keyboardBankEditorView: android.view.View? = null
    internal var keyboardBankEditorHasFocus = false
    internal var keyboardBankEditorActions: ImeEditorActions? = null
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
    internal var sshHistoryScroll: ((Int) -> Unit)? = null
    private val sshControllerScroll = SshControllerScroll { sshHistoryScroll?.invoke(it) }
    private var nextSshInputLog = 0L

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (sshHistoryScroll != null && sshControllerScroll.onMotion(event)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (BuildConfig.DEBUG && now >= nextSshInputLog) {
                android.util.Log.d("SshInput", "motion=${event.actionMasked} source=${event.source} y=${event.getAxisValue(android.view.MotionEvent.AXIS_Y)} ry=${event.getAxisValue(android.view.MotionEvent.AXIS_RY)} hat=${event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)} wheel=${event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)} focus=${hasWindowFocus()}")
                nextSshInputLog = now + 1000
            }
            return true
        }
        if ((rdpInstance != 0L || vncInstance != 0L) && remoteScroll != null && controllerScroll.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        sshControllerScroll.stop()
        controllerScroll.stop()
        super.onPause()
    }
    internal var vncBridge: VncBridge? = null
    internal var vncInstance = 0L
    internal var rdpBridge: FreeRDPBridge? = null
        internal set
    internal var rdpInstance = 0L
        internal set
    private val windowInteraction = androidx.compose.runtime.mutableStateOf(0L)
    private var sessionProfile: String? = null
    private var sessionCredential: String? = null
    private var credentialPrefs: android.content.SharedPreferences? = null
    private val credentialListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "credentials" && sessionCredential?.let { id ->
                ProfileManager(this).getCredentials().none { it.id == id }
            } == true) runOnUiThread { finish() }
    }
    internal var sessionSlot = 0
        private set
    private val movementObserver = WindowMovementObserver(this,
        onMoved = { windowInteraction.value += 1L },
        onPresented = { WindowLaunchMonitor.complete(intent.getStringExtra(WindowLaunchMonitor.EXTRA_REQUEST)) })

    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post {
            if (!isDestroyed) {
                movementObserver.start()
                WindowLaunchMonitor.observe(this)
            }
        }
    }

    override fun onDestroy() {
        sshControllerScroll.stop()
        credentialPrefs?.unregisterOnSharedPreferenceChangeListener(credentialListener)
        displayCapture.close()
        movementObserver.close()
        if (!isChangingConfigurations) sessionProfile?.let { SessionWindowSlots.release(it, sessionSlot) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) controllerScroll.stop()
        if (!hasFocus) sshControllerScroll.stop()
        if (keyboardPanelState?.lifecycle?.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) return
        // System window affordances may take focus while moving/resizing the panel.
        windowInteraction.value += 1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionWindowLauncher.forgetLegacyPositions(this)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
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
        sessionProfile = profile.id
        sessionCredential = profile.credentialId
        if (profile.credentialId != null && profileManager.getCredentials().none { it.id == profile.credentialId }) {
            android.widget.Toast.makeText(this, "Saved credential was deleted. Choose another credential for this connection.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        credentialPrefs = getSharedPreferences("farside_profiles", MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(credentialListener)
        }
        sessionSlot = if (intent.hasExtra(SessionWindowSlots.EXTRA_SLOT)) intent.getIntExtra(SessionWindowSlots.EXTRA_SLOT, 0).coerceAtLeast(0)
            else SessionWindowSlots.reserve(this, profile.id, taskId)
        intent.putExtra(SessionWindowSlots.EXTRA_SLOT, sessionSlot)
        SessionWindowSlots.claim(profile.id, sessionSlot)
        setTitle(profile.name)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(android.app.ActivityManager.TaskDescription(profile.name))
        }

        setContent {
            val credential = profileManager.getCredentials().find { it.id == profile.credentialId }
            val sshKey = profileManager.getSshKeys().find { it.id == profile.sshKeyId }
            val appPinVerifier = profileManager.getAppPinVerifier()
            val requiresPin = ((credential?.requirePin == true) || (sshKey?.requirePin == true)) && appPinVerifier != null
            var unlocked by remember { mutableStateOf(!requiresPin || (appPinVerifier != null && CredentialPin.hasValidCachedPin(appPinVerifier))) }
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
                            onBridgeCreated = { rdpBridge = it; it.displayCapture = displayCapture },
                            onClose = { finish() }
                        )
                        }
                    } else if (profile.type == ConnectionType.VNC) {
                        SpatialScene {
                            VncSessionScreen(profile, onInstanceCreated = { vncInstance = it },
                                onBridgeCreated = { vncBridge = it }, onClose = { finish() },
                                windowInteraction = windowInteraction.value)
                        }
                    } else {
                        SpatialScene {
                        SshSessionScreen(
                            session = profile,
                            windowInteraction = windowInteraction.value,
                            onClose = { finish() }
                        )
                        }
                    }
                }
            }
        }
    }
}
