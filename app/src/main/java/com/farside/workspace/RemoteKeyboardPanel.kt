@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.farside.workspace

import android.graphics.Point
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import metavr.app.volumetricwindow.VolumetricWindowTransaction
import metavr.app.volumetricwindow.VolumetricWindowLayoutParams
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.*
import metavrx.layout.window.compose.state.*

internal const val KEYBOARD_MIN_WIDTH_DP = 512
internal const val KEYBOARD_FADE_MS = 500

/** One full keyboard, with fades only when shown or closed. */
@Composable
internal fun RemoteKeyboardPanel(visible: Boolean, state: WindowState,
    onInteraction: () -> Unit, content: @Composable () -> Unit) {
    var emitted by remember { mutableStateOf(visible) }
    val opacity = remember { Animatable(0f) }
    SideEffect { state.nonOpaque = true }
    val currentContent by rememberUpdatedState(content)
    val interaction by rememberUpdatedState(onInteraction)
    val lifecycle by state.lifecycle.collectAsState()
    val token by state.token.collectAsState()
    val parent = LocalView.current
    val density = LocalDensity.current
    var parentWidth by remember(parent) { mutableIntStateOf(parent.width) }
    var parentHeight by remember(parent) { mutableIntStateOf(parent.height) }
    val panelWidth = with(density) { parentWidth.toDp() }.coerceAtLeast(KEYBOARD_MIN_WIDTH_DP.dp)
    var displayedWidth by remember { mutableStateOf(panelWidth) }
    val geometry = remember(parent) { KeyboardWindowGeometry(parent.context) }
    DisposableEffect(geometry) { onDispose { geometry.close() } }
    fun place() {
        val t = token ?: return
        val params = VolumetricWindowLayoutParams.Builder().setAttachFrom(48L).setAttachTo(80L)
            .setTranslationOverride(Point(0, -96)).build()
        state.attachedWindowManager?.applyTransaction(VolumetricWindowTransaction.Builder()
            .setLayoutParams(t, params).build())
    }
    DisposableEffect(parent) {
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            parentWidth = r-l
            parentHeight = b-t
        }
        parent.addOnLayoutChangeListener(listener)
        onDispose { parent.removeOnLayoutChangeListener(listener) }
    }
    LaunchedEffect(visible) { if (visible) emitted = true }
    LaunchedEffect(visible, parentWidth, parentHeight, lifecycle, token) {
        if (lifecycle != WindowLifecycle.Active || token == null) {
            if (!visible) emitted = false
            return@LaunchedEffect
        }
        if (!visible) {
            opacity.animateTo(0f, tween(KEYBOARD_FADE_MS))
            awaitKeyboardFrame(state)
            emitted = false
            return@LaunchedEffect
        }
        displayedWidth = panelWidth
        val height = with(density) { 251.2.dp.roundToPx() }
        val width = with(density) { panelWidth.roundToPx() }
        // Wait for SDK sizing first, then own all placement until native geometry settles.
        val ready = withTimeoutOrNull(4000) {
            while (state.hostedView?.height != height || state.hostedView?.width != width) withFrameNanos { }
            if (!awaitKeyboardFrame(state)) return@withTimeoutOrNull false
            while (true) {
                val native = geometry.read(checkNotNull(token))
                if (native?.width == width && native.height == height) break
                kotlinx.coroutines.delay(50)
            }
            place()
            android.util.Log.d("KeyboardTransition", "placement requested")
            var previous: KeyboardWindowGeometry.Snapshot? = null
            var stableSince = android.os.SystemClock.uptimeMillis()
            while (true) {
                kotlinx.coroutines.delay(50)
                val current = geometry.read(checkNotNull(token))
                val now = android.os.SystemClock.uptimeMillis()
                if (current == null || current.width != width || current.height != height || current != previous) {
                    stableSince = now
                }
                previous = current
                if (current != null && now-stableSince >= 400) break
            }
            awaitKeyboardFrame(state)
        }
        if (ready != true) {
            android.util.Log.w("KeyboardTransition", "Geometry observation timed out; revealing full keyboard")
            // Keep keyboard controls available if this OS cannot report stable geometry.
            if (opacity.value < 1f) opacity.animateTo(1f, tween(KEYBOARD_FADE_MS))
            return@LaunchedEffect
        }
        android.util.Log.d("KeyboardTransition", "geometry settled; reveal")
        if (opacity.value < 1f) opacity.animateTo(1f, tween(KEYBOARD_FADE_MS))
    }
    if (emitted) SpatialWindow(key = "remote-shortcuts", windowState = state, fallbackStrategy = WindowFallback.Drop,
        modifier = WindowModifier.width(displayedWidth).height(251.2.dp).anchor(WindowAnchor.Bottom)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }.pointerInput(visible) {
            awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (visible && event.type == PointerEventType.Press) interaction()
            } }
        }) { currentContent() }
    }
}

private suspend fun awaitKeyboardFrame(state: WindowState): Boolean {
    repeat(2) { withFrameNanos { } }
    val view = state.hostedView ?: return false
    val observer = view.viewTreeObserver
    val submitted = CompletableDeferred<Unit>()
    val callback = Runnable { submitted.complete(Unit) }
    observer.registerFrameCommitCallback(callback)
    return try {
        view.invalidate()
        if (withTimeoutOrNull(1000) { submitted.await(); true } != true) {
            android.util.Log.w("KeyboardFade", "Frame commit timed out")
            false
        } else true
    } finally {
        if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
    }
}
