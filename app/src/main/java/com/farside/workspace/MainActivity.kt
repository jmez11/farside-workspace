package com.farside.workspace

import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Keyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType

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


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileManager = ProfileManager(this)
        
        setContent {
            var profiles by remember { mutableStateOf(profileManager.getProfiles()) }

            val refreshProfiles = { profiles = profileManager.getProfiles() }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LauncherScreen(
                        profiles = profiles,
                        onAddProfile = {
                            profileManager.addProfile(it)
                            refreshProfiles()
                        },
                        onDeleteProfile = {
                            profileManager.deleteProfile(it)
                            refreshProfiles()
                        },
                        onConnectProfile = { profileId ->
                            SessionWindowLauncher.launch(this@MainActivity, profileId)
                        },
                        profileManager = profileManager
                    )
                }
            }
        }
    }
}

@Composable
fun SshSessionScreen(session: ConnectionProfile, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val terminalView = com.termux.view.TerminalView(viewContext, null)
                
                val sessionClient = object : com.termux.terminal.TerminalSessionClient {
                    override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
                        terminalView.onScreenUpdated()
                    }
                    override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
                    override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {
                        onClose()
                    }
                    override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
                    override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
                    override fun onBell(session: com.termux.terminal.TerminalSession) {}
                    override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
                    override fun onTerminalCursorStateChange(state: Boolean) {}
                    override fun setTerminalShellPid(session: com.termux.terminal.TerminalSession, pid: Int) {}
                    override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
                    override fun logError(tag: String, message: String) { Log.e(tag, message) }
                    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
                    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
                    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
                    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
                    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stacktrace", e) }
                }

                val viewClient = object : com.termux.view.TerminalViewClient {
                    override fun onScale(scale: Float): Float = 1.0f
                    override fun onSingleTapUp(e: android.view.MotionEvent) {}
                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                    override fun shouldEnforceCharBasedInput(): Boolean = false
                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                    override fun isTerminalViewSelected(): Boolean = true
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: com.termux.terminal.TerminalSession): Boolean = false
                    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
                    override fun onLongPress(event: android.view.MotionEvent): Boolean = false
                    override fun readControlKey(): Boolean = false
                    override fun readAltKey(): Boolean = false
                    override fun readShiftKey(): Boolean = false
                    override fun readFnKey(): Boolean = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession): Boolean = false
                    override fun onEmulatorSet() {}
                    override fun logError(tag: String, message: String) { Log.e(tag, message) }
                    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
                    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
                    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
                    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
                    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stacktrace", e) }
                }

                terminalView.setTerminalViewClient(viewClient)
                
                val sshTerm = com.termux.terminal.SshTerminalSession(session, sessionClient)
                terminalView.attachSession(sshTerm)
                
                terminalView
            }
        )
    }
}


fun mapAndroidKeyCodeToXt(keyCode: Int): Int {
    return when(keyCode) {
        67 -> 0x0E // DEL/Backspace
        66 -> 0x1C // ENTER
        19 -> 0x148 // UP
        20 -> 0x150 // DOWN
        21 -> 0x14B // LEFT
        22 -> 0x14D // RIGHT
        61 -> 0x0F // TAB
        111 -> 0x01 // ESC
        113 -> 0x1D // CTRL
        57 -> 0x38 // ALT
        59 -> 0x2A // SHIFT
        62 -> 0x39 // SPACE
        else -> 0
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RdpSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit, windowInteraction: Long = 0L) {
    val permissionContext = LocalContext.current
    var microphoneReady by remember(session.id) {
        mutableStateOf(!session.redirectMicrophone || hasMicrophonePermission(permissionContext))
    }
    var microphoneAllowed by remember(session.id) {
        mutableStateOf(session.redirectMicrophone && hasMicrophonePermission(permissionContext))
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
    if (!microphoneReady) {
        Text("Waiting for microphone permission?", color = Color.White)
        return
    }
    val effectiveSession = remember(session, microphoneAllowed) {
        session.copy(redirectMicrophone = microphoneAllowed && hasMicrophonePermission(permissionContext))
    }
    val rdpBridge = remember { FreeRDPBridge() }
    var rdpInstance by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    var certificateRequest by remember { mutableStateOf<RdpCertificateRequest?>(null) }
    var connectionFailed by remember { mutableStateOf(false) }
    var connectFailureReason by remember { mutableStateOf("") }
    var disconnectedReason by remember { mutableStateOf<String?>(null) }
    var tunnelStatus by remember { mutableStateOf("") }
    val certificates = remember(session.id) {
        RdpCertificateVerifier(context, session.host, session.port, { certificateRequest = it })
    }
    val connection = remember(session.id) {
        RdpSessionConnection(context, effectiveSession, rdpBridge, certificates, onDisconnected = { reason, established ->
            if (established) disconnectedReason = reason else connectFailureReason = reason
        }, onStatus = { tunnelStatus = it }) {
            rdpInstance = it
            connectionFailed = it == 0L
            onInstanceCreated(it)
        }
    }
    DisposableEffect(connection) {
        onDispose {
            rdpInstance = 0L
            connection.close()
        }
    }
    certificateRequest?.let { RdpCertificateDialog(it) }
    if (tunnelStatus.isNotEmpty() && !connectionFailed && disconnectedReason == null) AlertDialog(
        onDismissRequest = {},
        title = { Text("Cloudflared") },
        text = { Text(tunnelStatus) },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
    if (connectionFailed) AlertDialog(
        onDismissRequest = { (context as? android.app.Activity)?.finish() },
        title = { Text("Connection failed") },
        text = { Text(connectFailureReason.ifBlank { "The RDP connection could not be established or its certificate was not accepted." }) },
        confirmButton = { TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text("Close") } },
    )
    disconnectedReason?.let { reason ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Disconnected") },
            text = { Text(if (reason.isBlank()) "The RDP session has disconnected." else "The RDP session has disconnected.\n\n$reason") },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSoftKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, showSoftKeyboard) {
        Log.d("RdpKeyboard", "requested=$showSoftKeyboard visible=$imeVisible observed=$keyboardWasVisible")
        if (showSoftKeyboard && imeVisible) keyboardWasVisible = true
        if (showSoftKeyboard && keyboardWasVisible && !imeVisible) {
            showSoftKeyboard = false
            focusRequester.requestFocus()
        }
        if (!showSoftKeyboard) keyboardWasVisible = false
    }

    
    var zoom by remember { mutableStateOf(1f) }
    var actualWidth by remember { mutableStateOf(1920) }
    var actualHeight by remember { mutableStateOf(1080) }

    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var currentWidth by remember { mutableStateOf(0) }
    var currentHeight by remember { mutableStateOf(0) }
    var offsetX by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    var offsetY by remember { mutableStateOf(with(density) { 16.dp.toPx() }) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var speakerMuted by remember { mutableStateOf(false) }
    var micMuted by remember { mutableStateOf(false) }
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

    var isOverlayVisible by remember { mutableStateOf(true) }
    var toolbarPinned by remember { mutableStateOf(false) }
    var toolbarActivity by remember { mutableStateOf(0L) }
    var toolbarBounds by remember { mutableStateOf(Rect.Zero) }
    val remotePointer = remember { intArrayOf(0, 0) }
    DisposableEffect(rdpInstance) {
        val activity = context as? SessionActivity
        activity?.remoteScroll = { flags ->
            if (rdpInstance != 0L) rdpBridge.sendMouseEvent(rdpInstance, remotePointer[0], remotePointer[1], flags)
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

    LaunchedEffect(windowInteraction, currentWidth, currentHeight) { revealToolbar() }
    LaunchedEffect(isOverlayVisible, toolbarPinned, toolbarActivity, offsetX, offsetY) {
        if (isOverlayVisible && !toolbarPinned) {
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
            delay(300)
            val targetWidth = currentWidth.coerceIn(200, 8192) and -2
            val targetHeight = currentHeight.coerceIn(200, 8192)
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


    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (rdpInstance != 0L) {
                    val isDown = event.type == KeyEventType.KeyDown
                    val unicode = event.nativeKeyEvent.unicodeChar
                    val scancode = mapAndroidKeyCodeToXt(event.nativeKeyEvent.keyCode)
                    if (unicode != 0 && scancode == 0) {
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
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            val pos = event.changes.first().position
                            if (toolbarBounds.inflate(48.dp.toPx()).contains(pos)) {
                                revealToolbar()
                            }
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = panX
                translationY = panY
            },
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    var lastMouseEventTime = 0L
                    
                    // TextureView stretches the complete desktop texture to its current bounds.
                    // MotionEvent coordinates are already local to the transformed AndroidView.
                    fun desktopPoint(event: MotionEvent): Pair<Int, Int> {
                        val x = (event.x * actualWidth / width.coerceAtLeast(1)).toInt()
                        val y = (event.y * actualHeight / height.coerceAtLeast(1)).toInt()
                        return x.coerceIn(0, actualWidth - 1) to y.coerceIn(0, actualHeight - 1)
                    }

                    val input = RdpPointerGesture(
                        touchSlop = ViewConfiguration.get(viewContext).scaledTouchSlop.toFloat(),
                        send = { x, y, flags ->
                            if (rdpInstance != 0L) {
                                val remoteX = (x * actualWidth / width.coerceAtLeast(1)).toInt().coerceIn(0, actualWidth - 1)
                                val remoteY = (y * actualHeight / height.coerceAtLeast(1)).toInt().coerceIn(0, actualHeight - 1)
                                remotePointer[0] = remoteX
                                remotePointer[1] = remoteY
                                rdpBridge.sendMouseEvent(rdpInstance, remoteX, remoteY, flags)
                            }
                        },
                        showHold = { point, started ->
                            holdPoint = point?.let {
                                Offset(it.first * zoom + width * (1f - zoom) / 2f + panX,
                                    it.second * zoom + height * (1f - zoom) / 2f + panY)
                            }
                            holdStarted = started
                        }
                    )
                    setOnTouchListener { _, event ->
                        // Keep the IME text editor focused while clicking/dragging the remote desktop.
                        if (!showSoftKeyboard) focusRequester.requestFocus()
                        input.onTouch(event)
                        true
                    }

                    setOnHoverListener { view, event ->
                        val now = System.currentTimeMillis()
                        if (now - lastMouseEventTime < 16) return@setOnHoverListener true
                        lastMouseEventTime = now

                        val (rdpX, rdpY) = desktopPoint(event)

                        if (rdpInstance != 0L) {
                            remotePointer[0] = rdpX
                            remotePointer[1] = rdpY
                            rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, FreeRDPBridge.PTR_FLAGS_MOVE)
                        }
                        true
                    }
                    
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
        )
        
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
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp)) {
                        Text(session.name, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(session.host, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { toolbarPinned = !toolbarPinned; revealToolbar() }) {
                        Icon(Icons.Default.PushPin, if (toolbarPinned) "Unpin toolbar" else "Keep toolbar visible",
                            tint = if (toolbarPinned) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = { speakerMuted = !speakerMuted; revealToolbar() }) {
                        Icon(if (speakerMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            if (speakerMuted) "Unmute speakers" else "Mute speakers", tint = Color.White)
                    }
                    if (effectiveSession.redirectMicrophone) {
                        RdpMicrophoneControl(
                            muted = micMuted,
                            recording = micRecording,
                            hostUnsupported = micHostUnsupported,
                            onToggleMute = { micMuted = !micMuted },
                            onInteraction = revealToolbar,
                        )
                    }
                    IconButton(onClick = { showZoomSlider = !showZoomSlider }) {
                        Icon(Icons.Default.Search, "Zoom", tint = Color.White)
                    }
                    if (zoom > 1.0f) {
                        IconButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) {
                            Icon(Icons.Default.FullscreenExit, "Reset Zoom", tint = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                    IconButton(onClick = { /* Implement menu later */ }) {
                        Icon(Icons.Default.Menu, "Menu", tint = androidx.compose.ui.graphics.Color.White)
                    }
                    IconButton(onClick = { revealToolbar(); showSoftKeyboard = !showSoftKeyboard; if (!showSoftKeyboard) { keyboardController?.hide() } }) {
                        Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White)
                    }
                }
                
                if (showZoomSlider) {
                    Text("${(zoom * 100).toInt()}%", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(bottom = 4.dp))
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 1f..3f,
                        modifier = Modifier.width(150.dp)
                    )
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
        if (showSoftKeyboard) {
        val softKeyboardFocus = remember { FocusRequester() }
        var textValue by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
        
        if (imeVisible) RemoteExtraKeys(
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth(),
            sendKey = { code, down ->
                if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down)
            },
            rightClick = {
                if (rdpInstance != 0L) {
                    val x = remotePointer[0].coerceIn(0, actualWidth - 1)
                    val y = remotePointer[1].coerceIn(0, actualHeight - 1)
                    rdpBridge.sendMouseEvent(rdpInstance, x, y, FreeRDPBridge.PTR_FLAGS_BUTTON2 or FreeRDPBridge.PTR_FLAGS_DOWN)
                    rdpBridge.sendMouseEvent(rdpInstance, x, y, FreeRDPBridge.PTR_FLAGS_BUTTON2)
                }
            }
        )

        androidx.compose.foundation.text.BasicTextField(
            value = textValue,
            onValueChange = { newVal ->
                if (newVal.text.length > textValue.text.length && rdpInstance != 0L) {
                    val char = newVal.text.last()
                    rdpBridge.sendUnicodeKeyEvent(rdpInstance, char.code, true)
                    rdpBridge.sendUnicodeKeyEvent(rdpInstance, char.code, false)
                } else if (newVal.text.length < textValue.text.length && rdpInstance != 0L) {
                    rdpBridge.sendKeyEvent(rdpInstance, 0x0E, true) // Backspace
                    rdpBridge.sendKeyEvent(rdpInstance, 0x0E, false)
                }
                textValue = TextFieldValue(" ", selection = TextRange(1))
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.size(1.dp).focusRequester(softKeyboardFocus)
                .onFocusChanged { Log.d("RdpKeyboard", "editor focused=${it.isFocused} hasFocus=${it.hasFocus}") }
                .semantics { contentDescription = "Remote keyboard input" }
        )
        LaunchedEffect(Unit) {
            softKeyboardFocus.requestFocus()
            keyboardController?.show()
        }
    }

}
}
