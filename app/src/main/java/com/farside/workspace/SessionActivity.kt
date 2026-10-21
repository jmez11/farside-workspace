package com.farside.workspace

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
    internal var rdpMonitor: RdpMonitor? = null
    internal var cardConnected = false
    internal var sshCaptureView: android.view.View? = null
    internal lateinit var displayCapture: DisplayCapturePermission
        private set
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
        set(value) {
            field = value
            if (value == null) controllerScroll.stop()
        }
    internal var setZoom: ((Float) -> Unit)? = null
    internal var setPan: ((Float, Float) -> Unit)? = null
    internal var macHover:((Float,Float)->Unit)?=null
    internal var onTouchDebug: ((android.view.MotionEvent, Float, Float, Float) -> Unit)? = null
    private val controllerScroll = RdpControllerScroll { remoteScroll?.invoke(it) }
    internal var sshHistoryScroll: ((Int) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) sshControllerScroll.stop()
        }
    private val sshControllerScroll = SshControllerScroll { sshHistoryScroll?.invoke(it) }
    private var nextSshInputLog = 0L

    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
        if(vncBridge?.isMacHevc==true) MacInputTrace.event("activity-touch")
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if(vncBridge?.isMacHevc==true) {
            MacInputTrace.event("activity-generic")
            if(event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) &&
                event.actionMasked in listOf(android.view.MotionEvent.ACTION_HOVER_ENTER,android.view.MotionEvent.ACTION_HOVER_MOVE)) {
                macHover?.invoke(event.x,event.y)
            }
        }
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
    internal fun checkAndBindAudioSession() {
        rdpMonitor?.let { if (it.session.isOwner(it)) it.session.updateMediaHost(); return }
        val bridge = rdpBridge ?: return
        val space = QuestSpatialAudio.windowSpace(this) ?: return
        bridge.audioWindowSpace = space
        if (rdpInstance != 0L) {
            val session = bridge.getAudioPlaybackSessionId(rdpInstance)
            if (session > 0) bridge.onNativeAudioSessionChanged(session)
        }
    }

    private val movementObserver = WindowMovementObserver(this,
        onMoved = {
            windowInteraction.value += 1L
            checkAndBindAudioSession()
        },
        onPresented = { WindowLaunchMonitor.complete(intent.getStringExtra(WindowLaunchMonitor.EXTRA_REQUEST)) })

    internal var restoreVisibilityOnResume = false

    override fun onResume() {
        super.onResume()
        if (restoreVisibilityOnResume) {
            restoreVisibilityOnResume = false
            LauncherWindowLauncher.setSessionVisible(this, true)
        }
        checkAndBindAudioSession()
    }

    override fun onPostResume() {
        super.onPostResume()
        SessionCardActions.focused(this)
        window.decorView.post {
            if (!isDestroyed) {
                movementObserver.start()
                WindowLaunchMonitor.observe(this)
                rdpMonitor?.let { monitor ->
                    window.decorView.viewTreeObserver.registerFrameCommitCallback {
                        if (!isDestroyed && window.decorView.isShown) monitor.session.presented(monitor)
                    }
                    window.decorView.invalidate()
                }
                checkAndBindAudioSession()
            }
        }
    }

    override fun onDestroy() {
        SessionCardActions.unregister(this)
        sshControllerScroll.stop()
        credentialPrefs?.unregisterOnSharedPreferenceChangeListener(credentialListener)
        rdpMonitor?.let { monitor ->
            if (isChangingConfigurations) { if (monitor.host.get() === this) monitor.host.clear() } else monitor.session.remove(monitor)
        }
        displayCapture.detachHost()
        if (rdpMonitor == null) displayCapture.close()
        movementObserver.close()
        if (!isChangingConfigurations) sessionProfile?.let { SessionWindowSlots.release(it, sessionSlot) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SessionCardActions.focused(this)
            checkAndBindAudioSession()
        }
        if (!hasFocus) controllerScroll.stop()
        if (!hasFocus) sshControllerScroll.stop()
        if (keyboardPanelState?.lifecycle?.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) return
        // System window affordances may take focus while moving/resizing the panel.
        windowInteraction.value += 1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayCapture = DisplayCapturePermission(this)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = LauncherWindowLauncher.show(this@SessionActivity)
        })
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
        if (profile.type == ConnectionType.RDP) {
            val group = intent.getStringExtra(RdpMonitorSessions.EXTRA_SESSION)
            val monitorId = intent.getLongExtra(RdpMonitorSessions.EXTRA_MONITOR, 0L)
            rdpMonitor = if (group != null) RdpMonitorSessions.find(group, monitorId) else RdpMonitorSessions.create(this, profile)
            if (rdpMonitor == null || rdpMonitor?.session?.profile?.id != profile.id) { finishAndRemoveTask(); return }
            val monitor = rdpMonitor!!
            intent.putExtra(RdpMonitorSessions.EXTRA_SESSION, monitor.session.id)
            intent.putExtra(RdpMonitorSessions.EXTRA_MONITOR, monitor.id)
            monitor.session.bindHost(monitor, this)
        }
        sessionProfile = profile.id
        SessionCardActions.register(this, profile.id)
        sessionCredential = profile.credentialId
        if (profile.credentialId != null && profileManager.getCredentials().none { it.id == profile.credentialId }) {
            android.widget.Toast.makeText(this, getString(R.string.saved_credential_deleted), android.widget.Toast.LENGTH_LONG).show()
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
        val headerTitle = if (profile.jumpHostProfileId.isNullOrBlank()) profile.name else runCatching {
            com.farside.workspace.ssh.JumpChains.resolveChain(profile, com.farside.workspace.data.ProfileManager(this).getProfiles()).describe(this)
        }.getOrDefault(profile.name)
        setTitle(headerTitle)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            setTaskDescription(android.app.ActivityManager.TaskDescription.Builder().setLabel(headerTitle).build())
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            setTaskDescription(android.app.ActivityManager.TaskDescription(headerTitle))
        }

        setContent {
            val credential = profileManager.getCredentials().find { it.id == profile.credentialId }
            val sshKey = profileManager.getSshKeys().find { it.id == profile.sshKeyId }
            val appPinVerifier = profileManager.getAppPinVerifier()
            val systemPin = profileManager.usesSystemPin()
            val requiresPin = ((credential?.requirePin == true) || (sshKey?.requirePin == true)) && (systemPin || appPinVerifier != null)
            var unlocked by remember { mutableStateOf(rdpMonitor?.session?.hasConnected?.value == true || !requiresPin || if (systemPin) DeviceCredentialAuth.hasRememberedUnlock(this)
                else appPinVerifier != null && CredentialPin.hasValidCachedPin(appPinVerifier)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    if (!unlocked) {
                        if (systemPin) com.farside.workspace.ui.SystemCredentialDialog(
                            title = stringResource(R.string.unlock_connection),
                            message = stringResource(R.string.system_pin_connect_profile, profile.name),
                            onUnlocked = { unlocked = true }, onCancel = { finish() }
                        ) else
                            com.farside.workspace.ui.CredentialPinDialog(
                            title = stringResource(R.string.unlock_connection),
                            message = stringResource(R.string.app_pin_connect_profile, profile.name),
                            onUnlocked = { unlocked = true },
                            onCancel = { finish() }
                        )
                    } else if (profile.type == ConnectionType.RDP) {
                        SpatialScene {
                        RdpSessionScreen(
                            session = profile.copy(reconnectAutomatically = profileManager.getProfiles().firstOrNull { it.id == profile.id }?.reconnectAutomatically ?: false),
                            windowInteraction = windowInteraction.value,
                            monitor = rdpMonitor,
                            onInstanceCreated = {
                                rdpInstance = it
                                SessionCardActions.connected(this, it != 0L)
                                if (it != 0L) checkAndBindAudioSession()
                            },
                            onBridgeCreated = {
                                rdpBridge = it
                                if (rdpMonitor == null) {
                                    it.displayCapture = displayCapture
                                    it.audioWindowSpace = QuestSpatialAudio.windowSpace(this)
                                }
                            },
                            onClose = { finish() }
                        )
                        }
                    } else if (profile.type == ConnectionType.VNC) {
                        SpatialScene {
                            VncSessionScreen(profile.copy(reconnectAutomatically = profileManager.getProfiles().firstOrNull { it.id == profile.id }?.reconnectAutomatically ?: false), onInstanceCreated = { vncInstance = it; SessionCardActions.connected(this, it != 0L) },
                                onBridgeCreated = { vncBridge = it }, onClose = { finish() },
                                windowInteraction = windowInteraction.value)
                        }
                    } else {
                        SpatialScene {
                        SshSessionScreen(
                            session = profile.copy(reconnectAutomatically = profileManager.getProfiles().firstOrNull { it.id == profile.id }?.reconnectAutomatically ?: false),
                            windowInteraction = windowInteraction.value,
                            onClose = { finish() }
                        )
                        }
                    }
                }
            }
        }
    }

    override fun onNavigateUp(): Boolean {
        LauncherWindowLauncher.show(this)
        return true
    }
}
