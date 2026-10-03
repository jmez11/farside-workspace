@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.multidesk.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.multidesk.explorer.data.ConnectionProfile
import com.multidesk.explorer.data.ConnectionType
import java.util.UUID

@Composable
fun LauncherScreen(
    profiles: List<ConnectionProfile>,
    onAddProfile: (ConnectionProfile) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var isAdding by remember { mutableStateOf(false) }

    if (isAdding) {
        AddProfileForm(
            onSave = {
                onAddProfile(it)
                isAdding = false
            },
            onCancel = { isAdding = false }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("MultiDesk Connections", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(24.dp))
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(profiles) { profile ->
                    ProfileCard(profile = profile, onDelete = { onDeleteProfile(profile.id) })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            ExtendedFloatingActionButton(
                onClick = { isAdding = true },
                icon = { Icon(Icons.Default.Add, "Add") },
                text = { Text("New Connection") }
            )
        }
    }
}

@Composable
fun ProfileCard(profile: ConnectionProfile, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.padding(8.dp).fillMaxWidth().height(140.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (profile.type == ConnectionType.RDP) Icons.Default.Settings else Icons.Default.Menu,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AddProfileForm(onSave: (ConnectionProfile) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("My Desktop") }
    var type by remember { mutableStateOf(ConnectionType.RDP) }
    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("3389") }
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("") }
    
    var isAdvanced by remember { mutableStateOf(false) }
    var useCloudflare by remember { mutableStateOf(false) }
    var remoteApp by remember { mutableStateOf("") }
    var enableCamera by remember { mutableStateOf(false) }
    var cameraId by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Add New Connection", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Row {
            FilterChip(
                selected = type == ConnectionType.RDP,
                onClick = { type = ConnectionType.RDP; port = "3389" },
                label = { Text("RDP") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = type == ConnectionType.SSH,
                onClick = { type = ConnectionType.SSH; port = "22" },
                label = { Text("SSH") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") })
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Hostname / IP") })
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") })
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = isAdvanced, onCheckedChange = { isAdvanced = it })
            Spacer(modifier = Modifier.width(8.dp))
            Text("Advanced Options")
        }

        if (isAdvanced) {
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useCloudflare, onCheckedChange = { useCloudflare = it })
                Text("Cloudflared Tunnel")
            }
            if (type == ConnectionType.RDP) {
                OutlinedTextField(value = remoteApp, onValueChange = { remoteApp = it }, label = { Text("RemoteApp (e.g. ||cmd)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableCamera, onCheckedChange = { enableCamera = it })
                    Text("Enable Webcam")
                    if (enableCamera) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(value = cameraId, onValueChange = { cameraId = it }, label = { Text("Camera ID") })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Row {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                onSave(
                    ConnectionProfile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        type = type,
                        host = host,
                        port = port.toIntOrNull() ?: if (type == ConnectionType.RDP) 3389 else 22,
                        user = user,
                        pass = pass,
                        remoteApp = remoteApp.takeIf { it.isNotBlank() },
                        useCloudflare = useCloudflare,
                        enableCamera = enableCamera,
                        cameraId = cameraId.takeIf { it.isNotBlank() }
                    )
                )
            }) {
                Text("Save Connection")
            }
        }
    }
}
