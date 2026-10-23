@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.farside.workspace

import android.graphics.Point
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import metavr.app.volumetricwindow.VolumetricWindowTransaction
import metavr.app.volumetricwindow.VolumetricWindowLayoutParams
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.*
import metavrx.layout.window.compose.state.*

internal const val KEYBOARD_MIN_WIDTH_DP = 512
internal const val KEYBOARD_FADE_MS = 200
internal const val KEYBOARD_ROLL_MS = 260

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
    var openingHeight by remember { mutableStateOf(1.dp) }
    val geometry = remember(parent) { KeyboardWindowGeometry(parent.context) }
    DisposableEffect(geometry) { onDispose { geometry.close() } }
    suspend fun awaitExtent(open: Boolean) {
        val height=if(open) with(density) {251.2.dp.roundToPx()} else 1
        withTimeoutOrNull(100) {
            while(token?.let(geometry::read)?.height!=height) withFrameNanos { }
        }
        repeat(2) {withFrameNanos { }}
    }
    fun place(progress: Float, resizeNative: Boolean = true) {
        val t = token ?: return
        val fullHeight = with(density) { 251.2.dp.roundToPx() }
        val width = with(density) { displayedWidth.roundToPx() }
        val params = VolumetricWindowLayoutParams.Builder().setAttachFrom(48L).setAttachTo(80L)
            .setTranslationOverride(Point(0, (-96f*progress).toInt())).build()
        // Animate only the invisible native footprint. The Android backing buffer stays
        // full-sized; reveal it only after the full native extent has been submitted.
        val transaction=VolumetricWindowTransaction.Builder().setLayoutParams(t, params)
        if(resizeNative) transaction.setSize(t, android.util.Size(width, (fullHeight*progress).toInt().coerceAtLeast(1)))
        state.attachedWindowManager?.applyTransaction(transaction.build())
    }
    DisposableEffect(parent) {
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            parentWidth = r-l
            parentHeight = b-t
        }
        parent.addOnLayoutChangeListener(listener)
        onDispose { parent.removeOnLayoutChangeListener(listener) }
    }
    LaunchedEffect(visible) { if (visible) {
        if(motion.roll.value==0f) openingHeight=1.dp
        emitted = true
    } }
    LaunchedEffect(visible, lifecycle, token) {
        if (lifecycle != WindowLifecycle.Active || token == null) {
            if (!visible) {
                opacity.snapTo(0f)
                motion.roll.snapTo(0f)
                emitted = false
                openingHeight = 1.dp
            }
            return@LaunchedEffect
        }
        if (!visible) {
            opacity.animateTo(0f, tween(KEYBOARD_FADE_MS, easing=LinearEasing))
            motion.roll.animateTo(0f, tween(KEYBOARD_ROLL_MS, easing=FastOutSlowInEasing)) { place(value) }
            awaitExtent(false)
            emitted = false
            openingHeight = 1.dp
            return@LaunchedEffect
        }
        // Create at the collapsed height. Creating full-sized and shrinking after Active
        // exposed the fully extended handle before the opening animation even started.
        place(motion.roll.value, resizeNative=false)
        withFrameNanos { }
        motion.roll.animateTo(1f, tween(KEYBOARD_ROLL_MS, easing=FastOutSlowInEasing)) {
            openingHeight=(251.2f*value).dp.coerceAtLeast(1.dp)
            place(value, resizeNative=false)
        }
        // SDK owns opening buffer/extent sizing while invisible; closing keeps its
        // accepted fixed-buffer native-collapse path.
        withTimeoutOrNull(100) {
            val fullHeight=with(density) {251.2.dp.roundToPx()}
            while(state.hostedView?.height!=fullHeight) withFrameNanos { }
        }
        awaitExtent(true)
        opacity.animateTo(1f, tween(KEYBOARD_FADE_MS, easing=LinearEasing))
    }
    LaunchedEffect(parentWidth, parentHeight) {
        displayedWidth = panelWidth
        // Width/layout changes must not restart a show/hide transition.
        if (lifecycle == WindowLifecycle.Active) place(motion.roll.value)
    }
    if (emitted) SpatialWindow(key = "remote-shortcuts", windowState = state, fallbackStrategy = WindowFallback.Drop,
        modifier = WindowModifier.width(displayedWidth).height(openingHeight).anchor(WindowAnchor.Bottom)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }.pointerInput(visible) {
            awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (visible && event.type == PointerEventType.Press) interaction()
            } }
        }) { currentContent() }
    }
}
