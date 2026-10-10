package com.farside.workspace

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.WindowAnchor
import metavrx.layout.window.compose.layout.WindowModifier
import metavrx.layout.window.compose.layout.anchor
import metavrx.layout.window.compose.layout.height
import metavrx.layout.window.compose.layout.width
import metavrx.layout.window.compose.state.WindowState
import metavrx.layout.window.compose.state.WindowFallback

/** Bottom-attached keys never consume or resize the desktop's content area. */
@Composable
internal fun RemoteKeyboardPanel(visible: Boolean, expanded: Boolean, state: WindowState,
    onInteraction: () -> Unit, content: @Composable () -> Unit) {
    var emitted by remember { mutableStateOf(false) }
    var expandedSize by remember { mutableStateOf(false) }
    val resizeLock = remember { Mutex() }
    val compactPixels = with(LocalDensity.current) { 76.dp.roundToPx() }
    val interaction by rememberUpdatedState(onInteraction)
    LaunchedEffect(visible) {
        if (visible) emitted = true
        else { delay(180); emitted = false; expandedSize = false }
    }
    LaunchedEffect(expanded, visible) {
        if (visible) resizeLock.withLock {
            if (!expanded && expandedSize) {
                delay(180)
                // Finish the bank animation before touching spatial geometry. The compositor
                // must not reuse a large, still-visible buffer at the compact window's pose.
                repeat(2) { withFrameNanos { } }
                KeyboardPanelResize.compact(state, compactPixels) { expandedSize = false }
            } else expandedSize = expanded
        }
    }
    if (emitted) {
        LaunchedEffect(state) { state.lifecycle.collect { Log.i("KeyboardPanel", "placement=$it") } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Leave the main window's Horizon corner grab targets clear on both sides.
            val panelWidth = (maxWidth - 96.dp).coerceAtLeast(320.dp).coerceAtMost(maxWidth)
            SpatialWindow(key = "remote-extra-keys", windowState = state, fallbackStrategy = WindowFallback.Drop,
                modifier = WindowModifier.width(panelWidth).height(if (expandedSize) 314.dp else 76.dp)
                    .anchor(WindowAnchor.Bottom)) {
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(visible) { appeared = visible }
                val opacity by animateFloatAsState(if (appeared) 1f else 0f, tween(180), label = "keyboard-panel")
                Box(Modifier.fillMaxSize().background(Color(0xFF181818)).pointerInput(visible) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (visible && event.type == PointerEventType.Press) interaction()
                        }
                    }
                }) {
                    Box(Modifier.fillMaxSize().alpha(opacity)) { content() }
                }
            }
        }
    }
}
