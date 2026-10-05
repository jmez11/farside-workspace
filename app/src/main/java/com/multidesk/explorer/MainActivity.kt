package com.multidesk.explorer

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import com.multidesk.explorer.tunnel.CloudflaredService
import com.multidesk.explorer.data.ConnectionProfile
import com.multidesk.explorer.data.ConnectionType
import com.multidesk.explorer.data.ProfileManager
import com.multidesk.explorer.ui.LauncherScreen
import java.util.UUID

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
                            val intent = Intent(this@MainActivity, SessionActivity::class.java).apply {
                                data = android.net.Uri.parse("multidesk://session/${profileId}")
                                putExtra("profileId", profileId)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                            }
                            startActivity(intent)
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

@Composable
fun RdpSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit) {
    val rdpBridge = remember { FreeRDPBridge() }
    var rdpInstance by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showSoftKeyboard by remember { mutableStateOf(false) }
    
    var zoom by remember { mutableStateOf(1f) }
    var actualWidth by remember { mutableStateOf(1920) }
    var actualHeight by remember { mutableStateOf(1080) }
    var lastMouseEventTime by remember { mutableStateOf(0L) }
    var currentWidth by remember { mutableStateOf(1920) }
    var currentHeight by remember { mutableStateOf(1080) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) } // Flush with top
    var showZoomSlider by remember { mutableStateOf(false) }

    var isOverlayVisible by remember { mutableStateOf(true) }
    
    LaunchedEffect(isOverlayVisible, offsetX, offsetY, showZoomSlider) {
        if (isOverlayVisible && !showZoomSlider) {
            delay(3000)
            isOverlayVisible = false
        }
    }
    
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isOverlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 500)
    )

    LaunchedEffect(zoom, currentWidth, currentHeight) {
        if (rdpInstance != 0L && currentWidth > 0 && currentHeight > 0) {
            rdpBridge.updateDisplay(rdpInstance, currentWidth, currentHeight, zoom)
            for (i in 0..10) {
                kotlinx.coroutines.delay(200)
                actualWidth = rdpBridge.getDesktopWidth(rdpInstance)
                actualHeight = rdpBridge.getDesktopHeight(rdpInstance)
            }
        }
    }


    if (showSoftKeyboard) {
        val softKeyboardFocus = remember { FocusRequester() }
        var textValue by remember { mutableStateOf("") }
        
        // Modifier keys row
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
        ) {
            val sendKey = { code: Int ->
                if (rdpInstance != 0L) {
                    rdpBridge.sendKeyEvent(rdpInstance, code, true)
                    rdpBridge.sendKeyEvent(rdpInstance, code, false)
                }
            }
            Button(onClick = { sendKey(0x01) }) { Text("ESC") }
            Button(onClick = { sendKey(0x0F) }) { Text("TAB") }
            Button(onClick = { sendKey(0x1D) }) { Text("CTRL") }
            Button(onClick = { sendKey(0x38) }) { Text("ALT") }
            Button(onClick = { sendKey(0x5B) }) { Text("WIN") }
            Button(onClick = { sendKey(0x2A) }) { Text("SHIFT") }
            Button(onClick = { sendKey(0x39) }) { Text("SPACE") }
        }

        androidx.compose.foundation.text.BasicTextField(
            value = textValue,
            onValueChange = { newVal ->
                if (newVal.length > textValue.length && rdpInstance != 0L) {
                    val char = newVal.last()
                    rdpBridge.sendUnicodeKeyEvent(rdpInstance, char.code, true)
                    rdpBridge.sendUnicodeKeyEvent(rdpInstance, char.code, false)
                } else if (newVal.length < textValue.length && rdpInstance != 0L) {
                    rdpBridge.sendKeyEvent(rdpInstance, 0x0E, true) // Backspace
                    rdpBridge.sendKeyEvent(rdpInstance, 0x0E, false)
                }
                textValue = newVal
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.size(1.dp).focusRequester(softKeyboardFocus)
        )
        LaunchedEffect(Unit) {
            softKeyboardFocus.requestFocus()
            keyboardController?.show()
        }
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
                            val overlayCenter = Offset(offsetX + 150f, offsetY + 50f)
                            if ((pos - overlayCenter).getDistance() < 300f) {
                                isOverlayVisible = true
                            }
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    setZOrderMediaOverlay(true)
                    pivotX = 0f
                    pivotY = 0f
                    
                    val gestureDetector = GestureDetector(viewContext, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            if (rdpInstance != 0L) {
                                val rdpX = e.x.toInt()
                                val rdpY = e.y.toInt()
                                rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, FreeRDPBridge.PTR_FLAGS_MOVE or FreeRDPBridge.PTR_FLAGS_BUTTON2 or FreeRDPBridge.PTR_FLAGS_DOWN)
                                rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, FreeRDPBridge.PTR_FLAGS_MOVE or FreeRDPBridge.PTR_FLAGS_BUTTON2)
                            }
                        }
                    })

                    setOnTouchListener { view, event ->
                        focusRequester.requestFocus()
                        gestureDetector.onTouchEvent(event)
                        
                        val scaleX = actualWidth.toFloat() / view.width.toFloat()
                        val scaleY = actualHeight.toFloat() / view.height.toFloat()
                        val rdpX = (event.x * scaleX).toInt()
                        val rdpY = (event.y * scaleY).toInt()
                        
                        var isRightClick = false
                        if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                            isRightClick = true
                        }
                        
                        var flags = 0
                        val btnFlag = if (isRightClick) FreeRDPBridge.PTR_FLAGS_BUTTON2 else FreeRDPBridge.PTR_FLAGS_BUTTON1
                        
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                flags = btnFlag or FreeRDPBridge.PTR_FLAGS_DOWN
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                                flags = btnFlag
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val now = System.currentTimeMillis()
                                if (now - lastMouseEventTime < 16) return@setOnTouchListener true
                                lastMouseEventTime = now
                                flags = FreeRDPBridge.PTR_FLAGS_MOVE
                            }
                            else -> return@setOnTouchListener true
                        }
                        
                        if (rdpInstance != 0L) {
                            rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, flags)
                        }
                        true
                    }
                    
                    setOnHoverListener { view, event ->
                        val now = System.currentTimeMillis()
                        if (now - lastMouseEventTime < 16) return@setOnHoverListener true
                        lastMouseEventTime = now

                        val scaleX = actualWidth.toFloat() / view.width.toFloat()
                        val scaleY = actualHeight.toFloat() / view.height.toFloat()
                        val rdpX = (event.x * scaleX).toInt()
                        val rdpY = (event.y * scaleY).toInt()

                        if (rdpInstance != 0L) {
                            rdpBridge.sendMouseEvent(rdpInstance, rdpX, rdpY, FreeRDPBridge.PTR_FLAGS_MOVE)
                        }
                        true
                    }
                    
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            Log.i("RdpSession", "Surface created for ${session.id}")
                            Thread {
                                var targetHost = session.host
                                var targetPort = session.port
                                
                                if (session.useCloudflare) {
                                    val localPort = 33890 + (Math.random() * 1000).toInt()
                                    val intent = Intent(context, CloudflaredService::class.java).apply {
                                        action = CloudflaredService.ACTION_START
                                        putExtra(CloudflaredService.EXTRA_HOSTNAME, session.host)
                                        putExtra(CloudflaredService.EXTRA_LOCAL_PORT, localPort)
                                    }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    targetHost = "127.0.0.1"
                                    targetPort = localPort
                                    Thread.sleep(2000) // Give cloudflared time to start
                                }
                                
                                val newInst = rdpBridge.connect(targetHost, targetPort, session.user, session.pass, session.remoteApp, session.enableCamera, session.cameraId, h.surface)
                                rdpInstance = newInst
                                onInstanceCreated(newInst)
                            }.start()
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                            currentWidth = width
                            currentHeight = height
                            if (rdpInstance != 0L) {
                                rdpBridge.updateDisplay(rdpInstance, width, height, zoom)
                            }
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            Log.i("RdpSession", "Surface destroyed for ${session.id}")
                            if (session.useCloudflare) {
                                val intent = Intent(context, CloudflaredService::class.java).apply {
                                    action = CloudflaredService.ACTION_STOP
                                }
                                context.startService(intent)
                            }
                            if (rdpInstance != 0L) {
                                rdpBridge.disconnect(rdpInstance)
                                rdpInstance = 0L
                            }
                        }
                    })
                }
            }
        )
        
        Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopCenter)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .alpha(animatedAlpha)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Row {
                    IconButton(onClick = { showZoomSlider = !showZoomSlider }) {
                        Icon(Icons.Default.Search, "Zoom", tint = Color.White)
                    }
                    IconButton(onClick = { /* Implement menu later */ }) {
                        Icon(Icons.Default.Menu, "Menu", tint = Color.White)
                    }
                    IconButton(onClick = { showSoftKeyboard = !showSoftKeyboard; if (!showSoftKeyboard) { keyboardController?.hide() } }) {
                        Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White)
                    }
                }
                
                if (showZoomSlider) {
                    Text("${(zoom * 100).toInt()}%", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(bottom = 4.dp))
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 1f..3f,
                        steps = 7,
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }
    }
}
