package com.farside.workspace.ui

import com.farside.workspace.R
import com.farside.workspace.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.farside.workspace.tunnel.*
import kotlinx.coroutines.*

@Composable
fun AdbTunnelSection(compact:Boolean=false,discovery: (suspend () -> AdbState)? = null) {
    val context = LocalContext.current
    val portConflictMsg = stringResource(R.string.adb_bridge_port_conflict)
    val tunnelStartFailedMsg = stringResource(R.string.tunnel_start_failed)
    val store = remember { AdbTunnelSettings(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var state by remember { mutableStateOf(AdbDiscovery.flags(context)) }
    var token by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf(saved?.hostname ?: "") }
    var port by remember { mutableStateOf((saved?.port ?: 5555).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(saved?.yaml()) }
    var active by remember { mutableStateOf(AdbTunnelService.running) }
    val status by AdbTunnelService.status.collectAsState()
    LaunchedEffect(Unit) {
        while (isActive) {
            state = withContext(Dispatchers.IO) { runCatching { discovery?.invoke() ?: AdbDiscovery.discover(context, 3000) }.getOrDefault(AdbState(false, false)) }
            active = AdbTunnelService.running
            delay(3000)
        }
    }
    if (!state.developer) return
    if(!compact) {Spacer(Modifier.height(16.dp));SettingsHeader(stringResource(R.string.wireless_adb_cloudflare_tunnel))}
    if (!state.wireless) {
        Text(stringResource(R.string.enable_wireless_debugging_help), color = Color.Gray, modifier = if(compact) Modifier else Modifier.padding(16.dp))
    }
    if(state.wireless || compact) {
        Text(state.port?.let { stringResource(R.string.wireless_adb_detected, it) } ?: stringResource(R.string.discovering_wireless_adb),
            color = Color.White, modifier = if(compact) Modifier else Modifier.padding(horizontal = 16.dp))
        if(compact) com.farside.workspace.ui.setup.SetupInput(if(saved==null) R.string.cloudflare_tunnel_token else R.string.new_tunnel_token,token,{token=it;error=null},secret=true)
        else SettingsTextField(token,{token=it;error=null},if(saved==null) stringResource(R.string.cloudflare_tunnel_token) else stringResource(R.string.new_tunnel_token),visualTransformation=PasswordVisualTransformation())
        if(compact) com.farside.workspace.ui.setup.SetupInput(R.string.published_hostname_hint,hostname,{hostname=it;error=null})
        else SettingsTextField(hostname, { hostname = it; error = null }, stringResource(R.string.published_hostname_hint))
        if(compact) com.farside.workspace.ui.setup.SetupInput(R.string.bridge_port_hint,port,{port=it;error=null})
        else SettingsTextField(port, { port = it; error = null }, stringResource(R.string.bridge_port_hint),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        Text(stringResource(R.string.adb_bridge_help, port),
            color = Color.Gray, modifier = if(compact) Modifier else Modifier.padding(16.dp))
        Row(if(compact) Modifier else Modifier.padding(horizontal = 16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(shape=androidx.compose.foundation.shape.RoundedCornerShape(if(compact) 8.dp else 20.dp),enabled = state.port != null && !active, onClick = {
                try {
                    val config = AdbTunnelConfig(token.ifBlank { store.load()?.token ?: "" }, hostname, port.toIntOrNull() ?: 0).validate()
                    require(config.port != state.port) { portConflictMsg }
                    store.save(config)
                    saved = config
                    preview = config.yaml()
                    token = ""
                    AdbTunnelService.start(context)
                    active = true
                    error = null
                } catch (failure: Exception) { error = failure.localizedMessage(context) ?: tunnelStartFailedMsg }
            }) { Text(stringResource(R.string.save_start_tunnel)) }
            if (active || compact) OutlinedButton(shape=androidx.compose.foundation.shape.RoundedCornerShape(if(compact) 8.dp else 20.dp),enabled=active,onClick = { AdbTunnelService.stop(context); active = false }) { Text(stringResource(R.string.stop_tunnel)) }
        }
        preview?.takeIf {!compact}?.let { yaml ->
            Text(stringResource(R.string.adb_tunnel_config_preview, yaml), color = Color.Gray, modifier = if(compact) Modifier else Modifier.padding(16.dp))
            saved?.let { config ->
                Text(stringResource(R.string.adb_tunnel_client_instructions, config.clientCommand(), config.port),
                    color = Color.White, modifier = if(compact) Modifier else Modifier.padding(16.dp))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = if(compact) Modifier else Modifier.padding(16.dp)) }
    }
    Text(if (status.isEmpty()) stringResource(R.string.stopped) else status, color = Color.White, modifier = if(compact) Modifier else Modifier.padding(16.dp))
    if (active && !state.wireless) OutlinedButton(onClick = { AdbTunnelService.stop(context); active = false }) { Text(stringResource(R.string.stop_tunnel)) }
}
