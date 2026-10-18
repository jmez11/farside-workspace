package com.farside.workspace

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.os.Bundle
import android.util.Log
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.MotionEvent
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.ExperimentalComposeUiApi

import metavrx.layout.compose.SpatialScene
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.WindowModifier
import metavrx.layout.window.compose.layout.size
import android.content.Intent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.LauncherScreen
import java.util.UUID

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalSoftwareKeyboardController


@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun RdpSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit, windowInteraction: Long = 0L, onBridgeCreated: ((FreeRDPBridge) -> Unit)? = null, monitor: RdpMonitor? = null) {
    DesktopSessionScreen(session, onInstanceCreated, onClose, windowInteraction,
        { onBridgeCreated?.invoke(monitor?.session?.bridge ?: (it as FreeRDPBridge)) }, monitor)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit,
    windowInteraction: Long = 0L, onBridgeCreated: ((DesktopSessionBridge) -> Unit)? = null, monitor: RdpMonitor? = null) {
    val runtime = monitor?.session
    var desktopTexture by remember { mutableStateOf<TextureView?>(null) }
    var frozenDesktop by remember { mutableStateOf<Bitmap?>(null) }
    val adjusting = runtime?.transition?.active == true
    // The frozen image covers live buffers immediately; only its dimming fades in.
    val adjustmentFade = remember { androidx.compose.animation.core.Animatable(0f) }
    val adjustmentDim by animateFloatAsState(if (adjusting) .48f else 0f, tween(240), label = "Adjustment dim")
    LaunchedEffect(adjusting) {
        if (adjusting) adjustmentFade.snapTo(1f)
        else { adjustmentFade.animateTo(0f,tween(320)); frozenDesktop = null }
    }
    DisposableEffect(monitor) {
        monitor?.freezeFrame = {
            if (frozenDesktop == null) desktopTexture?.takeIf { it.isAvailable }?.let { view ->
                // Bound the retained GPU readback while keeping the window aspect ratio.
                val scale = minOf(1f,1600f / view.width.coerceAtLeast(1),1000f / view.height.coerceAtLeast(1))
                frozenDesktop = runCatching { view.getBitmap((view.width*scale).toInt().coerceAtLeast(1),
                    (view.height*scale).toInt().coerceAtLeast(1)) }.getOrNull()
            }
        }
        onDispose { monitor?.freezeFrame = null; desktopTexture = null; frozenDesktop = null }
    }
    val isVnc = session.type == ConnectionType.VNC
    val protocolName = stringResource(if (isVnc) R.string.protocol_vnc else R.string.protocol_rdp)
    val sharedProfile = runtime?.configuredProfile
    val closeConnection = { if (runtime != null) runtime.closeWindows() else onClose() }
    val permissionContext = LocalContext.current
    val windowManagerAvailable = remember(permissionContext) { QuestWindowManager.isAvailable(permissionContext) }
    var microphoneReady by remember(session.id) {
        mutableStateOf(sharedProfile != null || !session.redirectMicrophone || hasMicrophonePermission(permissionContext))
    }
    var microphoneAllowed by remember(session.id) {
        mutableStateOf((sharedProfile ?: session).redirectMicrophone && hasMicrophonePermission(permissionContext))
    }
    val microphonePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneAllowed = granted && hasMicrophonePermission(permissionContext)
        microphoneReady = true
    }
    LaunchedEffect(session.id) {
        if (!microphoneReady) microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    var cameraReady by remember(session.id) {
        mutableStateOf(sharedProfile != null || !session.enableCamera || hasCameraPermission(permissionContext))
    }
    var cameraAllowed by remember(session.id) {
        mutableStateOf((sharedProfile ?: session).enableCamera && hasCameraPermission(permissionContext))
    }
    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        cameraAllowed = hasCameraPermission(permissionContext)
        cameraReady = true
    }
    LaunchedEffect(session.id) {
        if (!cameraReady) cameraPermission.launch(REQUIRED_CAMERA_PERMISSIONS)
    }

    var storageReady by remember(session.id) {
        mutableStateOf(sharedProfile != null || !session.redirectStorage || hasStoragePermission(permissionContext))
    }
    var storageAllowed by remember(session.id) {
        mutableStateOf((sharedProfile ?: session).redirectStorage && hasStoragePermission(permissionContext))
    }
    val storagePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        storageAllowed = session.redirectStorage && hasStoragePermission(permissionContext)
        storageReady = true
    }
    LaunchedEffect(session.id) {
        if (!storageReady) {
            requestStoragePermission(permissionContext) { intent -> storagePermission.launch(intent) }
        }
    }

    if (!microphoneReady || !cameraReady || !storageReady) {
        val statusText = when {
            !microphoneReady -> stringResource(R.string.waiting_microphone_permission)
            !cameraReady -> stringResource(R.string.waiting_camera_permission)
            else -> stringResource(R.string.waiting_storage_permission)
        }
        Text(statusText, color = Color.White)
        return
    }
    val effectiveSession = remember(session, microphoneAllowed, cameraAllowed, storageAllowed) {
        session.copy(
            redirectMicrophone = microphoneAllowed && hasMicrophonePermission(permissionContext),
            enableCamera = cameraAllowed && hasCameraPermission(permissionContext),
            redirectStorage = storageAllowed && hasStoragePermission(permissionContext)
        )
    }
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    val rdpBridge: DesktopSessionBridge = remember(session.id, reconnectAttempt, runtime?.bridge) {
        (monitor?.bridge ?: if (isVnc) VncBridge() else FreeRDPBridge()).also { onBridgeCreated?.invoke(it) }
    }
    var rdpInstance by (runtime?.instance ?: remember { mutableStateOf<Long>(0L) })
    val context = LocalContext.current
    val rdpClipboardLabel = stringResource(R.string.rdp_clipboard_label)
    val pairedSshId = remember(session.sftpPairProfileId) {
        if (isVnc) com.farside.workspace.data.ProfileManager(context).getProfiles()
            .firstOrNull { it.id == session.sftpPairProfileId && it.type == ConnectionType.SSH }?.id else null
    }
    var showVncFiles by remember { mutableStateOf(false) }
    var certificateRequest by (runtime?.certificateRequest ?: remember { mutableStateOf<RdpCertificateRequest?>(null) })
    var connectionFailed by (runtime?.connectionFailed ?: remember { mutableStateOf<Boolean>(false) })
    var connectFailureReason by (runtime?.failureReason ?: remember { mutableStateOf<String>("") })
    var disconnectedReason by (runtime?.disconnectedReason ?: remember { mutableStateOf<String?>(null) })
    var hasConnected by (runtime?.hasConnected ?: remember(session.id) { mutableStateOf<Boolean>(false) })
    var autoReconnect by (runtime?.autoReconnect ?: remember(session.id) { mutableStateOf<Boolean>(session.reconnectAutomatically) })
    var tunnelStatus by (runtime?.tunnelStatus ?: remember { mutableStateOf<String>("") })
    val certificates = remember(session.id, reconnectAttempt) {
        if (runtime == null) RdpCertificateVerifier(context, session.host, session.port, { certificateRequest = it }) else null
    }
    val connection: DesktopSessionConnection = remember(session.id, reconnectAttempt) {
        val ended: (String, Boolean) -> Unit = { reason, established ->
            (context as? SessionActivity)?.let { SessionCardActions.connected(it, false) }
            if (established || hasConnected) { disconnectedReason = reason; connectionFailed = false }
            else connectFailureReason = reason
        }
        val status: (String) -> Unit = { tunnelStatus = it }
        val connected: (Long) -> Unit = {
            rdpInstance = it
            if (it != 0L) hasConnected = true
            else if (hasConnected && disconnectedReason == null) disconnectedReason = connectFailureReason
            connectionFailed = it == 0L && !hasConnected
            onInstanceCreated(it)
        }
        if (monitor != null) { runtime!!.configure(effectiveSession); monitor.windowConnection() }
        else if (isVnc) VncSessionConnection(context, effectiveSession, rdpBridge as VncBridge, ended, status, connected)
        else RdpSessionConnection(context, effectiveSession, rdpBridge as FreeRDPBridge, certificates!!, onDisconnected = ended, onStatus = status, onConnected = connected)
    }
    DisposableEffect(connection) {
        onDispose {
            if (runtime == null) rdpInstance = 0L
            connection.close()
        }
    }

    LaunchedEffect(rdpInstance) {
        if (rdpInstance != 0L) {
            val activity = context as? SessionActivity
            while (true) {
                activity?.checkAndBindAudioSession()
                delay(500)
            }
        }
    }

    LaunchedEffect(rdpInstance, runtime?.bridge) { if (runtime != null) onInstanceCreated(rdpInstance) }

    // Bidirectional RDP Clipboard Synchronization
    if (session.redirectClipboard && runtime == null) {
        DisposableEffect(context, rdpInstance) {
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            var lastSentClipText: String? = null
            var lastReceivedClipText: String? = null

            val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                if (rdpInstance != 0L) {
                    try {
                        val clip = clipboardManager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        val text = clip?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty() && text != lastReceivedClipText && text != lastSentClipText) {
                            lastSentClipText = text
                            rdpBridge.sendClipboardText(rdpInstance, text)
                        }
                    } catch (e: Exception) {
                        Log.e("RdpClipboard", "Failed to send clipboard text: ${e.message}")
                    }
                }
            }

            rdpBridge.clipboardListener = { remoteText ->
                if (remoteText.isNotEmpty() && remoteText != lastSentClipText) {
                    lastReceivedClipText = remoteText
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText(rdpClipboardLabel, remoteText))
                        } catch (e: Exception) {
                            Log.e("RdpClipboard", "Failed to set primary clip: ${e.message}")
                        }
                    }
                }
            }

            clipboardManager?.addPrimaryClipChangedListener(clipListener)

            // Send initial clip if available
            if (rdpInstance != 0L) {
                try {
                    val initialClip = clipboardManager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    val initialText = initialClip?.coerceToText(context)?.toString()
                    if (!initialText.isNullOrEmpty()) {
                        lastSentClipText = initialText
                        rdpBridge.sendClipboardText(rdpInstance, initialText)
                    }
                } catch (e: Exception) {
                    Log.e("RdpClipboard", "Failed to send initial clip: ${e.message}")
                }
            }

            onDispose {
                clipboardManager?.removePrimaryClipChangedListener(clipListener)
                rdpBridge.clipboardListener = null
            }
        }
    }
    if (monitor == null || runtime!!.isOwner(monitor)) {
    certificateRequest?.let { RdpCertificateDialog(it) }
    if (tunnelStatus.isNotEmpty() && !connectionFailed && disconnectedReason == null) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.cloudflared)) },
        text = { Text(tunnelStatus) },
        confirmButton = { TextButton(onClick = closeConnection) { Text(stringResource(R.string.cancel)) } },
    )
    if (connectionFailed) AlertDialog(
        onDismissRequest = closeConnection,
        title = { Text(stringResource(R.string.connection_failed)) },
        text = { Text(connectFailureReason.ifBlank { stringResource(R.string.protocol_connection_failed, protocolName) }) },
        confirmButton = { TextButton(onClick = closeConnection) { Text(stringResource(R.string.close)) } },
    )
    disconnectedReason?.let { reason ->
        ReconnectDialog(
            profileName = session.name,
            reason = reason,
            autoReconnect = autoReconnect,
            onAutoReconnectChanged = { enabled ->
                autoReconnect = enabled
                ProfileManager(context).getProfiles().firstOrNull { it.id == session.id }
                    ?.let { ProfileManager(context).updateProfile(it.copy(reconnectAutomatically = enabled)) }
            },
            onReconnect = { if (runtime != null) runtime.reconnect() else { disconnectedReason = null; connectFailureReason = ""; reconnectAttempt++ } },
            onCancel = { disconnectedReason = null; closeConnection() },
        )
    }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSoftKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(showSoftKeyboard) {
        if (showSoftKeyboard && monitor != null) runtime!!.keyboardOwner.value = monitor.id
    }
    LaunchedEffect(runtime?.keyboardOwner?.value) {
        if (monitor != null && runtime!!.keyboardOwner.value != monitor.id) showSoftKeyboard = false
    }
    val imeVisible = WindowInsets.isImeVisible
    var panelImeVisible by remember { mutableStateOf(false) }
    val keyboardVisible = imeVisible || panelImeVisible
    val keyboardPanelState = metavrx.layout.window.compose.state.rememberWindowState()
    var keyboardInteraction by remember { mutableStateOf(0L) }
    var panelKeyboardActivation by remember { mutableStateOf(0L) }
    var desktopKeyboardActivation by remember { mutableStateOf(0L) }
    SideEffect { (context as? SessionActivity)?.let { it.keyboardPanelState = keyboardPanelState } }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardVisible, showSoftKeyboard, keyboardInteraction) {
        Log.d("RdpKeyboard", "requested=$showSoftKeyboard rootIme=$imeVisible panelIme=$panelImeVisible observed=$keyboardWasVisible")
        if (showSoftKeyboard && keyboardVisible) keyboardWasVisible = true
        if (showSoftKeyboard && keyboardWasVisible && !keyboardVisible) {
            // A key-surface activation can briefly hide the IME while its owner regains focus.
            delay(600)
            showSoftKeyboard = false
            focusRequester.requestFocus()
        }
        if (!showSoftKeyboard) { keyboardWasVisible = false; keyboardInteraction = 0L; desktopKeyboardActivation = 0L; panelKeyboardActivation = 0L }
    }

    
    var zoom by remember { mutableStateOf(1f) }
    var actualWidth by remember { mutableStateOf(1920) }
    var actualHeight by remember { mutableStateOf(1080) }

    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    SideEffect {
        (context as? SessionActivity)?.setZoom = { zoom = it }
        (context as? SessionActivity)?.setPan = { x, y -> panX = x; panY = y }
    }
    var currentWidth by remember { mutableStateOf(0) }
    var currentHeight by remember { mutableStateOf(0) }
    var offsetX by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    var offsetY by remember { mutableStateOf(with(density) { 16.dp.toPx() }) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var showDebugInfo by remember { mutableStateOf(false) }
    var speakerMuted by (runtime?.speakerMuted ?: remember { mutableStateOf<Boolean>(false) })
    var micMuted by (runtime?.micMuted ?: remember { mutableStateOf<Boolean>(false) })
    var micRecording by remember { mutableStateOf(false) }
    val hostCapabilities = remember(session.host) { RdpHostCapabilities(context) }
    var micHostUnsupported by remember(session.host) {
        mutableStateOf(hostCapabilities.microphoneUnsupported(session.host))
    }
    LaunchedEffect(rdpInstance, speakerMuted, micMuted) {
        if (rdpInstance != 0L) rdpBridge.setAudioMuted(rdpInstance, speakerMuted, micMuted)
    }
    LaunchedEffect(rdpInstance) {
        while (rdpInstance != 0L) {
            micRecording = rdpBridge.isMicrophoneRecording(rdpInstance)
            // Actual capture overrides older host evidence, even after recording stops.
            if (micRecording) micHostUnsupported = false
            delay(100)
        }
    }

    var cameraMuted by (runtime?.cameraMuted ?: remember { mutableStateOf<Boolean>(false) })
    var cameraRecording by remember { mutableStateOf(false) }
    var cameraHostUnsupported by remember(session.host) {
        mutableStateOf(hostCapabilities.cameraUnsupported(session.host))
    }
    LaunchedEffect(rdpInstance, cameraMuted) {
        if (rdpInstance != 0L) rdpBridge.setCameraMuted(rdpInstance, cameraMuted)
    }
    LaunchedEffect(rdpInstance) {
        while (rdpInstance != 0L) {
            cameraRecording = rdpBridge.isCameraRecording(rdpInstance)
            if (cameraRecording || rdpBridge.isCameraChannelConnected(rdpInstance)) {
                cameraHostUnsupported = false
            }
            delay(100)
        }
    }

    var isOverlayVisible by remember { mutableStateOf(true) }
    var toolbarPinned by remember { mutableStateOf(false) }
    var toolbarActivity by remember { mutableStateOf(0L) }
    var toolbarBounds by remember { mutableStateOf(Rect.Zero) }
    val remotePointer = remember { intArrayOf(0, 0) }
    DisposableEffect(rdpInstance, showVncFiles) {
        val activity = context as? SessionActivity
        if (showVncFiles) {
            activity?.remoteScroll = null
        } else {
            activity?.remoteScroll = { flags ->
                if (rdpInstance != 0L) rdpBridge.sendMouseEvent(rdpInstance, remotePointer[0], remotePointer[1], flags)
            }
        }
        onDispose { activity?.remoteScroll = null }
    }
    var holdPoint by remember { mutableStateOf<Offset?>(null) }
    var holdStarted by remember { mutableStateOf(0L) }
    var holdProgress by remember { mutableStateOf(0f) }
    val revealToolbar = {
        isOverlayVisible = true
        toolbarActivity += 1L
    }

    val isMediaRecording = micRecording || cameraRecording
    LaunchedEffect(windowInteraction, currentWidth, currentHeight, isMediaRecording) { revealToolbar() }
    val keepToolbarVisible = showDebugInfo || isMediaRecording || runtime?.drag?.armed == true || runtime?.drag?.active == true
    LaunchedEffect(isOverlayVisible, toolbarPinned, toolbarActivity, offsetX, offsetY, keepToolbarVisible) {
        if (isOverlayVisible && !toolbarPinned && !keepToolbarVisible) {
            delay(3000)
            isOverlayVisible = false
        }
    }
    LaunchedEffect(holdStarted) {
        holdProgress = 0f
        if (holdStarted != 0L) {
            while (holdProgress < 1f) {
                withFrameNanos { holdProgress = rightClickBoxProgress(SystemClock.uptimeMillis() - holdStarted) }
            }
        }
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isOverlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 500)
    )

    LaunchedEffect(rdpInstance) {
        while (rdpInstance != 0L) {
            actualWidth = rdpBridge.getDesktopWidth(rdpInstance)
            actualHeight = rdpBridge.getDesktopHeight(rdpInstance)
            delay(200)
        }
    }

    // TextureView fills immediately; ask the server for matching resolution once resizing settles.
    LaunchedEffect(rdpInstance, currentWidth, currentHeight) {
        if (rdpInstance != 0L && currentWidth > 0 && currentHeight > 0) {
            val targetWidth = currentWidth.coerceIn(200, 8192) and -2
            val targetHeight = currentHeight.coerceIn(200, 8192)
            if (monitor != null) {
                rdpBridge.updateDisplay(rdpInstance, targetWidth, targetHeight, 1f)
                return@LaunchedEffect
            }
            delay(300)
            // Windows can ignore a layout while its initial GFX reset is in flight.
            // Stop after acknowledgement; a new window size cancels this whole effect.
            repeat(4) {
                if (actualWidth == targetWidth && actualHeight == targetHeight) return@LaunchedEffect
                rdpBridge.updateDisplay(rdpInstance, targetWidth, targetHeight, 1f)
                delay(1500)
            }
        }
    }
    LaunchedEffect(zoom, currentWidth, currentHeight) {
        val maxX = currentWidth * (zoom - 1f) / 2f
        val maxY = currentHeight * (zoom - 1f) / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }


    val baseTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val desktopPoint = { eventX: Float, eventY: Float ->
        val w = if (currentWidth > 0) currentWidth.toFloat() else 1280f
        val h = if (currentHeight > 0) currentHeight.toFloat() else 800f
        mapScreenToDesktop(
            eventX = eventX,
            eventY = eventY,
            viewWidth = w,
            viewHeight = h,
            zoom = zoom,
            panX = panX,
            panY = panY,
            actualWidth = actualWidth,
            actualHeight = actualHeight
        )
    }

    val input = remember {
        RdpPointerGesture(
            touchSlop = baseTouchSlop,
            send = { x, y, flags ->
                if (rdpInstance != 0L) {
                    val (remoteX, remoteY) = desktopPoint(x, y)
                    remotePointer[0] = remoteX
                    remotePointer[1] = remoteY
                    rdpBridge.sendMouseEvent(rdpInstance, remoteX, remoteY, flags)
                }
            },
            showHold = { point, started ->
                holdPoint = point?.let { Offset(it.first, it.second) }
                holdStarted = started
            },
            startWindowDrag = { x, y ->
                val point = desktopPoint(x, y)
                val activity = context as? SessionActivity
                monitor != null && activity != null && monitor.session.drag.start(activity.window.decorView, monitor, point.first, point.second)
            },
            windowDragEligible = { x,y ->
                if (monitor == null) false else {
                    val r = monitor.rect
                    val peers = monitor.session.monitors.filter { it !== monitor }
                    (x <= 64f && peers.any { it.rect.x < r.x }) ||
                        (x >= currentWidth - 64f && peers.any { it.rect.x > r.x }) ||
                        (y <= 64f && peers.any { it.rect.y < r.y }) ||
                        (y >= currentHeight - 64f && peers.any { it.rect.y > r.y })
                }
            }
        )
    }

    LaunchedEffect(adjusting) { if (adjusting) input.cancel() }

    val dragPoint by rememberUpdatedState(desktopPoint)
    DisposableEffect(monitor) {
        val view = (context as? SessionActivity)?.window?.decorView
        if (monitor != null) view?.setOnDragListener { _, event -> monitor.session.drag.event(monitor, event, dragPoint) }
        onDispose {
            if (monitor != null) { view?.setOnDragListener(null); monitor.session.drag.cancelFor(monitor) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    currentWidth = size.width
                    currentHeight = size.height
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                // Horizon's control-bar Back is local navigation, even while the
                // desktop owns keyboard focus. Let Activity route it to the launcher.
                if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    false
                } else if (rdpInstance != 0L) {
                    val isDown = event.type == KeyEventType.KeyDown
                    val unicode = event.nativeKeyEvent.unicodeChar
                    val scancode = mapAndroidKeyCodeToXt(event.nativeKeyEvent.keyCode)
                    if (rdpBridge is VncBridge) {
                        rdpBridge.sendPhysicalKey(rdpInstance, event.nativeKeyEvent, isDown)
                        true
                    } else if (unicode != 0 && scancode == 0) {
                        rdpBridge.sendUnicodeKeyEvent(rdpInstance, unicode, isDown)
                        true
                    } else if (scancode != 0) {
                        rdpBridge.sendKeyEvent(rdpInstance, scancode, isDown)
                        true
                    } else {
                        // Pass EVDEV scancode as fallback
                        rdpBridge.sendKeyEvent(rdpInstance, event.nativeKeyEvent.scanCode, isDown)
                        true
                    }
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var lastRemoteHover = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            val pos = event.changes.first().position
                            if (toolbarBounds.inflate(48.dp.toPx()).contains(pos)) {
                                revealToolbar()
                            }
                        }
                        // Compose hover is not delivered through pointerInteropFilter's touch
                        // callback. VNC needs it to position its negotiated software cursor.
                        if ((isVnc || runtime?.drag?.carrying == true) && rdpInstance != 0L && event.changes.none { it.pressed } &&
                            (event.type == PointerEventType.Move || event.type == PointerEventType.Enter)) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastRemoteHover >= 16) {
                                lastRemoteHover = now
                                val pos = event.changes.first().position
                                val (x, y) = desktopPoint(pos.x, pos.y)
                                remotePointer[0] = x; remotePointer[1] = y
                                rdpBridge.sendMouseEvent(rdpInstance, x, y, FreeRDPBridge.PTR_FLAGS_MOVE)
                            }
                        }
                    }
                }
            }
    ) {
        key(reconnectAttempt) { AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = panX
                translationY = panY
            },
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    desktopTexture = this
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            currentWidth = width
                            currentHeight = height
                            Log.i("RdpSession", "Texture available ${width}x${height}")
                            connection.attach(Surface(texture))
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            currentWidth = width
                            currentHeight = height
                            Log.i("RdpSession", "Texture resized ${width}x${height}")
                            connection.attach(Surface(texture))
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            input.cancel()
                            connection.detach()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            }
        ) }

        val toolbarMargin = with(density) { 48.dp.toPx() }
        var lastHoverTime by remember { mutableStateOf(0L) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    (context as? SessionActivity)?.onTouchDebug?.invoke(event, zoom, panX, panY)
                    if (toolbarBounds.inflate(toolbarMargin).contains(Offset(event.x, event.y))) {
                        revealToolbar()
                    }
                    if (!showSoftKeyboard) focusRequester.requestFocus()
                    else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        desktopKeyboardActivation = SystemClock.uptimeMillis()
                        keyboardInteraction = desktopKeyboardActivation
                    }
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastHoverTime >= 16) {
                            lastHoverTime = now
                            val (rdpX, rdpY) = desktopPoint(event.x, event.y)
                            if (rdpInstance != 0L) {
                                remotePointer[0] = rdpX
                                remotePointer[1] = rdpY
                                rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, FreeRDPBridge.PTR_FLAGS_MOVE)
                            }
                        }
                        true
                    } else {
                        input.onTouch(event)
                        true
                    }
                }
        )
        
        if (adjusting || adjustmentFade.value > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (adjusting) 1f else adjustmentFade.value }
                .background(Color(0xFF15191E))
                .pointerInteropFilter { true }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                frozenDesktop?.let { bitmap ->
                    Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY
                        })
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = adjustmentDim)))
                val loadingDescription = stringResource(R.string.loading)
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp).alpha(adjustmentDim / .48f)
                        .semantics { contentDescription = loadingDescription })
            }
        }

        holdPoint?.takeIf { holdProgress > 0f }?.let { point ->
            Canvas(Modifier.fillMaxSize()) {
                val half = (8.dp.toPx() + 8.dp.toPx() * holdProgress)
                val corners = listOf(
                    Offset(point.x - half, point.y - half), Offset(point.x + half, point.y - half),
                    Offset(point.x + half, point.y + half), Offset(point.x - half, point.y + half),
                    Offset(point.x - half, point.y - half)
                )
                val path = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    val edges = holdProgress * 4f
                    for (edge in 0..3) {
                        val fraction = (edges - edge).coerceIn(0f, 1f)
                        if (fraction > 0f) {
                            val start = corners[edge]
                            val end = corners[edge + 1]
                            lineTo(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
                        }
                    }
                }
                drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(2.dp.toPx()))
            }
        }
        if (isOverlayVisible || animatedAlpha > 0f) {
        Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopCenter)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .alpha(animatedAlpha)
                .onGloballyPositioned { toolbarBounds = it.boundsInRoot() }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                        revealToolbar()
                    }
                },
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            val iconSize = 18.dp
            val buttonSize = 36.dp
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (monitor != null && runtime != null && runtime.monitors.size > 1) stringResource(R.string.rdp_desktop_number, runtime.monitors.indexOf(monitor)+1) + " - " + session.name else session.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp).padding(horizontal = 6.dp))
                    IconButton(onClick = { toolbarPinned = !toolbarPinned; revealToolbar() }, modifier = Modifier.size(buttonSize)) {
                        Icon(Icons.Default.PushPin, if (toolbarPinned) stringResource(R.string.unpin_toolbar) else stringResource(R.string.keep_toolbar_visible),
                            modifier = Modifier.size(iconSize),
                            tint = if (toolbarPinned) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    if (windowManagerAvailable) IconButton(onClick = {
                        QuestWindowManager.toggle(permissionContext)
                        revealToolbar()
                    }, modifier = Modifier.size(buttonSize)) {
                        Icon(Icons.Default.Dashboard, stringResource(R.string.window_manager), modifier = Modifier.size(iconSize), tint = Color.White)
                    }
                    if (runtime != null && runtime.monitors.size > 1) {
                        val selected = runtime.drag.armed || runtime.drag.carrying
                        IconButton(onClick = { runtime.drag.toggleCarry(); revealToolbar() },
                            enabled = runtime.inputReady && !runtime.busy || selected,
                            modifier = Modifier.size(buttonSize)) {
                            Icon(Icons.Default.PanTool,
                                contentDescription = stringResource(if (selected) R.string.rdp_cancel_carry else R.string.rdp_carry_content),
                                modifier = Modifier.size(iconSize), tint = if (selected) Color(0xFFFFC107) else Color.White)
                        }
                    }
                    if (runtime != null && runtime.supportsAdditionalMonitors) {
                        IconButton(onClick = {
                            (context as? android.app.Activity)?.let { runtime.addWindow(it) }
                            revealToolbar()
                        }, enabled = runtime.canAdd, modifier = Modifier.size(buttonSize)) {
                            Icon(Icons.Default.AddToQueue, stringResource(R.string.rdp_add_desktop), modifier = Modifier.size(iconSize),
                                tint = if (runtime.canAdd) Color.White else Color.Gray)
                        }
                    }
                    if (!isVnc) IconButton(onClick = { speakerMuted = !speakerMuted; revealToolbar() }, modifier = Modifier.size(buttonSize)) {
                        Icon(if (speakerMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            if (speakerMuted) stringResource(R.string.unmute_speakers) else stringResource(R.string.mute_speakers), modifier = Modifier.size(iconSize), tint = Color.White)
                    }
                    if (pairedSshId != null) SftpFilesControl(unsupported = false,
                        onOpen = { showVncFiles = true }, onInteraction = revealToolbar, iconSize = iconSize)
                    if (effectiveSession.redirectMicrophone) {
                        RdpMicrophoneControl(
                            muted = micMuted,
                            recording = micRecording,
                            hostUnsupported = micHostUnsupported,
                            onToggleMute = { micMuted = !micMuted },
                            onInteraction = revealToolbar,
                            iconSize = iconSize,
                        )
                    }
                    if (effectiveSession.enableCamera) {
                        RdpCameraControl(
                            muted = cameraMuted,
                            recording = cameraRecording,
                            hostUnsupported = cameraHostUnsupported,
                            onToggleMute = { cameraMuted = !cameraMuted },
                            onInteraction = revealToolbar,
                            iconSize = iconSize,
                        )
                    }
                    IconButton(onClick = { showZoomSlider = !showZoomSlider }, modifier = Modifier.size(buttonSize)) {
                        Icon(Icons.Default.Search, stringResource(R.string.zoom), modifier = Modifier.size(iconSize), tint = if (showZoomSlider) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = { showDebugInfo = !showDebugInfo; revealToolbar() }, modifier = Modifier.size(buttonSize)) {
                        Icon(Icons.Default.Info, stringResource(R.string.debug_actions), modifier = Modifier.size(iconSize),
                            tint = if (showDebugInfo) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = { revealToolbar(); showSoftKeyboard = !showSoftKeyboard; if (!showSoftKeyboard) { keyboardController?.hide() } }, modifier = Modifier.size(buttonSize)) {
                        Icon(Icons.Default.Keyboard, stringResource(R.string.keyboard), modifier = Modifier.size(iconSize), tint = Color.White)
                    }
                }
                
                if (showZoomSlider) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                    ) {
                        IconButton(
                            onClick = { zoom = 1f; panX = 0f; panY = 0f },
                            enabled = zoom > 1.0f,
                            modifier = Modifier.size(buttonSize)
                        ) {
                            Icon(
                                Icons.Default.FullscreenExit,
                                stringResource(R.string.reset_zoom),
                                modifier = Modifier.size(iconSize),
                                tint = if (zoom > 1.0f) Color.White else Color.Gray
                            )
                        }
                        Slider(
                            value = zoom,
                            onValueChange = { zoom = it },
                            valueRange = 1f..3f,
                            modifier = Modifier.width(224.dp).padding(horizontal = 4.dp)
                        )
                        Text(
                            stringResource(R.string.zoom_percent, (zoom * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
        }
        }
        if (zoom > 1.0f) {
            Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.Center)
                    .size(120.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val maxPanX = (currentWidth * (zoom - 1f)) / 2f
                            val maxPanY = (currentHeight * (zoom - 1f)) / 2f
                            panX = (panX + dragAmount.x).coerceIn(-maxPanX, maxPanX)
                            panY = (panY + dragAmount.y).coerceIn(-maxPanY, maxPanY)
                        }
                    },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.KeyboardArrowUp, "", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(8.dp))
                    Icon(Icons.Default.KeyboardArrowDown, "", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(8.dp))
                    Icon(Icons.Default.KeyboardArrowLeft, "", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(8.dp))
                    Icon(Icons.Default.KeyboardArrowRight, "", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(8.dp))
                    Box(modifier = Modifier.align(androidx.compose.ui.Alignment.Center).size(50.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
        fun sendRdpUnicode(code: Int, down: Boolean) {
            if (rdpInstance == 0L) return
            if (!isVnc && code > 0xFFFF) {
                val high = Character.highSurrogate(code).code
                val low = Character.lowSurrogate(code).code
                rdpBridge.sendUnicodeKeyEvent(rdpInstance, high, down)
                rdpBridge.sendUnicodeKeyEvent(rdpInstance, low, down)
            } else {
                rdpBridge.sendUnicodeKeyEvent(rdpInstance, code, down)
            }
        }

        if (showSoftKeyboard) RemoteImeEditor(
            label = stringResource(R.string.remote_keyboard_input), activation = desktopKeyboardActivation, showInitially = true,
            keyboardVisible = keyboardVisible, onActions = { (context as? SessionActivity)?.keyboardEditorActions = it },
            onFocus = { (context as? SessionActivity)?.keyboardEditorHasFocus = it },
            onView = { (context as? SessionActivity)?.keyboardEditorView = it },
            sendUnicode = { code, down -> sendRdpUnicode(code, down) },
            sendKey = { code, down -> if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down) })

        RemoteKeyboardPanel(visible = showSoftKeyboard,
            state = keyboardPanelState, onInteraction = {
                keyboardInteraction = SystemClock.uptimeMillis()
                panelKeyboardActivation = keyboardInteraction
            }) {
        if (showSoftKeyboard) RemoteImeEditor(
            label = stringResource(R.string.remote_key_panel_input), activation = panelKeyboardActivation, showInitially = false,
            keyboardVisible = keyboardVisible, onActions = { (context as? SessionActivity)?.let { activity -> activity.keyboardPanelEditorActions = it } },
            onImeVisibility = { panelImeVisible = it },
            onFocus = { (context as? SessionActivity)?.let { activity -> activity.keyboardPanelEditorHasFocus = it } },
            onView = { (context as? SessionActivity)?.let { activity -> activity.keyboardPanelEditorView = it } },
            sendUnicode = { code, down -> sendRdpUnicode(code, down) },
            sendKey = { code, down -> if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down) })
        RemoteExtraKeys(
            modifier = Modifier.fillMaxSize(),
            enabled = showSoftKeyboard,
            sendKey = { code, down ->
                if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down)
            }
        )
        }
        if (showVncFiles && pairedSshId != null) Box(Modifier.fillMaxSize()) {
            com.farside.workspace.sftp.SftpFilesEntry(onClose = { showVncFiles = false },
                profileId = pairedSshId, allowDisabledPairedProfile = true)
        }
        if (showDebugInfo) {
            Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .widthIn(max = 380.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xEE141414),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "Connection Info",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        IconButton(
                            onClick = { showDebugInfo = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val protocolLabel = when {
                        !isVnc -> "RDP (FreeRDP 3.x)"
                        effectiveSession.expMacHevc -> "VNC (Apple High-Performance)"
                        else -> "VNC (Standard RFB 3.8)"
                    }
                    val transportLabel = when {
                        effectiveSession.useCloudflare || effectiveSession.proxyType == com.farside.workspace.data.ProxyType.CLOUDFLARED -> "Cloudflare Tunnel (TCP)"
                        effectiveSession.proxyType == com.farside.workspace.data.ProxyType.HTTP -> "HTTP CONNECT Proxy"
                        else -> "Direct TCP (LAN / WARP)"
                    }
                    val decoderLabel = rdpBridge.getDecoderDescription(rdpInstance)
                    val scalePct = (zoom * 100).toInt()
                    val windowW = currentWidth
                    val windowH = currentHeight
                    val serverW = actualWidth
                    val serverH = actualHeight
                    val scaleFactor = if (serverW > 0) "%.2f".format(windowW.toFloat() / serverW) else "1.00"

                    ConnectionInfoRow("Profile", session.name)
                    ConnectionInfoRow("Host:Port", "${session.host}:${session.port}")
                    ConnectionInfoRow("Protocol", protocolLabel)
                    ConnectionInfoRow("Decoder", decoderLabel)
                    ConnectionInfoRow("Transport", transportLabel)
                    ConnectionInfoRow("Server Canvas", "${serverW} × ${serverH}")
                    ConnectionInfoRow("Window Surface", "${windowW} × ${windowH}")
                    ConnectionInfoRow("Scale / Zoom", "${scaleFactor}x (${scalePct}%)")
                    if (isVnc) {
                        ConnectionInfoRow("Mac Auth", "${effectiveSession.vncAuthMode}")
                        ConnectionInfoRow("HEVC Enabled", "${effectiveSession.expMacHevc}")
                        if (effectiveSession.expMacHevc) {
                            ConnectionInfoRow("Share Console", "${effectiveSession.expMacShareConsole}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            value,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
