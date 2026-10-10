package com.farside.workspace.ui

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
fun AdbTunnelSection(discovery: (suspend () -> AdbState)? = null) {
    val context = LocalContext.current
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
    Spacer(Modifier.height(16.dp))
    SettingsHeader("Wireless ADB Cloudflare Tunnel")
    if (!state.wireless) {
        Text("Enable wireless debugging on the headset to set up the tunnel.", color = Color.Gray, modifier = Modifier.padding(16.dp))
    } else {
        Text(state.port?.let { "Wireless ADB detected on port $it" } ?: "Discovering this headset's wireless ADB endpoint…",
            color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
        SettingsTextField(token, { token = it; error = null }, if (saved == null) "Cloudflare tunnel token" else "New tunnel token (blank keeps saved token)",
            visualTransformation = PasswordVisualTransformation())
        SettingsTextField(hostname, { hostname = it; error = null }, "Published hostname (e.g. quest-adb.example.com)")
        SettingsTextField(port, { port = it; error = null }, "Bridge port (e.g. 5555)",
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        Text("In Cloudflare's tunnel dashboard, publish this hostname as TCP to 127.0.0.1:$port. The headset keeps that bridge connected to the current wireless ADB port.",
            color = Color.Gray, modifier = Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            Button(enabled = state.port != null && !active, onClick = {
                try {
                    val config = AdbTunnelConfig(token.ifBlank { store.load()?.token ?: "" }, hostname, port.toIntOrNull() ?: 0).validate()
                    require(config.port != state.port) { "Bridge port must differ from the wireless ADB port." }
                    store.save(config)
                    saved = config
                    preview = config.yaml()
                    token = ""
                    AdbTunnelService.start(context)
                    active = true
                    error = null
                } catch (failure: Exception) { error = failure.message ?: "Could not start the tunnel." }
            }) { Text("Save & start tunnel") }
            if (active) TextButton(onClick = { AdbTunnelService.stop(context); active = false }) { Text("Stop tunnel") }
        }
        preview?.let { yaml ->
            Text("config.yml (mirror this ingress in the Cloudflare dashboard):\n$yaml", color = Color.Gray, modifier = Modifier.padding(16.dp))
            saved?.let { config ->
                Text("On your computer:\n${config.clientCommand()}\nadb connect 127.0.0.1:${config.port}",
                    color = Color.White, modifier = Modifier.padding(16.dp))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
    }
    Text(status, color = Color.White, modifier = Modifier.padding(16.dp))
    if (active && !state.wireless) TextButton(onClick = { AdbTunnelService.stop(context); active = false }) { Text("Stop tunnel") }
}
