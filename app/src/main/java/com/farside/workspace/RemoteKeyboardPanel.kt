@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.farside.workspace

import android.graphics.Point
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
internal const val KEYBOARD_FADE_MS = 140
internal const val KEYBOARD_ROLL_MS = 260
internal const val KEYBOARD_PREPARE_MS = 180L

/** Separate native travel from painted opacity, so the fixed-size keys never stretch. */
internal class KeyboardPanelMotion {
    val roll = Animatable(0f)
    val opacity = Animatable(0f)
}

/** Opening: move the native attachment down, then reveal. Closing reverses that order. */
@Composable
internal fun RemoteKeyboardPanel(visible: Boolean, state: WindowState,
    onInteraction: () -> Unit, motion: KeyboardPanelMotion = remember { KeyboardPanelMotion() }, content: @Composable () -> Unit) {
    var emitted by remember { mutableStateOf(visible) }
    val opacity = motion.opacity
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
    fun place(progress: Float) {
        val t = token ?: return
        val params = VolumetricWindowLayoutParams.Builder().setAttachFrom(48L).setAttachTo(80L)
            // Native attachment coordinates point upward. Tuck the invisible fixed-size
            // panel behind the desktop, then move its lower handle to the open position.
            .setTranslationOverride(Point(0, ((1f-progress)*with(density) { 251.2.dp.toPx() }-96f).toInt())).build()
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
    LaunchedEffect(visible, lifecycle, token) {
        if (lifecycle != WindowLifecycle.Active || token == null) {
            if (!visible) {
                opacity.snapTo(0f)
                motion.roll.snapTo(0f)
                emitted = false
            }
            return@LaunchedEffect
        }
        if (!visible) {
            opacity.animateTo(0f, tween(KEYBOARD_FADE_MS))
            motion.roll.animateTo(0f, tween(KEYBOARD_ROLL_MS, easing=FastOutSlowInEasing)) { place(value) }
            repeat(2) { withFrameNanos { } }
            emitted = false
            return@LaunchedEffect
        }
        place(motion.roll.value)
        // Bound initial layout preparation; the former four-second geometry wait and
        // 400ms stable-pose delay made every opening noticeably lag behind the button.
        withTimeoutOrNull(KEYBOARD_PREPARE_MS) {
            val height = with(density) { 251.2.dp.roundToPx() }
            while (state.hostedView?.height != height) withFrameNanos { }
            awaitKeyboardFrame(state)
        }
        place(motion.roll.value)
        motion.roll.animateTo(1f, tween(KEYBOARD_ROLL_MS, easing=FastOutSlowInEasing)) { place(value) }
        opacity.animateTo(1f, tween(KEYBOARD_FADE_MS))
    }
    LaunchedEffect(parentWidth, parentHeight) {
        displayedWidth = panelWidth
        // Width/layout changes must not restart a show/hide transition.
        if (lifecycle == WindowLifecycle.Active) place(motion.roll.value)
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
        if (withTimeoutOrNull(120) { submitted.await(); true } != true) {
            android.util.Log.w("KeyboardFade", "Frame commit timed out")
            false
        } else true
    } finally {
        if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
    }
}
