package com.farside.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
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

/** SSH toolbar Files control: opens the file browser, or shows a circle-backslash when SFTP is unsupported. */
@Composable
internal fun SftpFilesControl(unsupported: Boolean, onOpen: () -> Unit, onInteraction: () -> Unit) {
    var showUnsupportedDialog by remember { mutableStateOf(false) }
    IconButton(onClick = {
        onInteraction()
        if (unsupported) showUnsupportedDialog = true else onOpen()
    }) {
        Box(Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Folder,
                contentDescription = if (unsupported) "SFTP files unsupported" else "Browse files",
                tint = Color.White,
                modifier = Modifier.fillMaxSize(),
            )
            if (unsupported) {
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
            title = { Text("File access unavailable") },
            text = { Text("SFTP file access is turned on for this connection, but the server did not accept an SFTP connection.") },
            confirmButton = { TextButton(onClick = { showUnsupportedDialog = false; onInteraction() }) { Text("OK") } },
        )
    }
}
