package com.farside.workspace.ui

import com.farside.workspace.R
import com.farside.workspace.localizedMessage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
    val context = LocalContext.current
    var editing by remember { mutableStateOf<SshForward?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.port_forwarding)) },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ssh_forwarding_help))
                if (rules.isEmpty()) Text(stringResource(R.string.no_saved_tunnels), style = MaterialTheme.typography.bodyMedium)
                rules.forEach { rule ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(rule.kind.labelResId), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Switch(rule.enabled, onCheckedChange = { enabled ->
                                    val next = rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }
                                    error = runCatching { SshForward.validateList(next); onChange(next) }.exceptionOrNull()?.localizedMessage(context)
                                })
                            }
                            Text(stringResource(rule.routeResId, *rule.routeFormatArgs))
                            Text(stringResource(R.string.forwarding_rule_status, stringResource(rule.family.labelResId), if (rule.allowOtherHosts) stringResource(R.string.other_hosts_allowed) else stringResource(R.string.loopback_only), statuses[rule.id] ?: if (rule.enabled) stringResource(R.string.saved) else stringResource(R.string.disabled)), style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { editing = rule }) { Text(stringResource(R.string.edit_tunnel)) }
                                TextButton(onClick = { onChange(rules.filterNot { it.id == rule.id }); error = null }) { Text(stringResource(R.string.remove_tunnel)) }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { editing = SshForward() }) { Text(stringResource(R.string.add_tunnel)) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } })
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
    val context = LocalContext.current
    var kind by remember { mutableStateOf(original.kind) }
    var family by remember { mutableStateOf(original.family) }
    var source by remember { mutableStateOf(original.sourcePort.toString()) }
    var host by remember { mutableStateOf(original.destinationHost) }
    var port by remember { mutableStateOf(original.destinationPort.toString()) }
    var otherHosts by remember { mutableStateOf(original.allowOtherHosts) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.configure_tunnel)) }, text = {
        Column(Modifier.width(560.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForwardKind.entries.forEach { FilterChip(kind == it, onClick = { kind = it }, label = { Text(stringResource(it.labelResId)) }, colors = connectionChipColors()) }
            }
            Text(when (kind) {
                ForwardKind.LOCAL -> stringResource(R.string.local_forward_help)
                ForwardKind.REMOTE -> stringResource(R.string.remote_forward_help)
                ForwardKind.DYNAMIC -> stringResource(R.string.dynamic_forward_help)
            })
            com.farside.workspace.FormTextMenus { OutlinedTextField(source, { source = it }, label = { Text(stringResource(R.string.source_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
            if (kind != ForwardKind.DYNAMIC) {
                com.farside.workspace.FormTextMenus { OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.destination_host)) }, supportingText = { Text(if (kind == ForwardKind.LOCAL) stringResource(R.string.local_forward_localhost_help) else stringResource(R.string.remote_forward_localhost_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                com.farside.workspace.FormTextMenus { OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.destination_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
            }
            Text(stringResource(R.string.listener_address_family), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForwardFamily.entries.forEach { FilterChip(family == it, onClick = { family = it }, label = { Text(stringResource(it.labelResId)) }, colors = connectionChipColors()) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.allow_connections_from_other_hosts), Modifier.weight(1f))
                Switch(otherHosts, onCheckedChange = { otherHosts = it })
            }
            if (kind == ForwardKind.REMOTE) Text(stringResource(R.string.remote_forward_server_help), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }, confirmButton = {
        TextButton(onClick = {
            error = runCatching {
                val rule = original.copy(kind = kind, family = family, sourcePort = source.toIntOrNull() ?: 0,
                    destinationHost = host.trim(), destinationPort = port.toIntOrNull() ?: 0, allowOtherHosts = otherHosts)
                rule.validate(); onSave(rule)
            }.exceptionOrNull()?.localizedMessage(context)
        }) { Text(stringResource(R.string.save_tunnel)) }
    })
}
