package com.farside.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Pose updates keep the label exposed until the Home-owned panel has stopped moving. */
@Composable
internal fun rememberToolbarMoving(movement: Long): State<Boolean> {
    val moving = remember { mutableStateOf(false) }
    LaunchedEffect(movement) {
        if (movement == 0L) return@LaunchedEffect
        moving.value = true
        // Home exposes pose changes, not an end-of-grab event. Only debounce the
        // pose stream; do not add a visibility hold before starting the slide.
        delay(64)
        moving.value = false
    }
    return moving
}

/** Per-composition placement only: a new session always starts expanded and centered. */
internal class FloatingToolbarState(initialY: Float = 0f) {
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(initialY)
    var collapsed by mutableStateOf(false)
        private set
    var nubY by mutableFloatStateOf(0f)
        private set
    var dockRight by mutableStateOf(false)
        private set
    fun collapse(y: Float, toRight: Boolean = offsetX > 0f) { nubY = y; dockRight = toRight; collapsed = true }
    fun restore() { offsetY = nubY; collapsed = false }
    fun dragNub(dy: Float, maxY: Float) {
        nubY = (nubY + dy).coerceIn(0f, maxY.coerceAtLeast(0f))
    }
    fun dragExpanded(dx: Float, dy: Float, windowWidth: Float, windowHeight: Float, barWidth: Float, barHeight: Float) {
        val center = (windowWidth - barWidth).coerceAtLeast(0f) / 2f
        offsetX = (offsetX + dx).coerceIn(-center, center)
        offsetY = (offsetY + dy).coerceIn(0f, (windowHeight - barHeight).coerceAtLeast(0f))
    }
}

/** Shared by the desktop (RDP/VNC/Mac) and terminal toolbars. */
@Composable
internal fun FloatingSessionToolbar(
    state: FloatingToolbarState,
    alpha: Float,
    onInteraction: () -> Unit,
    onBounds: (Rect) -> Unit = {},
    moving: Boolean = false,
    movementLabel: String = "",
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val restoreLabel = stringResource(R.string.restore_toolbar)
    val collapseLabel = stringResource(R.string.collapse_toolbar)
    val interaction by rememberUpdatedState(onInteraction)
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var peekSize by remember { mutableStateOf(IntSize.Zero) }
    val hidden = state.collapsed
    val slide by animateFloatAsState(if (hidden) 1f else 0f, tween(260), label = "toolbar slide")
    val peek by animateFloatAsState(if (hidden && moving) 1f else 0f, tween(260), label = "connection peek")
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val nubWidth = with(density) { 48.dp.toPx() }
        val nubHeight = barSize.height.toFloat()
        val x = ((width - barSize.width) / 2f + state.offsetX).coerceIn(0f, (width - barSize.width).coerceAtLeast(0f))
        val y = state.offsetY.coerceIn(0f, (height - barSize.height).coerceAtLeast(0f))
        val latestY by rememberUpdatedState(y)
        val latestDockRight by rememberUpdatedState(x + barSize.width / 2f > width / 2f)
        val barTargetX = if (state.dockRight) width else -barSize.width.toFloat()
        val barX = x + (barTargetX - x) * slide
        val peekX = if (state.dockRight) width - peekSize.width * peek else -peekSize.width + peekSize.width * peek
        val edge = with(density) { 8.dp.toPx() }
        val collapsedReady = hidden && barSize.width > 0 &&
            (if (state.dockRight) barX >= width - edge else barX + barSize.width <= edge)
        val peekVisibleWidth = if (state.dockRight) width - peekX else peekX + peekSize.width
        val showPeek = collapsedReady && peekSize.width > 0 && peekVisibleWidth > nubWidth
        val showNub = collapsedReady && !showPeek
        val dockShape = AbsoluteRoundedCornerShape(
            topLeft = if (state.dockRight) 18.dp else 0.dp, bottomLeft = if (state.dockRight) 18.dp else 0.dp,
            topRight = if (state.dockRight) 0.dp else 18.dp, bottomRight = if (state.dockRight) 0.dp else 18.dp)
        val latestWidth by rememberUpdatedState(width)
        val latestHeight by rememberUpdatedState(height)
        fun collapse() { state.collapse(latestY, latestDockRight); interaction() }
        fun restore() { state.restore(); interaction() }
        @Composable
        fun RestoreArrow() {
            Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(if (state.dockRight) Icons.Default.KeyboardArrowLeft else Icons.Default.KeyboardArrowRight,
                    null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        fun Modifier.collapsedGestures() = this
            .semantics { contentDescription = restoreLabel; onClick(label = restoreLabel) { restore(); true } }
            .pointerInput(state) { detectTapGestures(onDoubleTap = { restore() }) }
            .pointerInput(state, height, nubHeight) {
                detectDragGestures { change, delta ->
                    change.consume()
                    state.dragNub(delta.y, height - nubHeight)
                    interaction()
                }
            }
        Surface(
            modifier = Modifier
                .absoluteOffset { IntOffset(barX.roundToInt(), y.roundToInt()) }
                .alpha(if (showNub) 0f else if (state.collapsed || moving) 1f else alpha)
                .onSizeChanged { barSize = it }
                .onGloballyPositioned { if (!hidden) onBounds(it.boundsInRoot()) }
                .then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier
                    .semantics { customActions = listOf(CustomAccessibilityAction(collapseLabel) { collapse(); true }) }
                    .pointerInput(state) { detectTapGestures(onDoubleTap = { collapse() }) }
                    .pointerInput(state) {
                        detectDragGestures { change, delta ->
                            change.consume()
                            state.dragExpanded(delta.x, delta.y, latestWidth, latestHeight, barSize.width.toFloat(), barSize.height.toFloat())
                            interaction()
                        }
                    }),
            shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = .7f)
        ) { content() }
        if (hidden && (moving || peek > 0f)) Surface(
            modifier = Modifier
                .absoluteOffset { IntOffset(peekX.roundToInt(), state.nubY.coerceIn(0f, (height - nubHeight).coerceAtLeast(0f)).roundToInt()) }
                .alpha(if (showPeek) 1f else 0f)
                .widthIn(max = with(density) { width.toDp() })
                .height(with(density) { nubHeight.toDp() })
                .onSizeChanged { peekSize = it }
                .onGloballyPositioned { if (showPeek) onBounds(it.boundsInRoot()) }
                .then(if (showPeek) Modifier.collapsedGestures() else Modifier.clearAndSetSemantics {}),
            shape = dockShape,
            color = Color.Black.copy(alpha = .7f)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (state.dockRight) RestoreArrow()
                Text(movementLabel, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(
                        start = if (state.dockRight) 8.dp else 16.dp, end = if (state.dockRight) 16.dp else 8.dp))
                if (!state.dockRight) RestoreArrow()
            }
        }
        if (showNub) Surface(
            modifier = Modifier
                .absoluteOffset { IntOffset(if (state.dockRight) (width - nubWidth).coerceAtLeast(0f).roundToInt() else 0,
                    state.nubY.coerceIn(0f, (height - nubHeight).coerceAtLeast(0f)).roundToInt()) }
                .width(48.dp).height(with(density) { nubHeight.toDp() })
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .collapsedGestures(),
            shape = dockShape,
            color = Color.Black.copy(alpha = .7f)
        ) {
            RestoreArrow()
        }
    }
}
