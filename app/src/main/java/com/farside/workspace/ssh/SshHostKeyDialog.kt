package com.farside.workspace.ssh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.farside.workspace.R
import kotlinx.coroutines.delay
import java.util.UUID

/** Only the focused application window claims a prompt; its dialog keeps ownership after focus moves. */
@Composable
internal fun SshHostKeyDialogHost(profileIds: Set<String> = emptySet()) {
    val owner = remember { UUID.randomUUID().toString() }
    val view = LocalView.current
    val queue by SshHostKeyPrompts.pending.collectAsState()
    val pending = queue.firstOrNull()
    DisposableEffect(owner, profileIds) {
        SshHostKeyPrompts.register(owner, profileIds)
        onDispose { SshHostKeyPrompts.release(owner) }
    }
    LaunchedEffect(pending?.request?.id, pending?.owner, view) {
        while (pending != null && pending.owner == null) {
            if (view.hasWindowFocus() && SshHostKeyPrompts.claim(pending.request.id, owner)) break
            delay(100)
        }
    }
    if (pending?.owner == owner) SshHostKeyDialog(pending.request)
}

@Composable
internal fun SshHostKeyDialog(request: SshHostKeyRequest) {
    AlertDialog(
        onDismissRequest = { request.decision.complete(false) },
        title = { Text(stringResource(if (request.changed) R.string.ssh_host_key_changed else R.string.ssh_host_key_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (request.changed) R.string.ssh_host_key_changed_help else R.string.ssh_host_key_new_help, request.host, request.port))
                Text(request.key.algorithm)
                Text(request.key.fingerprint)
                request.previous?.let { Text(stringResource(R.string.ssh_host_key_previous, it.fingerprint)) }
            }
        },
        confirmButton = { TextButton(onClick = { request.decision.complete(true) }) {
            Text(stringResource(if (request.changed) R.string.ssh_host_key_replace else R.string.ssh_host_key_save))
        } },
        dismissButton = { TextButton(onClick = { request.decision.complete(false) }) { Text(stringResource(R.string.cancel)) } }
    )
}
