package com.multidesk.explorer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Host rejection is distinct from idle capture and the user's mute choice. */
@Composable
internal fun RdpMicrophoneControl(
    muted: Boolean,
    recording: Boolean,
    hostUnsupported: Boolean,
    onToggleMute: () -> Unit,
    onInteraction: () -> Unit,
) {
    var showUnsupportedDialog by remember { mutableStateOf(false) }
    val description = when {
        hostUnsupported -> "Microphone redirection unsupported"
        muted -> "Unmute microphone"
        recording -> "Mute microphone (recording)"
        else -> "Mute microphone"
    }
    IconButton(onClick = {
        onInteraction()
        if (hostUnsupported) showUnsupportedDialog = true else onToggleMute()
    }) {
        Box(Modifier.size(24.dp)) {
            Icon(
                if (muted && !hostUnsupported) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = description,
                tint = if (!hostUnsupported && !muted && recording) Color.Red else Color.White,
                modifier = Modifier.fillMaxSize(),
            )
            if (hostUnsupported) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2f
                    drawCircle(Color.Red, radius, style = Stroke(stroke))
                    val diagonal = radius * 0.7071f
                    drawLine(Color.Red, center + Offset(-diagonal, -diagonal),
                        center + Offset(diagonal, diagonal), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
    if (showUnsupportedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsupportedDialog = false },
            title = { Text("Microphone redirection unavailable") },
            text = { Text("This host does not support microphone redirection.") },
            confirmButton = {
                TextButton(onClick = { showUnsupportedDialog = false; onInteraction() }) { Text("OK") }
            },
        )
    }
}
