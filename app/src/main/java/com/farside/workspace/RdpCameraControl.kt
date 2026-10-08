package com.farside.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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

/** Camera redirection toolbar control: turns red when remote records; circle-backslash when unsupported. */
@Composable
internal fun RdpCameraControl(
    muted: Boolean,
    recording: Boolean,
    hostUnsupported: Boolean,
    onToggleMute: () -> Unit,
    onInteraction: () -> Unit,
) {
    var showUnsupportedDialog by remember { mutableStateOf(false) }
    val description = when {
        hostUnsupported -> "Camera redirection unsupported"
        muted -> "Unmute camera"
        recording -> "Mute camera (recording)"
        else -> "Mute camera"
    }
    IconButton(onClick = {
        onInteraction()
        if (hostUnsupported) showUnsupportedDialog = true else onToggleMute()
    }) {
        Box(Modifier.size(24.dp)) {
            Icon(
                if (muted && !hostUnsupported) Icons.Default.VideocamOff else Icons.Default.Videocam,
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
            title = { Text("Camera redirection unavailable") },
            text = { Text("This host does not support camera redirection.") },
            confirmButton = {
                TextButton(onClick = { showUnsupportedDialog = false; onInteraction() }) { Text("OK") }
            },
        )
    }
}
