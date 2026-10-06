@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.multidesk.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multidesk.explorer.data.ConnectionProfile
import com.multidesk.explorer.data.ConnectionType
import kotlinx.coroutines.launch
import java.util.UUID



@Composable
fun LauncherScreen(
    profiles: List<ConnectionProfile>,
    onAddProfile: (ConnectionProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onConnectProfile: (String) -> Unit,
    profileManager: com.multidesk.explorer.data.ProfileManager
) {
    var isAdding by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsInitialTab by rememberSaveable { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isAdding || editingProfile != null) {
            AddProfileForm(
                initialProfile = editingProfile,
                onSave = {
                    onAddProfile(it)
                    isAdding = false
                    editingProfile = null
                },
                onCancel = { 
                    isAdding = false 
                    editingProfile = null
                },
                profileManager = profileManager,
                onNavigateToSettings = {
                    settingsInitialTab = 1
                    isSettingsOpen = true
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("MultiDesk Connections", style = MaterialTheme.typography.headlineLarge)
                    IconButton(
                        onClick = { 
                            settingsInitialTab = 0
                            isSettingsOpen = true 
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(profiles) { profile ->
                        ProfileCard(
                            profile = profile,
                            onEdit = { editingProfile = profile },
                            onDelete = { onDeleteProfile(profile.id) },
                            onConnect = { onConnectProfile(profile.id) }
                        )
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
        
        if (isSettingsOpen) {
            SettingsScreen(
                onClose = { isSettingsOpen = false },
                profileManager = profileManager,
                initialTab = settingsInitialTab
            )
        }
    }
}

@Composable
fun ProfileCard(profile: ConnectionProfile, onEdit: () -> Unit, onDelete: () -> Unit, onConnect: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.padding(8.dp).fillMaxWidth().height(140.dp).clickable { onConnect() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (profile.type == ConnectionType.RDP) Icons.Default.Computer else Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}



@Composable
fun SettingsHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = Color(0xFF1E88E5), // Blue accent
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTextField(
    value: String, 
    onValueChange: (String) -> Unit, 
    placeholder: String = "", 
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    isError: Boolean = false
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.Gray) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF1C1C1E),
            unfocusedContainerColor = Color(0xFF1C1C1E),
            focusedIndicatorColor = Color(0xFF1E88E5),
            unfocusedIndicatorColor = Color.DarkGray,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = true,
        isError = isError
    )
}

@Composable
fun SettingsScreen(onClose: () -> Unit, profileManager: com.multidesk.explorer.data.ProfileManager, initialTab: Int = 0) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var cloudflarePersistent by rememberSaveable { mutableStateOf(false) }
    var cloudflareTunnelId by rememberSaveable { mutableStateOf("") }
    
    var credentials by remember { mutableStateOf(profileManager.getCredentials()) }
    var newCredName by rememberSaveable { mutableStateOf("") }
    var newCredUser by rememberSaveable { mutableStateOf("") }
    var newCredPass by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Text("<", color = Color(0xFF1E88E5), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("App Settings", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        
        Divider(color = Color.DarkGray, thickness = 1.dp)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Black,
                    contentColor = Color.White
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Tunnels & Global") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Credentials") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("About") })
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                when (selectedTab) {
                    0 -> {
                        SettingsHeader("Cloudflared Tunnel Service")
                        SettingsSwitchRow("Enable Persistent Service", cloudflarePersistent, { cloudflarePersistent = it })
                        SettingsTextField(cloudflareTunnelId, { cloudflareTunnelId = it }, "Tunnel ID (e.g. 1234abcd-...)")
                    }
                    1 -> {
                        SettingsHeader("User Management")
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    credentials.forEach { cred ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(cred.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(cred.user, color = Color.Gray, fontSize = 12.sp)
                            }
                            TextButton(onClick = { 
                                profileManager.deleteCredential(cred.id)
                                credentials = profileManager.getCredentials()
                            }) {
                                Text("Delete", color = Color.Red)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add New Credential", color = Color(0xFF1E88E5), fontSize = 14.sp)
                    SettingsTextField(newCredName, { newCredName = it }, "Account Display Name")
                    SettingsTextField(newCredUser, { newCredUser = it }, "Username")
                    SettingsTextField(newCredPass, { newCredPass = it }, "Password", visualTransformation = PasswordVisualTransformation())
                    TextButton(onClick = {
                        if (newCredName.isNotBlank() && newCredUser.isNotBlank()) {
                            profileManager.addCredential(com.multidesk.explorer.data.SavedCredential(name = newCredName, user = newCredUser, pass = newCredPass))
                            credentials = profileManager.getCredentials()
                            newCredName = ""
                            newCredUser = ""
                            newCredPass = ""
                        }
                    }, modifier = Modifier.align(Alignment.End)) {
                        Text("Save Credential", color = Color(0xFF1E88E5))
                    }
                        }
                    }
                    2 -> {
                        SettingsHeader("About & Open Source Licenses")
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("MultiDesk Explorer", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Licenses in use:", color = Color.LightGray, fontWeight = FontWeight.Bold)
                            Text("• FreeRDP 3.x (Apache-2.0)", color = Color.Gray)
                            Text("• SSHJ (Apache-2.0)", color = Color.Gray)
                            Text("• Termux Terminal Emulator (GPLv3)", color = Color.Gray)
                            Text("• Jetpack Compose & Kotlin (Apache-2.0)", color = Color.Gray)
                            Text("• Meta Spatial SDK (Proprietary)", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .background(Color(0xFF1C1C1E))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Switch(
            modifier = Modifier.semantics { contentDescription = label },
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1E88E5),
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.DarkGray
            )
        )
    }
}

@Composable
fun SettingsSegmentedControl(selectedType: ConnectionType, onTypeSelected: (ConnectionType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        FilterChip(
            selected = selectedType == ConnectionType.RDP,
            onClick = { onTypeSelected(ConnectionType.RDP) },
            label = { Text("RDP") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF1E88E5),
                selectedLabelColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(16.dp))
        FilterChip(
            selected = selectedType == ConnectionType.SSH,
            onClick = { onTypeSelected(ConnectionType.SSH) },
            label = { Text("SSH") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF1E88E5),
                selectedLabelColor = Color.White
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyTypeSelector(selectedType: com.multidesk.explorer.data.ProxyType, onTypeSelected: (com.multidesk.explorer.data.ProxyType) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        TextField(
            value = selectedType.name,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedIndicatorColor = Color(0xFF1E88E5),
                unfocusedIndicatorColor = Color.DarkGray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            com.multidesk.explorer.data.ProxyType.values().forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption.name) },
                    onClick = {
                        onTypeSelected(selectionOption)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSelector(selectedCameraId: String?, onCameraSelected: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val cameraList = remember { 
        try {
            cameraManager.cameraIdList.map { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when(facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Unknown"
                }
                id to "Camera $id ($facingStr)"
            }
        } catch (e: Exception) {
            emptyList<Pair<String, String>>()
        }
    }
    
    val displayValue = cameraList.find { it.first == selectedCameraId }?.second ?: (selectedCameraId ?: "Select Camera...")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        TextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedIndicatorColor = Color(0xFF1E88E5),
                unfocusedIndicatorColor = Color.DarkGray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            cameraList.forEach { (id, desc) ->
                DropdownMenuItem(
                    text = { Text(desc) },
                    onClick = {
                        onCameraSelected(id)
                        expanded = false
                    }
                )
            }
            if (cameraList.isEmpty()) {
                DropdownMenuItem(text = { Text("No cameras detected") }, onClick = { expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolutionDropdown(selectedRes: String, onResSelected: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val resolutions = listOf("1368 x 768", "1600 x 900", "1920 x 1080", "2000 x 1124", "2400 x 1348", "2800 x 1572", "3200 x 1800")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        TextField(
            value = selectedRes,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedIndicatorColor = Color(0xFF1E88E5),
                unfocusedIndicatorColor = Color.DarkGray,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            resolutions.forEach { res ->
                DropdownMenuItem(
                    text = { Text(res) },
                    onClick = {
                        onResSelected(res)
                        expanded = false
                    },
                    trailingIcon = {
                        RadioButton(selected = selectedRes == res, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1E88E5)))
                    }
                )
            }
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AddProfileForm(
    initialProfile: ConnectionProfile? = null,
    onSave: (ConnectionProfile) -> Unit, 
    onCancel: () -> Unit, 
    profileManager: com.multidesk.explorer.data.ProfileManager,
    onNavigateToSettings: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialProfile?.name ?: "") }
    var type by rememberSaveable { mutableStateOf(initialProfile?.type ?: ConnectionType.RDP) }
    var host by rememberSaveable { mutableStateOf(initialProfile?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(initialProfile?.port?.toString() ?: "") }
    var user by rememberSaveable { mutableStateOf(initialProfile?.user ?: "") }
    var pass by rememberSaveable { mutableStateOf(initialProfile?.pass ?: "") }
    
    var remoteApp by rememberSaveable { mutableStateOf(initialProfile?.remoteApp ?: "") }
    var enableCamera by rememberSaveable { mutableStateOf(initialProfile?.enableCamera ?: true) }
    var cameraId by rememberSaveable { mutableStateOf(initialProfile?.cameraId ?: "") }
    
    var discoveredHosts by remember { mutableStateOf<List<com.multidesk.explorer.utils.DiscoveredHost>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var proxyType by rememberSaveable { mutableStateOf(initialProfile?.proxyType ?: com.multidesk.explorer.data.ProxyType.NONE) }
    var proxyHost by rememberSaveable { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by rememberSaveable { mutableStateOf(initialProfile?.proxyPort?.toString() ?: "") }
    var proxyUser by rememberSaveable { mutableStateOf(initialProfile?.proxyUser ?: "") }
    var proxyPass by rememberSaveable { mutableStateOf(initialProfile?.proxyPass ?: "") }
    var proxyExclude by rememberSaveable { mutableStateOf(initialProfile?.proxyExclude ?: "") }
    var proxyCommand by rememberSaveable { mutableStateOf(initialProfile?.proxyCommand ?: "") }
    
    // RDP Specific Options
    var soundRedirection by rememberSaveable { mutableStateOf(initialProfile?.soundRedirection ?: com.multidesk.explorer.data.SoundRedirection.PLAY_ON_DEVICE) }
    var redirectMicrophone by rememberSaveable {
        mutableStateOf((initialProfile?.redirectMicrophone ?: true) && com.multidesk.explorer.hasMicrophonePermission(context))
    }
    val microphonePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> redirectMicrophone = granted && com.multidesk.explorer.hasMicrophonePermission(context) }
    var redirectStorage by rememberSaveable { mutableStateOf(initialProfile?.redirectStorage ?: true) }
    var redirectClipboard by rememberSaveable { mutableStateOf(initialProfile?.redirectClipboard ?: true) }
    var redirectLocation by rememberSaveable { mutableStateOf(initialProfile?.redirectLocation ?: true) }
    var customResolution by rememberSaveable { mutableStateOf(initialProfile?.customResolution ?: false) }
    var matchDevice by rememberSaveable { mutableStateOf(initialProfile?.matchDevice ?: true) }
    var useDefaultResolution by rememberSaveable { mutableStateOf(initialProfile?.useDefaultResolution ?: false) }
    var resolutionDropdown by rememberSaveable { mutableStateOf("${initialProfile?.resolutionWidth ?: 1920} x ${initialProfile?.resolutionHeight ?: 1080}") }
    var scaling by rememberSaveable { mutableFloatStateOf(initialProfile?.resolutionScaling?.toFloat() ?: 100f) }
    var swapMouseButtons by rememberSaveable { mutableStateOf(initialProfile?.swapMouseButtons ?: false) }
    var adminMode by rememberSaveable { mutableStateOf(initialProfile?.adminMode ?: false) }
    
    var resolutionSubMenuOpen by rememberSaveable { mutableStateOf(false) }
    var showValidationErrors by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (resolutionSubMenuOpen) {
                IconButton(onClick = { resolutionSubMenuOpen = false }) {
                    Text("<", color = Color(0xFF1E88E5), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text("Customize display resolution", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(48.dp))
            } else {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF1E88E5))
                }
                Text("Add Connection", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    val creds = profileManager.getCredentials()
                    val selectedCred = creds.find { it.user == user && it.pass == pass }
                    val credSelected = selectedCred != null || (user.isNotBlank())
                    if (host.isBlank() || !credSelected) {
                        showValidationErrors = true
                        return@TextButton
                    }
                    
                    onSave(
                        ConnectionProfile(
                            id = initialProfile?.id ?: UUID.randomUUID().toString(),
                        name = name.takeIf { it.isNotBlank() } ?: "Unnamed",
                        type = type,
                        host = host,
                        port = port.toIntOrNull() ?: if (type == ConnectionType.RDP) 3389 else 22,
                        user = user,
                        pass = pass,
                        remoteApp = remoteApp.takeIf { it.isNotBlank() },
                        useCloudflare = false,
                        enableCamera = enableCamera,
                        cameraId = cameraId.takeIf { it.isNotBlank() },
                        proxyType = proxyType,
                        proxyHost = proxyHost.takeIf { it.isNotBlank() },
                        proxyPort = proxyPort.toIntOrNull(),
                        proxyUser = proxyUser.takeIf { it.isNotBlank() },
                        proxyPass = proxyPass.takeIf { it.isNotBlank() },
                        proxyExclude = proxyExclude.takeIf { it.isNotBlank() },
                        proxyCommand = proxyCommand.takeIf { it.isNotBlank() },
                        soundRedirection = soundRedirection,
                        redirectMicrophone = redirectMicrophone && com.multidesk.explorer.hasMicrophonePermission(context),
                        redirectStorage = redirectStorage,
                        redirectClipboard = redirectClipboard,
                        redirectLocation = redirectLocation,
                        customResolution = customResolution,
                        matchDevice = matchDevice,
                        useDefaultResolution = useDefaultResolution,
                        resolutionWidth = if (matchDevice) 0 else if (useDefaultResolution) 1920 else (resolutionDropdown.substringBefore(" x ").toIntOrNull() ?: 1920),
                        resolutionHeight = if (matchDevice) 0 else if (useDefaultResolution) 1080 else (resolutionDropdown.substringAfter(" x ").toIntOrNull() ?: 1080),
                        resolutionScaling = scaling.toInt(),
                        swapMouseButtons = swapMouseButtons,
                        adminMode = adminMode
                    )
                )
            }) {
                Text("SAVE", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
            }
        }
        }
        
        Divider(color = Color.DarkGray, thickness = 1.dp)

        if (resolutionSubMenuOpen) {
            // Resolution Submenu
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 700.dp)
                        .fillMaxHeight()
                        .padding(top = 16.dp, bottom = 32.dp)
                ) {
                    SettingsSwitchRow("On", customResolution, { customResolution = it })
                    if (customResolution) {
                        // Match this device
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { matchDevice = true; useDefaultResolution = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = matchDevice && !useDefaultResolution, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1E88E5), unselectedColor = Color.Gray))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Match this device", color = Color.White, fontSize = 16.sp)
                        }
                        
                        // Default 1920x1080
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { matchDevice = false; useDefaultResolution = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !matchDevice && useDefaultResolution, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1E88E5), unselectedColor = Color.Gray))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Default 1920x1080", color = Color.White, fontSize = 16.sp)
                        }
                        
                        // Custom
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { matchDevice = false; useDefaultResolution = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !matchDevice && !useDefaultResolution, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1E88E5), unselectedColor = Color.Gray))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("+ Custom", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        if (!matchDevice && !useDefaultResolution) {
                            Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp)) {
                                Text("RESOLUTION", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                                ResolutionDropdown(resolutionDropdown, { resolutionDropdown = it })
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("SCALING", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = scaling,
                                    onValueChange = { scaling = it },
                                    valueRange = 100f..300f,
                                    steps = 3,
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFF1E88E5), activeTrackColor = Color(0xFF1E88E5))
                                )
                                Text("${scaling.toInt()} %", color = Color.White, modifier = Modifier.align(Alignment.End))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("These settings affect the amount of data used and performance. Scaling is available beginning with Windows 8.1 and Windows Server 2012 R2.", color = Color.Gray, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(24.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { resolutionSubMenuOpen = false }) {
                                        Text("CANCEL", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    TextButton(onClick = { resolutionSubMenuOpen = false }) {
                                        Text("SAVE", color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Settings List centered in a wide window
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                SettingsSegmentedControl(type) { 
                    type = it
                    port = if (it == ConnectionType.RDP) "3389" else "22"
                }

                if (type == ConnectionType.RDP) {

                }

                SettingsHeader("Connection Name")
                SettingsTextField(name, { name = it }, "Enter display name")

                SettingsHeader("Host / Server")
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        isScanning = true
                        coroutineScope.launch {
                            discoveredHosts = com.multidesk.explorer.utils.NetworkScanner.scanLocalNetworkForRdp(context)
                            isScanning = false
                        }
                    }) {
                        Text(if (isScanning) "Scanning subnet for RDP..." else "Click here to Find Local PCs", color = Color(0xFF1E88E5))
                    }
                }
                if (discoveredHosts.isNotEmpty()) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        TextField(
                            value = "Select Discovered PC",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(
                                focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                                focusedIndicatorColor = Color(0xFF1E88E5), unfocusedIndicatorColor = Color.DarkGray,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            discoveredHosts.forEach { foundHost ->
                                DropdownMenuItem(
                                    text = { Text(foundHost.name) },
                                    onClick = {
                                        if (name.isEmpty() || name == "New Profile" || name == host) {
                                            name = foundHost.name
                                        }
                                        host = foundHost.ip
                                        port = "3389"
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                SettingsTextField(host, { host = it }, "Hostname or IP address", isError = showValidationErrors && host.isBlank())
                SettingsTextField(
                    value = port, 
                    onValueChange = { port = it }, 
                    placeholder = "Port (e.g. 3389 for RDP or 22 for SSH)",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                SettingsHeader("User Account")
                val creds = remember { profileManager.getCredentials() }
                // Pre-select credential if editing
                var savedCredId by rememberSaveable { 
                    mutableStateOf(
                        initialProfile?.let { prof -> creds.find { it.user == prof.user && it.pass == prof.pass }?.id } ?: ""
                    ) 
                }
                var expandedCreds by rememberSaveable { mutableStateOf(false) }
                
                ExposedDropdownMenuBox(
                    expanded = expandedCreds,
                    onExpandedChange = { expandedCreds = !expandedCreds },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    TextField(
                        value = creds.find { it.id == savedCredId }?.name ?: "Select Saved Credential",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCreds) },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                            focusedIndicatorColor = Color(0xFF1E88E5), unfocusedIndicatorColor = Color.DarkGray,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        isError = showValidationErrors && savedCredId.isBlank()
                    )
                    ExposedDropdownMenu(expanded = expandedCreds, onDismissRequest = { expandedCreds = false }) {
                        creds.forEach { cred ->
                            DropdownMenuItem(
                                text = { Text(cred.name) },
                                onClick = {
                                    savedCredId = cred.id
                                    user = cred.user
                                    pass = cred.pass
                                    expandedCreds = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("+ Add New Credential", color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                expandedCreds = false
                                onNavigateToSettings()
                            }
                        )
                    }
                }
                
                if (type == ConnectionType.RDP) {
                    SettingsHeader("General")
                    SettingsSwitchRow("Admin mode", adminMode, { adminMode = it })
                    SettingsSwitchRow("Swap mouse buttons", swapMouseButtons, { swapMouseButtons = it })
                }
                
                SettingsHeader("Proxy Settings")
                ProxyTypeSelector(proxyType, { proxyType = it })
                if (proxyType == com.multidesk.explorer.data.ProxyType.LOCAL) {
                    SettingsTextField(proxyCommand, { proxyCommand = it }, "Local Command (e.g. cloudflared access tcp...)")
                } else if (proxyType != com.multidesk.explorer.data.ProxyType.NONE) {
                    SettingsTextField(proxyHost, { proxyHost = it }, "Proxy Hostname or IP")
                    SettingsTextField(proxyPort, { proxyPort = it }, "Proxy Port")
                    SettingsTextField(proxyUser, { proxyUser = it }, "Proxy Username (Optional)")
                    SettingsTextField(proxyPass, { proxyPass = it }, "Proxy Password (Optional)", visualTransformation = PasswordVisualTransformation())
                    SettingsTextField(proxyExclude, { proxyExclude = it }, "Exclude Hosts/IPs (comma separated)")
                }

                if (type == ConnectionType.RDP) {
                    SettingsHeader("Device & Audio Redirection")
                    // We can reuse ProxyTypeSelector logic for SoundRedirection dropdown if we wanted, or just a simple segmented control.
                    SettingsTextField(remoteApp, { remoteApp = it }, "RemoteApp Alias (e.g. ||cmd)")
                    SettingsSwitchRow("Redirect Microphone", redirectMicrophone, { enabled ->
                        if (!enabled) redirectMicrophone = false
                        else if (com.multidesk.explorer.hasMicrophonePermission(context)) redirectMicrophone = true
                        else microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    })
                    SettingsSwitchRow("Redirect Camera", enableCamera, { enableCamera = it })
                    if (enableCamera) {
                        CameraSelector(selectedCameraId = cameraId.takeIf { it.isNotBlank() }, onCameraSelected = { cameraId = it })
                    }
                    SettingsSwitchRow("Redirect Storage", redirectStorage, { redirectStorage = it })
                    SettingsSwitchRow("Redirect Clipboard", redirectClipboard, { redirectClipboard = it })
                    SettingsSwitchRow("Redirect Location", redirectLocation, { redirectLocation = it })
                    
                    SettingsHeader("Display")
                    // Custom clickable row for Resolution Submenu
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resolutionSubMenuOpen = true }
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .background(Color(0xFF1C1C1E))
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Customize display resolution", color = Color.White, fontSize = 16.sp)
                            val displayResText = if (customResolution) {
                                if (matchDevice) "Match this device" else if (useDefaultResolution) "Default 1920x1080" else "Custom ($resolutionDropdown)"
                            } else "Off"
                            Text(displayResText, color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
}
