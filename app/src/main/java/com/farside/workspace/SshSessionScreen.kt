package com.farside.workspace

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.material.icons.filled.Dashboard
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Opacity
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


@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SshSessionScreen(session: ConnectionProfile, windowInteraction: Long = 0L, onClose: () -> Unit) {
    val context = LocalContext.current
    val scrollbackValidationMsg = stringResource(R.string.scrollback_lines_validation)
    val sshClipboardLabel = stringResource(R.string.ssh_clipboard_label)
    val windowManagerAvailable = remember(context) { QuestWindowManager.isAvailable(context) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSoftKeyboard by remember { mutableStateOf(false) }
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
        if (showSoftKeyboard && keyboardVisible) keyboardWasVisible = true
        if (showSoftKeyboard && keyboardWasVisible && !keyboardVisible) {
            delay(600)
            showSoftKeyboard = false
            focusRequester.requestFocus()
        }
        if (!showSoftKeyboard) {
            keyboardWasVisible = false
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
    var fontSize by remember { mutableStateOf(session.terminalFontSize) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var terminalAlpha by remember { mutableStateOf(session.terminalOpacity) }
    var showTransparencySlider by remember { mutableStateOf(false) }

    var terminalViewRef by remember { mutableStateOf<com.termux.view.TerminalView?>(null) }
    var sshSessionRef by remember { mutableStateOf<com.termux.terminal.SshTerminalSession?>(null) }
    val profileManager = remember { ProfileManager(context) }
    var forwards by remember { mutableStateOf(session.sshForwards) }
    var forwardStatuses by remember { mutableStateOf(emptyMap<String, String>()) }
    var showForwards by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var sshHasConnected by remember { mutableStateOf(false) }
    var sshDisconnected by remember { mutableStateOf(false) }
    var sshReconnectAttempt by remember { mutableIntStateOf(0) }
    var autoReconnect by remember(session.id) { mutableStateOf(session.reconnectAutomatically) }
    // null = not checked yet (no icon); true = SFTP works; false = enabled but unsupported.
    var sftpSupported by remember { mutableStateOf<Boolean?>(null) }
    var showTerminalSettings by remember { mutableStateOf(false) }
    var scrollback by remember { mutableStateOf(session.terminalScrollback) }
    var scrollbackText by remember { mutableStateOf(scrollback.toString()) }
    var scrollbackError by remember { mutableStateOf<String?>(null) }
    fun saveTerminalSettings() {
        profileManager.getProfiles().find { it.id == session.id }?.let {
            profileManager.updateProfile(it.copy(terminalFontSize = fontSize, terminalOpacity = terminalAlpha,
                terminalScrollback = scrollback))
        }
    }
    LaunchedEffect(fontSize, terminalAlpha) { delay(250); saveTerminalSettings() }
    if (showForwards) com.farside.workspace.ui.SshForwardingDialog(forwards, forwardStatuses,
        onChange = { next ->
            profileManager.getProfiles().find { it.id == session.id }?.let {
                profileManager.updateProfile(it.copy(sshForwards = next))
            }
            forwards = next
            sshSessionRef?.forwarding?.update(next)
        }, onDismiss = { showForwards = false })
    if (showTerminalSettings) AlertDialog(onDismissRequest = { showTerminalSettings = false },
        title = { Text(stringResource(R.string.terminal_settings)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scrollback_help))
                OutlinedTextField(scrollbackText, { scrollbackText = it; scrollbackError = null },
                    label = { Text(stringResource(R.string.scrollback_lines_hint)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Text(stringResource(R.string.terminal_font_opacity, fontSize, (terminalAlpha * 100).roundToInt()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.terminal_settings_saved_help), style = MaterialTheme.typography.bodySmall)
                scrollbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, dismissButton = { TextButton(onClick = { showTerminalSettings = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            val count = scrollbackText.toIntOrNull()
            if (count == null || count !in 100..50000) scrollbackError = scrollbackValidationMsg
            else {
                scrollback = count
                terminalViewRef?.mEmulator?.setScrollbackRows(count)
                terminalViewRef?.onScreenUpdated()
                saveTerminalSettings()
                showTerminalSettings = false
            }
        }) { Text(stringResource(R.string.save_settings)) } })


    SideEffect {
        (context as? SessionActivity)?.setZoom = { factor ->
            val size = (defaultFontSize * factor).roundToInt().coerceIn(8, 32)
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
            saveTerminalSettings()
            (context as? SessionActivity)?.sshHistoryScroll = null
            (context as? SessionActivity)?.sshCaptureView = null
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
                            if (showSoftKeyboard) {
                                desktopKeyboardActivation = SystemClock.uptimeMillis()
                                keyboardInteraction = desktopKeyboardActivation
                            }
                        }
                    }
                }
            }
    ) {
        key(sshReconnectAttempt) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .alpha(terminalAlpha),
            factory = { viewContext ->
                val terminalView = com.termux.view.TerminalView(viewContext, null)
                terminalView.setTextSize(fontSize)
                terminalView.setPointerDragSelectionEnabled(true)
                terminalView.setTextSelectionMoreEnabled(false)
                terminalView.setPointerPasteMenuEnabled(true)
                // Compose owns the SSH InputConnection, including Horizon replacement/pop-up keys.
                terminalView.isFocusable = false
                terminalView.isFocusableInTouchMode = false
                terminalView.setHistoryScrollbarEnabled(true)

                val copyTextToClipboard: (String) -> Unit = { text ->
                    try {
                        val clipboard = viewContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(sshClipboardLabel, text))
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
                        (context as? SessionActivity)?.let { SessionCardActions.connected(it, false) }
                        if (sshHasConnected) sshDisconnected = true else onClose()
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
                sshTerm.onForwardStatus = { forwardStatuses = it }
                sshTerm.onConnectionFailed = {
                    if (sshHasConnected) sshDisconnected = true
                    (context as? SessionActivity)?.let { SessionCardActions.connected(it, false) }
                }
                sshTerm.onConnected = {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        sshHasConnected = true
                        sshDisconnected = false
                        (context as? SessionActivity)?.let { SessionCardActions.connected(it, true) }
                    }
                    if (session.enableSftp) kotlin.concurrent.thread(name = "sftp-probe", isDaemon = true) {
                    // Minimal background probe over the shared SSH transport; never blocks the terminal.
                        val ok = try {
                            val probe = com.farside.workspace.sftp.SftpSession(viewContext.applicationContext, session)
                            try { probe.connect({}, {}); true } finally { probe.close() }
                        } catch (e: Exception) { Log.w("SshSession", "SFTP probe failed: ${e.message}"); false }
                        android.os.Handler(android.os.Looper.getMainLooper()).post { sftpSupported = ok }
                    }
                }
                terminalView.attachSession(sshTerm)
                if (!showFiles && !showForwards && !showTerminalSettings) {
                    (context as? SessionActivity)?.sshHistoryScroll = { terminalView.scrollHistory(it) }
                }

                terminalViewRef = terminalView
                (context as? SessionActivity)?.sshCaptureView = terminalView
                sshSessionRef = sshTerm

                terminalView
            }
        )
        }

        if (sshDisconnected) ReconnectDialog(
            profileName = session.name,
            reason = "",
            autoReconnect = autoReconnect,
            onAutoReconnectChanged = { enabled ->
                autoReconnect = enabled
                profileManager.getProfiles().firstOrNull { it.id == session.id }
                    ?.let { profileManager.updateProfile(it.copy(reconnectAutomatically = enabled)) }
            },
            onReconnect = { sshDisconnected = false; sshReconnectAttempt++ },
            onCancel = onClose,
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
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                val iconSize = 18.dp
                val buttonSize = 36.dp
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(session.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp).padding(horizontal = 6.dp))
                        IconButton(onClick = { toolbarPinned = !toolbarPinned; revealToolbar() }, modifier = Modifier.size(buttonSize)) {
                            Icon(
                                Icons.Default.PushPin,
                                if (toolbarPinned) stringResource(R.string.unpin_toolbar) else stringResource(R.string.keep_toolbar_visible),
                                modifier = Modifier.size(iconSize),
                                tint = if (toolbarPinned) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        if (windowManagerAvailable) IconButton(onClick = {
                            QuestWindowManager.toggle(context)
                            revealToolbar()
                        }, modifier = Modifier.size(buttonSize)) {
                            Icon(Icons.Default.Dashboard, stringResource(R.string.window_manager), modifier = Modifier.size(iconSize), tint = Color.White)
                        }
                        IconButton(onClick = {
                            showZoomSlider = !showZoomSlider
                            if (showZoomSlider) showTransparencySlider = false
                            revealToolbar()
                        }, modifier = Modifier.size(buttonSize)) {
                            Icon(Icons.Default.Search, stringResource(R.string.zoom), modifier = Modifier.size(iconSize), tint = if (showZoomSlider) MaterialTheme.colorScheme.primary else Color.White)
                        }
                        IconButton(onClick = {
                            showTransparencySlider = !showTransparencySlider
                            if (showTransparencySlider) showZoomSlider = false
                            revealToolbar()
                        }, modifier = Modifier.size(buttonSize)) {
                            Icon(
                                Icons.Default.Opacity,
                                stringResource(R.string.transparency),
                                modifier = Modifier.size(iconSize),
                                tint = if (showTransparencySlider) MaterialTheme.colorScheme.primary else if (terminalAlpha < 1.0f) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        sftpSupported?.let { works ->
                            SftpFilesControl(
                                unsupported = !works,
                                onOpen = {
                                    terminalViewRef?.stopTextSelectionMode()
                                    showFiles = true
                                },
                                onInteraction = { revealToolbar() },
                                iconSize = iconSize
                            )
                        }
                        IconButton(onClick = { showForwards = true; revealToolbar() }, modifier = Modifier.size(buttonSize)) {
                            Icon(Icons.Default.SwapHoriz, stringResource(R.string.port_forwarding), modifier = Modifier.size(iconSize), tint = Color.White)
                        }
                        IconButton(onClick = {
                            revealToolbar()
                            showSoftKeyboard = !showSoftKeyboard
                            if (!showSoftKeyboard) { keyboardController?.hide() }
                        }, modifier = Modifier.size(buttonSize)) {
                            Icon(Icons.Default.Keyboard, stringResource(R.string.keyboard), modifier = Modifier.size(iconSize), tint = if (showSoftKeyboard) MaterialTheme.colorScheme.primary else Color.White)
                        }
                    }

                    if (showZoomSlider) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    fontSize = defaultFontSize
                                    terminalViewRef?.setTextSize(defaultFontSize)
                                    revealToolbar()
                                },
                                enabled = fontSize != defaultFontSize,
                                modifier = Modifier.size(buttonSize)
                            ) {
                                Icon(
                                    Icons.Default.FullscreenExit,
                                    stringResource(R.string.reset_zoom),
                                    modifier = Modifier.size(iconSize),
                                    tint = if (fontSize != defaultFontSize) Color.White else Color.Gray
                                )
                            }
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
                                modifier = Modifier.width(224.dp).padding(horizontal = 4.dp)
                            )
                            Text(
                                stringResource(R.string.terminal_font_size, fontSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }

                    if (showTransparencySlider) {
                        Text(stringResource(R.string.terminal_opacity_percent, (terminalAlpha * 100).roundToInt()), style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(bottom = 3.dp))
                        Slider(
                            value = terminalAlpha,
                            onValueChange = { newAlpha ->
                                terminalAlpha = newAlpha.coerceIn(0.2f, 1.0f)
                                revealToolbar()
                            },
                            valueRange = 0.2f..1.0f,
                            modifier = Modifier.width(112.dp)
                        )
                    }
                }
            }
        }

        val hasModalOverlay = showFiles || showForwards || showTerminalSettings
        DisposableEffect(hasModalOverlay, terminalViewRef) {
            val activity = context as? SessionActivity
            if (hasModalOverlay) {
                activity?.sshHistoryScroll = null
            } else {
                terminalViewRef?.let { tv ->
                    activity?.sshHistoryScroll = { tv.scrollHistory(it) }
                }
            }
            onDispose {
                if (hasModalOverlay) {
                    terminalViewRef?.let { tv ->
                        activity?.sshHistoryScroll = { tv.scrollHistory(it) }
                    }
                }
            }
        }

        if (showFiles) Box(Modifier.fillMaxSize()) {
            com.farside.workspace.sftp.SftpFilesEntry(onClose = { showFiles = false }, profileId = session.id)
        }

        // Soft Keyboard Input Handlers
        if (showSoftKeyboard) {
            RemoteImeEditor(
                label = stringResource(R.string.ssh_keyboard_input),
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

        // Parent-width companion preserves the pre-narrowing spatial layout.
        RemoteKeyboardPanel(
            visible = showSoftKeyboard,
            state = keyboardPanelState,
            onInteraction = {
                keyboardInteraction = SystemClock.uptimeMillis()
                panelKeyboardActivation = keyboardInteraction
            }
        ) {
            if (showSoftKeyboard) {
                RemoteImeEditor(
                    label = stringResource(R.string.ssh_key_panel_input),
                    activation = panelKeyboardActivation,
                    showInitially = false,
                    keyboardVisible = keyboardVisible,
                    onActions = { (context as? SessionActivity)?.let { activity -> activity.keyboardPanelEditorActions = it } },
                    onImeVisibility = { panelImeVisible = it },
                    onFocus = { (context as? SessionActivity)?.let { activity -> activity.keyboardPanelEditorHasFocus = it } },
                    onView = { (context as? SessionActivity)?.let { activity -> activity.keyboardPanelEditorView = it } },
                    sendUnicode = { code, down -> handleSshUnicode(code, down) },
                    sendKey = { code, down -> handleSshKey(code, down) }
                )
            }
            RemoteExtraKeys(
                modifier = Modifier.fillMaxSize(),
                enabled = showSoftKeyboard,
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

