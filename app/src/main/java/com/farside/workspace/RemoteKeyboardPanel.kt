@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.farside.workspace

import android.graphics.Point
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import metavr.app.volumetricwindow.VolumetricWindowTransaction
import metavr.app.volumetricwindow.VolumetricWindowLayoutParams
import metavrx.layout.window.compose.SpatialWindow
import metavrx.layout.window.compose.layout.*
import metavrx.layout.window.compose.state.*

/** Fixed-height opaque keyboard: page changes never resize or crop the native surface. */
@Composable
internal fun RemoteKeyboardPanel(visible: Boolean, state: WindowState,
    onInteraction: () -> Unit, content: @Composable () -> Unit) {
    if (!visible) return
    val currentContent by rememberUpdatedState(content)
    val interaction by rememberUpdatedState(onInteraction)
    val lifecycle by state.lifecycle.collectAsState()
    val token by state.token.collectAsState()
    fun place() {
        val t = token ?: return
        // Native translationOverride is in dp (not pixels). Negative Y is downward.
        val params = VolumetricWindowLayoutParams.Builder().setAttachFrom(48L).setAttachTo(80L)
            .setTranslationOverride(Point(0, -96)).build()
        state.attachedWindowManager?.applyTransaction(VolumetricWindowTransaction.Builder()
            .setLayoutParams(t, params).build())
    }
    // The SDK commits/animates initial placement and width changes asynchronously.
    // Reapply clearance after that commit; paging never enters either placement path.
    LaunchedEffect(lifecycle, token) {
        if (lifecycle == WindowLifecycle.Active && token != null) {
            kotlinx.coroutines.delay(600)
            place()
        }
    }
    DisposableEffect(lifecycle, token) {
        val view = state.hostedView
        val placement = Runnable { place() }
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                view?.removeCallbacks(placement)
                view?.postDelayed(placement, 600)
            }
        }
        view?.addOnLayoutChangeListener(listener)
        onDispose { view?.removeCallbacks(placement); view?.removeOnLayoutChangeListener(listener) }
    }
    SpatialWindow(key = "remote-shortcuts", windowState = state, fallbackStrategy = WindowFallback.Drop,
        modifier = WindowModifier.matchParentWidth().height(76.dp).anchor(WindowAnchor.Bottom)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF181818)).pointerInput(Unit) {
            awaitPointerEventScope { while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) interaction()
            } }
        }) { currentContent() }
    }
}
