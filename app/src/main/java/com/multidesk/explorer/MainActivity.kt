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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import metavrx.layout.compose.SpatialScene
import metavrx.layout.window.compose.SpatialWindow
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.multidesk.explorer.tunnel.CloudflaredService
import com.multidesk.explorer.data.ConnectionProfile
import com.multidesk.explorer.data.ConnectionType
import com.multidesk.explorer.data.ProfileManager
import com.multidesk.explorer.ui.ConnectionIconScreen
import com.multidesk.explorer.ui.LauncherScreen
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileManager = ProfileManager(this)
        
        setContent {
            var profiles by remember { mutableStateOf(profileManager.getProfiles()) }
            var activeProfiles by remember { mutableStateOf(setOf<String>()) }
            var activeInstances by remember { mutableStateOf(mapOf<String, Long>()) }

            val refreshProfiles = { profiles = profileManager.getProfiles() }

            SpatialScene {
                SpatialWindow(key = "launcher_window") {
                    MaterialTheme {
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
                                }
                            )
                        }
                    }
                }

                for (profile in profiles) {
                    SpatialWindow(key = "conn_${profile.id}") {
                        MaterialTheme {
                            if (activeProfiles.contains(profile.id)) {
                                if (profile.type == ConnectionType.RDP) {
                                    RdpSessionScreen(
                                        session = profile,
                                        onInstanceCreated = { instance ->
                                            activeInstances = activeInstances + (profile.id to instance)
                                        },
                                        onClose = {
                                            activeProfiles = activeProfiles - profile.id
                                            activeInstances = activeInstances - profile.id
                                        }
                                    )
                                } else {
                                    SshSessionScreen(
                                        session = profile,
                                        onClose = {
                                            activeProfiles = activeProfiles - profile.id
                                        }
                                    )
                                }
                            } else {
                                ConnectionIconScreen(
                                    profile = profile,
                                    onConnect = {
                                        activeProfiles = activeProfiles + profile.id
                                    }
                                )
                            }
                        }
                    }
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
                    override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {}
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
        
        IconButton(
            onClick = onClose,
            modifier = Modifier.padding(16.dp).align(androidx.compose.ui.Alignment.TopEnd)
        ) {
            Text("X", color = androidx.compose.ui.graphics.Color.Red, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun RdpSessionScreen(session: ConnectionProfile, onInstanceCreated: (Long) -> Unit, onClose: () -> Unit) {
    val rdpBridge = remember { FreeRDPBridge() }
    var rdpInstance by remember { mutableStateOf(0L) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    setOnTouchListener { _, event ->
                        val x = event.x.toInt()
                        val y = event.y.toInt()
                        var flags = FreeRDPBridge.PTR_FLAGS_MOVE
                        
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                                flags = flags or FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_POINTER_UP -> {
                                flags = flags or FreeRDPBridge.PTR_FLAGS_BUTTON1
                            }
                        }
                        
                        if (rdpInstance != 0L) {
                            rdpBridge.sendMouseEvent(rdpInstance, x, y, flags)
                        }
                        true
                    }
                    
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            Log.i("RdpSession", "Surface created for ${session.id}")
                            if (false) {
                                rdpBridge.addSurface(0L, h.surface)
                            } else {
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
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            Log.i("RdpSession", "Surface destroyed for ${session.id}")
                            if (session.useCloudflare) {
                                val intent = Intent(context, CloudflaredService::class.java).apply {
                                    action = CloudflaredService.ACTION_STOP
                                }
                                context.startService(intent)
                            }
                            
                            if (false) {
                                rdpBridge.removeSurface(0L, h.surface)
                            } else if (rdpInstance != 0L) {
                                rdpBridge.disconnect(rdpInstance)
                                rdpInstance = 0L
                            }
                        }
                    })
                }
            }
        )
        
        // Add a simple close button overlay for now
        IconButton(
            onClick = onClose,
            modifier = Modifier.padding(16.dp).align(androidx.compose.ui.Alignment.TopEnd)
        ) {
            Text("X", color = androidx.compose.ui.graphics.Color.Red, style = MaterialTheme.typography.titleLarge)
        }
    }
}
