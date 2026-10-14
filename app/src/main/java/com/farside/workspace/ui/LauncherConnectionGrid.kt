package com.farside.workspace.ui

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.SessionCardActions
import kotlinx.coroutines.delay

private fun LazyGridState.profileAt(point: Offset): String? = layoutInfo.visibleItemsInfo.firstOrNull {
    point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
        point.y >= it.offset.y && point.y < it.offset.y + it.size.height
}?.key as? String

/** Reordering owns its gesture at the grid, so moving keyed cards cannot cancel the drag. */
@Composable
internal fun LauncherConnectionGrid(
    profiles: List<ConnectionProfile>, starredIds: Set<String>, reordering: Boolean,
    modifier: Modifier, onEdit: (ConnectionProfile) -> Unit, onDelete: (String) -> Unit,
    onConnect: (String) -> Unit, onToggleStar: (String) -> Unit, onMove: (String, String) -> Unit,
    onCapture: (String) -> Unit = {}
) {
    val connectionsLabel = stringResource(R.string.connections)
    val grid = rememberLazyGridState()
    val latestMove by rememberUpdatedState(onMove)
    var dragged by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(Offset.Zero) }
    var edge by remember { mutableIntStateOf(0) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    val edgeSize = with(LocalDensity.current) { 48.dp.toPx() }
    val scrollStep = with(LocalDensity.current) { 10.dp.toPx() }

    fun moveAtPointer(source: String) {
        val target = grid.profileAt(position) ?: return
        if (target == source) lastTarget = null
        else if (target != lastTarget) {
            lastTarget = target
            latestMove(source, target)
        }
    }
    fun updateEdge() {
        edge = when {
            position.y < edgeSize -> -1
            position.y > grid.layoutInfo.viewportEndOffset - edgeSize -> 1
            else -> 0
        }
    }

    LaunchedEffect(reordering, dragged, edge) {
        val source = dragged ?: return@LaunchedEffect
        if (!reordering || edge == 0) return@LaunchedEffect
        while (true) {
            grid.scrollBy(edge * scrollStep)
            moveAtPointer(source)
            delay(16)
        }
    }
    LaunchedEffect(reordering) { if (!reordering) { dragged = null; edge = 0 } }

    val reorderGesture = if (reordering) Modifier.pointerInput(grid, edgeSize) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Choose the source at DOWN, even if the first MOVE jumps to another card.
            val source = grid.profileAt(down.position) ?: return@awaitEachGesture
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            dragged = source
            lastTarget = null
            position = start.position
            moveAtPointer(source)
            updateEdge()
            try {
                drag(start.id) { change ->
                    change.consume()
                    position = change.position
                    moveAtPointer(source)
                    updateEdge()
                }
            } finally {
                dragged = null
                edge = 0
                lastTarget = null
            }
        }
    } else Modifier

    BoxWithConstraints(modifier) {
    val columns = when {
        maxWidth >= 840.dp -> 3
        maxWidth >= 440.dp -> 2
        else -> 1
    }
    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
        contentPadding = PaddingValues(8.dp), userScrollEnabled = !reordering,
        modifier = Modifier.fillMaxSize().then(reorderGesture).semantics { contentDescription = connectionsLabel }) {
        itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
            val starred = profile.id in starredIds
            val earlier = profiles.getOrNull(index - 1)?.takeIf { (it.id in starredIds) == starred }
            val later = profiles.getOrNull(index + 1)?.takeIf { (it.id in starredIds) == starred }
            ProfileCard(profile, onEdit = { onEdit(profile) }, onDelete = { onDelete(profile.id) },
                onConnect = { onConnect(profile.id) }, reordering = reordering, starred = starred,
                onToggleStar = { onToggleStar(profile.id) }, dragging = dragged == profile.id,
                connected = (SessionCardActions.connectedCounts[profile.id] ?: 0) > 0,
                onCapture = { onCapture(profile.id) },
                onMoveEarlier = earlier?.let { { latestMove(profile.id, it.id) } },
                onMoveLater = later?.let { { latestMove(profile.id, it.id) } },
                jumpLabel = profile.jumpHostProfileId?.takeIf { it.isNotBlank() }?.let { id -> profiles.find { it.id == id }?.let { stringResource(R.string.connection_via, it.name) } ?: stringResource(R.string.jump_host_missing) })
        }
    }
    }
}
