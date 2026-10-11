package com.farside.workspace.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.farside.workspace.data.*

@Composable
fun SshForwardingDialog(rules: List<SshForward>, statuses: Map<String, String> = emptyMap(),
    onChange: (List<SshForward>) -> Unit, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<SshForward?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Port forwarding") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tunnels start with this SSH connection. Changes in a session apply now and are saved for next time.")
                if (rules.isEmpty()) Text("No saved tunnels. Add a port or a SOCKS proxy.", style = MaterialTheme.typography.bodyMedium)
                rules.forEach { rule ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rule.kind.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Switch(rule.enabled, onCheckedChange = { enabled ->
                                    val next = rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }
                                    error = runCatching { SshForward.validateList(next); onChange(next) }.exceptionOrNull()?.message
                                })
                            }
                            Text(rule.route)
                            Text("${rule.family.label} · ${if (rule.allowOtherHosts) "Other hosts allowed" else "Loopback only"} · ${statuses[rule.id] ?: if (rule.enabled) "Saved" else "Disabled"}", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { editing = rule }) { Text("Edit tunnel") }
                                TextButton(onClick = { onChange(rules.filterNot { it.id == rule.id }); error = null }) { Text("Remove tunnel") }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { editing = SshForward() }) { Text("Add tunnel") }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    editing?.let { original ->
        key(original.id) {
            ForwardEditor(original, onCancel = { editing = null }, onSave = { rule ->
                val next = if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule
                SshForward.validateList(next)
                onChange(next)
                editing = null
            })
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ForwardEditor(original: SshForward, onCancel: () -> Unit, onSave: (SshForward) -> Unit) {
    var kind by remember { mutableStateOf(original.kind) }
    var family by remember { mutableStateOf(original.family) }
    var source by remember { mutableStateOf(original.sourcePort.toString()) }
    var host by remember { mutableStateOf(original.destinationHost) }
    var port by remember { mutableStateOf(original.destinationPort.toString()) }
    var otherHosts by remember { mutableStateOf(original.allowOtherHosts) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Configure tunnel") }, text = {
        Column(Modifier.width(560.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForwardKind.entries.forEach { FilterChip(kind == it, onClick = { kind = it }, label = { Text(it.label) }) }
            }
            Text(when (kind) {
                ForwardKind.LOCAL -> "Listen on Quest. The SSH server connects to the destination."
                ForwardKind.REMOTE -> "Listen on the SSH server. Quest connects to the destination."
                ForwardKind.DYNAMIC -> "Listen on Quest as a SOCKS5 proxy. Each app chooses its destination through SSH (TCP only)."
            })
            OutlinedTextField(source, { source = it }, label = { Text("Source port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (kind != ForwardKind.DYNAMIC) {
                OutlinedTextField(host, { host = it }, label = { Text("Destination host") }, supportingText = { Text(if (kind == ForwardKind.LOCAL) "localhost means the SSH server" else "localhost means Quest") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("Destination port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            Text("Listener address family", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForwardFamily.entries.forEach { FilterChip(family == it, onClick = { family = it }, label = { Text(it.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow connections from other hosts", Modifier.weight(1f))
                Switch(otherHosts, onCheckedChange = { otherHosts = it })
            }
            if (kind == ForwardKind.REMOTE) Text("The server must permit remote forwarding. Other-host access also requires its GatewayPorts setting.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }, confirmButton = {
        TextButton(onClick = {
            error = runCatching {
                val rule = original.copy(kind = kind, family = family, sourcePort = source.toIntOrNull() ?: 0,
                    destinationHost = host.trim(), destinationPort = port.toIntOrNull() ?: 0, allowOtherHosts = otherHosts)
                rule.validate(); onSave(rule)
            }.exceptionOrNull()?.message
        }) { Text("Save tunnel") }
    })
}
