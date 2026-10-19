package com.farside.workspace

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.stringResource

/** A fallback for Home versions that expose local/identity rather than comparable panel poses. */
@Composable
internal fun RdpDesktopArrangement(monitor: RdpMonitor, onDismiss: () -> Unit) {
    val session = monitor.session
    val others = session.monitors.filter { it !== monitor }
    var selected by remember { mutableStateOf(others.firstOrNull()?.id) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rdp_arrange_desktops)) },
        text = { Column {
            Text(stringResource(R.string.rdp_arrangement_hint))
            others.forEach { other ->
                TextButton(onClick = { selected = other.id }) {
                    RadioButton(selected == other.id, onClick = { selected = other.id })
                    Text(stringResource(R.string.rdp_desktop_number, session.monitors.indexOf(other)+1))
                }
            }
            val anchor = others.find { it.id == selected }
            val enabled = anchor != null && !session.busy && !session.drag.active
            for (row in listOf(listOf(Triple(R.string.rdp_layout_left,-1,0), Triple(R.string.rdp_layout_right,1,0)),
                listOf(Triple(R.string.rdp_layout_above,0,1), Triple(R.string.rdp_layout_below,0,-1)))) {
                Row {
                    row.forEach { (label,dx,dy) -> TextButton(enabled = enabled, onClick = {
                        anchor?.let { session.arrangeRelative(monitor,it,dx,dy) }; onDismiss()
                    }) { Text(stringResource(label)) } }
                }
            }
            TextButton(onClick = { session.followPanelLayout(); onDismiss() }) { Text(stringResource(R.string.rdp_layout_auto)) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
