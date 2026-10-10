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
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Opacity
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


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionWindowLauncher.forgetLegacyPositions(this)
        val profileManager = ProfileManager(this)
        val arrangementStore = com.farside.workspace.data.LauncherArrangementStore(this)
        
        setContent {
            var profiles by remember { mutableStateOf(profileManager.getProfiles()) }
            var arrangement by remember { mutableStateOf(arrangementStore.load()) }

            val refreshProfiles = { profiles = profileManager.getProfiles() }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LauncherScreen(
                        profiles = arrangement.arrange(profiles),
                        onAddProfile = {
                            profileManager.addProfile(it)
                            refreshProfiles()
                        },
                        onDeleteProfile = {
                            profileManager.deleteProfile(it)
                            arrangement = arrangementStore.remove(it)
                            refreshProfiles()
                        },
                        onConnectProfile = { profileId ->
                            SessionWindowLauncher.launch(this@MainActivity, profileId)
                            moveTaskToBack(true)
                        },
                        profileManager = profileManager,
                        starredIds = arrangement.starred,
                        onToggleStar = { arrangement = arrangementStore.toggleStar(it) },
                        onMoveProfile = { profile, target -> arrangement = arrangementStore.move(profiles, profile, target) }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SshSessionScreen(session: ConnectionProfile, windowInteraction: Long = 0L, onClose: () -> Unit) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSoftKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var panelImeVisible by remember { mutableStateOf(false) }
    val keyboardVisible = imeVisible || panelImeVisible
    var extraKeysExpanded by remember { mutableStateOf(false) }
    val keyboardPanelState = metavrx.layout.window.compose.state.rememberWindowState()
    var keyboardInteraction by remember { mutableStateOf(0L) }
    var panelKeyboardActivation by remember { mutableStateOf(0L) }
    var desktopKeyboardActivation by remember { mutableStateOf(0L) }
    SideEffect { (context as? SessionActivity)?.keyboardPanelState = keyboardPanelState }
    var keyboardWasVisible by remember { mutableStateOf(false) }

    LaunchedEffect(keyboardVisible, showSoftKeyboard, keyboardInteraction) {
        if (showSoftKeyboard && keyboardVisible) keyboardWasVisible = true
        if (showSoftKeyboard && keyboardWasVisible && !keyboardVisible) {
            delay(600)
            showSoftKeyboard = false
            focusRequester.requestFocus()
        }
        if (!showSoftKeyboard) {
            keyboardWasVisible = false
            extraKeysExpanded = false
            keyboardInteraction = 0L
            desktopKeyboardActivation = 0L
            panelKeyboardActivation = 0L
        }
    }

    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var shiftLatched by remember { mutableStateOf(false) }

    LaunchedEffect(showSoftKeyboard) {
        if (!showSoftKeyboard) {
            ctrlLatched = false
            altLatched = false
            shiftLatched = false
        }
    }

    val defaultFontSize = 14
    var fontSize by remember { mutableStateOf(defaultFontSize) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var terminalAlpha by remember { mutableStateOf(1.0f) }
    var showTransparencySlider by remember { mutableStateOf(false) }

    var terminalViewRef by remember { mutableStateOf<com.termux.view.TerminalView?>(null) }
    var sshSessionRef by remember { mutableStateOf<com.termux.terminal.SshTerminalSession?>(null) }

    SideEffect {
        (context as? SessionActivity)?.setZoom = { factor ->
            val size = (defaultFontSize * factor).roundToInt().coerceIn(8, 36)
            fontSize = size
            terminalViewRef?.setTextSize(size)
        }
    }

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var isOverlayVisible by remember { mutableStateOf(true) }
    var toolbarPinned by remember { mutableStateOf(false) }
    var toolbarActivity by remember { mutableStateOf(0L) }
    var toolbarBounds by remember { mutableStateOf(Rect.Zero) }

    val revealToolbar = {
        isOverlayVisible = true
        toolbarActivity += 1L
    }

    LaunchedEffect(windowInteraction, fontSize) { revealToolbar() }
    LaunchedEffect(isOverlayVisible, toolbarPinned, toolbarActivity, offsetX, offsetY) {
        if (isOverlayVisible && !toolbarPinned) {
            delay(3000)
            isOverlayVisible = false
        }
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isOverlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 500)
    )

    DisposableEffect(Unit) {
        onDispose {
            sshSessionRef?.finishIfRunning()
        }
    }

    fun handleSshUnicode(char: Int, down: Boolean) {
        if (!down) return
        var code = char
        if (ctrlLatched) {
            code = when {
                code in 'a'.code..'z'.code -> code - 'a'.code + 1
                code in 'A'.code..'Z'.code -> code - 'A'.code + 1
                code == '@'.code || code == ' '.code -> 0
                code == '['.code -> 27
                code == '\\'.code -> 28
                code == ']'.code -> 29
                code == '^'.code -> 30
                code == '_'.code -> 31
                code == '?'.code -> 127
                else -> code
            }
        } else if (shiftLatched) {
            if (code in 'a'.code..'z'.code) {
                code = Character.toUpperCase(code)
            }
        }
        if (Character.isValidCodePoint(code) && (code < Character.MIN_SURROGATE.code || code > Character.MAX_SURROGATE.code)) {
            sshSessionRef?.writeCodePoint(altLatched, code)
        }
    }

    fun handleSshKey(code: Int, down: Boolean) {
        when (code) {
            0x1D -> { ctrlLatched = down } // CTRL
            0x38, 0x138 -> { altLatched = down } // ALT / AltGr
            0x2A -> { shiftLatched = down } // SHIFT
            0x15B -> {} // WIN
            else -> {
                if (down) {
                    val escapeSeq = when (code) {
                        0x01 -> "\u001b" // ESC
                        0x0F -> "\t"     // TAB
                        0x0E -> "\u007f" // Backspace
                        0x1C, 0x11C -> "\r" // Enter, Keypad Enter
                        0x153 -> "\u001b[3~" // Delete
                        0x152 -> "\u001b[2~" // Insert
                        0x147 -> "\u001b[H"  // Home
                        0x14F -> "\u001b[F"  // End
                        0x149 -> "\u001b[5~" // PgUp
                        0x151 -> "\u001b[6~" // PgDn
                        0x148 -> "\u001b[A"  // Up
                        0x150 -> "\u001b[B"  // Down
                        0x14D -> "\u001b[C"  // Right
                        0x14B -> "\u001b[D"  // Left
                        // Function keys F1-F12
                        0x3B -> "\u001bOP"
                        0x3C -> "\u001bOQ"
                        0x3D -> "\u001bOR"
                        0x3E -> "\u001bOS"
                        0x3F -> "\u001b[15~"
                        0x40 -> "\u001b[17~"
                        0x41 -> "\u001b[18~"
                        0x42 -> "\u001b[19~"
                        0x43 -> "\u001b[20~"
                        0x44 -> "\u001b[21~"
                        0x57 -> "\u001b[23~"
                        0x58 -> "\u001b[24~"
                        // Numeric and arithmetic keys
                        0x08 -> "7"
                        0x09 -> "8"
                        0x0A -> "9"
                        0x05 -> "4"
                        0x06 -> "5"
                        0x07 -> "6"
                        0x02 -> "1"
                        0x03 -> "2"
                        0x04 -> "3"
                        0x0B -> "0"
                        0x34 -> "."
                        0x135 -> "/"
                        0x37 -> "*"
                        0x4A -> "-"
                        0x4E -> "+"
                        else -> null
                    }
                    if (escapeSeq != null) {
                        sshSessionRef?.write(escapeSeq)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = terminalAlpha))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            revealToolbar()
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .alpha(terminalAlpha),
            factory = { viewContext ->
                val terminalView = com.termux.view.TerminalView(viewContext, null)
                terminalView.setTextSize(fontSize)

                val copyTextToClipboard: (String) -> Unit = { text ->
                    try {
                        val clipboard = viewContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("ssh-clipboard", text))
                    } catch (e: Exception) {
                        Log.e("SshSession", "Error copying text to clipboard: ${e.message}")
                    }
                }

                val sessionClient = object : com.termux.terminal.TerminalSessionClient {
                    override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
                        terminalView.onScreenUpdated()
                    }
                    override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
                    override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {
                        onClose()
                    }
                    override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {
                        copyTextToClipboard(text)
                    }
                    override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {
                        try {
                            val clipboard = viewContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                            val text = clip?.coerceToText(viewContext)?.toString()
                            if (!text.isNullOrEmpty()) {
                                (session ?: sshSessionRef)?.let { targetSession ->
                                    val bytes = text.toByteArray(Charsets.UTF_8)
                                    targetSession.write(bytes, 0, bytes.size)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SshSession", "Error pasting text from clipboard: ${e.message}")
                        }
                    }
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
                    override fun onSingleTapUp(e: android.view.MotionEvent) {
                        revealToolbar()
                    }
                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                    override fun shouldEnforceCharBasedInput(): Boolean = false
                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                    override fun isTerminalViewSelected(): Boolean = true
                    override fun copyModeChanged(copyMode: Boolean) {
                        if (session.autoCopySelection && copyMode) {
                            val text = terminalView.selectedText
                            if (!text.isNullOrEmpty()) {
                                copyTextToClipboard(text)
                            }
                        }
                    }
                    override fun onSelectedTextChanged(text: String?) {
                        if (session.autoCopySelection && !text.isNullOrEmpty()) {
                            copyTextToClipboard(text)
                        }
                    }
                    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: com.termux.terminal.TerminalSession): Boolean = false
                    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
                    override fun onLongPress(event: android.view.MotionEvent): Boolean = false
                    override fun readControlKey(): Boolean = ctrlLatched
                    override fun readAltKey(): Boolean = altLatched
                    override fun readShiftKey(): Boolean = shiftLatched
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

                val sshTerm = com.termux.terminal.SshTerminalSession(session, sessionClient, viewContext)
                terminalView.attachSession(sshTerm)

                terminalViewRef = terminalView
                sshSessionRef = sshTerm

                terminalView
            }
        )

        // Floating Movable Disappearing Menu Bar
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
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp)) {
                            Text(session.name, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(session.host, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { toolbarPinned = !toolbarPinned; revealToolbar() }) {
                            Icon(
                                Icons.Default.PushPin,
                                if (toolbarPinned) "Unpin toolbar" else "Keep toolbar visible",
                                tint = if (toolbarPinned) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        IconButton(onClick = {
                            showZoomSlider = !showZoomSlider
                            if (showZoomSlider) showTransparencySlider = false
                            revealToolbar()
                        }) {
                            Icon(Icons.Default.Search, "Zoom", tint = Color.White)
                        }
                        if (fontSize != defaultFontSize) {
                            IconButton(onClick = {
                                fontSize = defaultFontSize
                                terminalViewRef?.setTextSize(defaultFontSize)
                                revealToolbar()
                            }) {
                                Icon(Icons.Default.FullscreenExit, "Reset Zoom", tint = Color.White)
                            }
                        }
                        IconButton(onClick = {
                            showTransparencySlider = !showTransparencySlider
                            if (showTransparencySlider) showZoomSlider = false
                            revealToolbar()
                        }) {
                            Icon(
                                Icons.Default.Opacity,
                                "Transparency",
                                tint = if (terminalAlpha < 1.0f) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        IconButton(onClick = {
                            revealToolbar()
                            showSoftKeyboard = !showSoftKeyboard
                            if (!showSoftKeyboard) { keyboardController?.hide() }
                        }) {
                            Icon(Icons.Default.Keyboard, "Keyboard", tint = if (showSoftKeyboard) MaterialTheme.colorScheme.primary else Color.White)
                        }
                    }

                    if (showZoomSlider) {
                        Text("${fontSize}sp", color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { newSize ->
                                val rounded = newSize.roundToInt()
                                if (rounded != fontSize) {
                                    fontSize = rounded
                                    terminalViewRef?.setTextSize(rounded)
                                }
                                revealToolbar()
                            },
                            valueRange = 8f..32f,
                            modifier = Modifier.width(150.dp)
                        )
                    }

                    if (showTransparencySlider) {
                        Text("${(terminalAlpha * 100).roundToInt()}%", color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
                        Slider(
                            value = terminalAlpha,
                            onValueChange = { newAlpha ->
                                terminalAlpha = newAlpha.coerceIn(0.2f, 1.0f)
                                revealToolbar()
                            },
                            valueRange = 0.2f..1.0f,
                            modifier = Modifier.width(150.dp)
                        )
                    }
                }
            }
        }

        // Soft Keyboard Input Handlers
        if (showSoftKeyboard) {
            RemoteImeEditor(
                label = "SSH keyboard input",
                activation = desktopKeyboardActivation,
                showInitially = true,
                keyboardVisible = keyboardVisible,
                onActions = { (context as? SessionActivity)?.keyboardEditorActions = it },
                onFocus = { (context as? SessionActivity)?.keyboardEditorHasFocus = it },
                onView = { (context as? SessionActivity)?.keyboardEditorView = it },
                sendUnicode = { code, down -> handleSshUnicode(code, down) },
                sendKey = { code, down -> handleSshKey(code, down) }
            )
        }

        // Extra Keys Panel (anchored to bottom, expands to 314.dp and contracts to 76.dp)
        RemoteKeyboardPanel(
            visible = showSoftKeyboard,
            expanded = extraKeysExpanded,
            state = keyboardPanelState,
            onInteraction = {
                panelKeyboardActivation = SystemClock.uptimeMillis()
                keyboardInteraction = panelKeyboardActivation
            }
        ) {
            if (showSoftKeyboard) {
                RemoteImeEditor(
                    label = "SSH key panel input",
                    activation = panelKeyboardActivation,
                    showInitially = false,
                    keyboardVisible = keyboardVisible,
                    onActions = { (context as? SessionActivity)?.keyboardPanelEditorActions = it },
                    onImeVisibility = { panelImeVisible = it },
                    onFocus = { (context as? SessionActivity)?.keyboardPanelEditorHasFocus = it },
                    onView = { (context as? SessionActivity)?.keyboardPanelEditorView = it },
                    sendUnicode = { code, down -> handleSshUnicode(code, down) },
                    sendKey = { code, down -> handleSshKey(code, down) }
                )
            }
            RemoteExtraKeys(
                modifier = Modifier.fillMaxSize(),
                enabled = showSoftKeyboard,
                expanded = extraKeysExpanded,
                onExpandedChange = { extraKeysExpanded = it },
                sendKey = { code, down -> handleSshKey(code, down) }
            )
        }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun RdpSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit, windowInteraction: Long = 0L, onBridgeCreated: ((FreeRDPBridge) -> Unit)? = null) {
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

    var cameraReady by remember(session.id) {
        mutableStateOf(!session.enableCamera || hasCameraPermission(permissionContext))
    }
    var cameraAllowed by remember(session.id) {
        mutableStateOf(session.enableCamera && hasCameraPermission(permissionContext))
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
        mutableStateOf(!session.redirectStorage || hasStoragePermission(permissionContext))
    }
    var storageAllowed by remember(session.id) {
        mutableStateOf(session.redirectStorage && hasStoragePermission(permissionContext))
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
            !microphoneReady -> "Waiting for microphone permission…"
            !cameraReady -> "Waiting for camera permission…"
            else -> "Waiting for storage permission…"
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
    val rdpBridge = remember { FreeRDPBridge().also { onBridgeCreated?.invoke(it) } }
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

    // Bidirectional RDP Clipboard Synchronization
    if (session.redirectClipboard) {
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
                            clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("rdp-clipboard", remoteText))
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
    var panelImeVisible by remember { mutableStateOf(false) }
    val keyboardVisible = imeVisible || panelImeVisible
    var extraKeysExpanded by remember { mutableStateOf(false) }
    val keyboardPanelState = metavrx.layout.window.compose.state.rememberWindowState()
    var keyboardInteraction by remember { mutableStateOf(0L) }
    var panelKeyboardActivation by remember { mutableStateOf(0L) }
    var desktopKeyboardActivation by remember { mutableStateOf(0L) }
    SideEffect { (context as? SessionActivity)?.keyboardPanelState = keyboardPanelState }
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
        if (!showSoftKeyboard) { keyboardWasVisible = false; extraKeysExpanded = false; keyboardInteraction = 0L; desktopKeyboardActivation = 0L; panelKeyboardActivation = 0L }
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

    var cameraMuted by remember { mutableStateOf(false) }
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
            if (cameraRecording) {
                cameraHostUnsupported = false
            } else if (rdpBridge.isCameraUnsupported(rdpInstance)) {
                cameraHostUnsupported = true
            }
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

    val isMediaRecording = micRecording || cameraRecording
    LaunchedEffect(windowInteraction, currentWidth, currentHeight, isMediaRecording) { revealToolbar() }
    LaunchedEffect(isOverlayVisible, toolbarPinned, toolbarActivity, offsetX, offsetY, isMediaRecording) {
        if (isOverlayVisible && !toolbarPinned && !isMediaRecording) {
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
            }
        )
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
                    if (effectiveSession.enableCamera) {
                        RdpCameraControl(
                            muted = cameraMuted,
                            recording = cameraRecording,
                            hostUnsupported = cameraHostUnsupported,
                            onToggleMute = { cameraMuted = !cameraMuted },
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
        fun sendRdpUnicode(code: Int, down: Boolean) {
            if (rdpInstance == 0L) return
            if (code > 0xFFFF) {
                val high = Character.highSurrogate(code).code
                val low = Character.lowSurrogate(code).code
                rdpBridge.sendUnicodeKeyEvent(rdpInstance, high, down)
                rdpBridge.sendUnicodeKeyEvent(rdpInstance, low, down)
            } else {
                rdpBridge.sendUnicodeKeyEvent(rdpInstance, code, down)
            }
        }

        if (showSoftKeyboard) RemoteImeEditor(
            label = "Remote keyboard input", activation = desktopKeyboardActivation, showInitially = true,
            keyboardVisible = keyboardVisible, onActions = { (context as? SessionActivity)?.keyboardEditorActions = it },
            onFocus = { (context as? SessionActivity)?.keyboardEditorHasFocus = it },
            onView = { (context as? SessionActivity)?.keyboardEditorView = it },
            sendUnicode = { code, down -> sendRdpUnicode(code, down) },
            sendKey = { code, down -> if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down) })

        RemoteKeyboardPanel(visible = showSoftKeyboard, expanded = extraKeysExpanded,
            state = keyboardPanelState, onInteraction = {
                panelKeyboardActivation = SystemClock.uptimeMillis()
                keyboardInteraction = panelKeyboardActivation
            }) {
        if (showSoftKeyboard) RemoteImeEditor(
            label = "Remote key panel input", activation = panelKeyboardActivation, showInitially = false,
            keyboardVisible = keyboardVisible, onActions = { (context as? SessionActivity)?.keyboardPanelEditorActions = it },
            onImeVisibility = { panelImeVisible = it },
            onFocus = { (context as? SessionActivity)?.keyboardPanelEditorHasFocus = it },
            onView = { (context as? SessionActivity)?.keyboardPanelEditorView = it },
            sendUnicode = { code, down -> sendRdpUnicode(code, down) },
            sendKey = { code, down -> if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down) })
        RemoteExtraKeys(
            modifier = Modifier.fillMaxSize(),
            enabled = showSoftKeyboard,
            expanded = extraKeysExpanded,
            onExpandedChange = { extraKeysExpanded = it },
            sendKey = { code, down ->
                if (rdpInstance != 0L) rdpBridge.sendKeyEvent(rdpInstance, code, down)
            }
        )
        }
    }
}
