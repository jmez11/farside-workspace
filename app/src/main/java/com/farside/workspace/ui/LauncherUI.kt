@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.farside.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.farside.workspace.R
import com.farside.workspace.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import com.farside.workspace.ConnectionSpatialDrag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.data.ConnectionProfile
import androidx.compose.material.icons.filled.DesktopMac
import com.farside.workspace.data.ConnectionType
import kotlinx.coroutines.launch
import java.util.UUID



@Composable
fun LauncherScreen(
    profiles: List<ConnectionProfile>,
    onAddProfile: (ConnectionProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onConnectProfile: (String) -> Unit,
    profileManager: com.farside.workspace.data.ProfileManager,
    starredIds: Set<String> = emptySet(),
    onToggleStar: (String) -> Unit = {},
    onMoveProfile: (String, String) -> Unit = { _, _ -> }
) {
    var isAdding by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var filesOpen by remember { mutableStateOf(false) }
    var settingsInitialTab by rememberSaveable { mutableStateOf(0) }
    var credentialRevision by remember { mutableIntStateOf(0) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    val dragView = LocalView.current
    DisposableEffect(dragView) {
        val detach = ConnectionSpatialDrag.attach(dragView)
        onDispose { detach() }
    }

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
                credentialRevision = credentialRevision,
                onNavigateToSettings = { tab ->
                    settingsInitialTab = tab
                    isSettingsOpen = true
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.farside_connections), style = MaterialTheme.typography.headlineLarge)
                    IconButton(onClick = { filesOpen = true }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Default.Folder, contentDescription = stringResource(R.string.files), tint = Color.White)
                    }
                    IconButton(
                        onClick = { 
                            settingsInitialTab = 0
                            isSettingsOpen = true 
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (reordering) stringResource(R.string.reorder_hint)
                        else stringResource(R.string.open_or_place_hint),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { reordering = !reordering }, enabled = profiles.size > 1 || reordering) {
                        Text(if (reordering) stringResource(R.string.done_reordering) else stringResource(R.string.reorder))
                    }
                }
                Spacer(Modifier.height(8.dp))
                LauncherConnectionGrid(profiles = profiles, starredIds = starredIds, reordering = reordering,
                    modifier = Modifier.weight(1f), onEdit = { editingProfile = it }, onDelete = onDeleteProfile,
                    onConnect = onConnectProfile, onToggleStar = onToggleStar, onMove = onMoveProfile)

                Spacer(modifier = Modifier.height(16.dp))
                ExtendedFloatingActionButton(
                    onClick = { isAdding = true },
                    icon = { Icon(Icons.Default.Add, stringResource(R.string.add)) },
                    text = { Text(stringResource(R.string.new_connection)) }
                )
            }
        }
        
        if (isSettingsOpen) {
            SettingsScreen(
                onClose = {
                    credentialRevision++
                    isSettingsOpen = false
                },
                profileManager = profileManager,
                initialTab = settingsInitialTab
            )
        }
        if (filesOpen) com.farside.workspace.sftp.SftpFilesEntry(onClose = { filesOpen = false })
    }
}

@Composable
fun ProfileCard(profile: ConnectionProfile, onEdit: () -> Unit, onDelete: () -> Unit, onConnect: () -> Unit,
    reordering: Boolean = false, starred: Boolean = false, onToggleStar: () -> Unit = {},
    dragging: Boolean = false, onMoveEarlier: (() -> Unit)? = null, onMoveLater: (() -> Unit)? = null,
    jumpLabel: String? = null) {
    var menuExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val latestProfile by rememberUpdatedState(profile)
    val launchModifier = if (!reordering) Modifier.clickable { onConnect() }.pointerInput(profile.id, view) {
        detectDragGestures(
            onDragStart = {
                ConnectionSpatialDrag.start(view, latestProfile)
            },
            onDrag = { change, _ -> change.consume() }
        )
    } else Modifier
    
    Card(
        modifier = Modifier.padding(8.dp).fillMaxWidth().heightIn(min = if (reordering) 180.dp else 140.dp).then(launchModifier),
        shape = RoundedCornerShape(12.dp),
        border = if (dragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp,
                    bottom = if (reordering) 48.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when (profile.type) { ConnectionType.RDP -> Icons.Default.Computer; ConnectionType.SSH -> Icons.Default.Terminal; ConnectionType.VNC -> Icons.Default.DesktopMac },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                    Text(stringResource(profile.type.labelResId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
                }
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.connection_address, profile.host, profile.port), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (jumpLabel != null) Text(jumpLabel, style = MaterialTheme.typography.labelSmall,
                    color = if (jumpLabel.startsWith("\u26A0")) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary)
            }
            
            IconButton(onClick = onToggleStar, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (starred) stringResource(R.string.unstar_connection, profile.name) else stringResource(R.string.star_connection, profile.name),
                    tint = if (starred) Color(0xFFFFD166) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reordering) {
                Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                    IconButton(onClick = { onMoveEarlier?.invoke() }, enabled = onMoveEarlier != null) {
                        Icon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.move_connection_earlier, profile.name))
                    }
                    IconButton(onClick = { onMoveLater?.invoke() }, enabled = onMoveLater != null) {
                        Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.move_connection_later, profile.name))
                    }
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
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
    isError: Boolean = false,
    accessibleLabel: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    modifier: Modifier = Modifier
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.Gray) },
        label = if (accessibleLabel != null) { { Text(accessibleLabel, color = Color.Gray) } } else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).then(modifier)
            .semantics { if (accessibleLabel != null) contentDescription = accessibleLabel },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF1C1C1E),
            unfocusedContainerColor = Color(0xFF1C1C1E),
            focusedIndicatorColor = Color(0xFF1E88E5),
            unfocusedIndicatorColor = Color.DarkGray,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        visualTransformation = visualTransformation,
        keyboardOptions = if (visualTransformation is PasswordVisualTransformation)
            keyboardOptions.copy(autoCorrectEnabled = false, keyboardType =
                if (keyboardOptions.keyboardType == androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    androidx.compose.ui.text.input.KeyboardType.NumberPassword else androidx.compose.ui.text.input.KeyboardType.Password)
        else keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError
    )
}

@Composable
fun SettingsScreen(onClose: () -> Unit, profileManager: com.farside.workspace.data.ProfileManager, initialTab: Int = 0) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    
    var appPinVerifier by remember { mutableStateOf(profileManager.getAppPinVerifier()) }
    var systemPinEnabled by remember { mutableStateOf(profileManager.usesSystemPin()) }
    var enablingSystemPin by remember { mutableStateOf(false) }
    var settingAppPin by remember { mutableStateOf(false) }
    var changingAppPin by remember { mutableStateOf(false) }
    var removingAppPin by remember { mutableStateOf(false) }
    var appPinNew by remember { mutableStateOf("") }
    var appPinConfirm by remember { mutableStateOf("") }
    var appPinError by remember { mutableStateOf<String?>(null) }

    var credentials by remember { mutableStateOf(profileManager.getCredentials()) }
    var newCredName by rememberSaveable { mutableStateOf("") }
    var newCredUser by rememberSaveable { mutableStateOf("") }
    var newCredPass by remember { mutableStateOf("") }
    var editingCredentialId by remember { mutableStateOf<String?>(null) }
    var requirePin by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var savingCredential by remember { mutableStateOf(false) }
    var pendingPinDisable by remember { mutableStateOf(false) }
    val credentialScope = rememberCoroutineScope()

    var sshKeys by remember { mutableStateOf(profileManager.getSshKeys()) }
    var newKeyName by rememberSaveable { mutableStateOf("") }
    var newKeyPrivate by remember { mutableStateOf("") }
    var newKeyPassphrase by remember { mutableStateOf("") }
    var editingSshKeyId by remember { mutableStateOf<String?>(null) }
    var keyRequirePin by remember { mutableStateOf(false) }
    var keyPinError by remember { mutableStateOf<String?>(null) }
    var savingSshKey by remember { mutableStateOf(false) }
    var pendingKeyPinDisable by remember { mutableStateOf(false) }
    val sshKeyScope = rememberCoroutineScope()

    fun sshKeyAction(key: com.farside.workspace.data.SavedSshKey, action: String) {
        if (action == "edit") {
            editingSshKeyId = key.id
            newKeyName = key.name
            newKeyPrivate = key.privateKey
            newKeyPassphrase = ""
            keyRequirePin = key.requirePin
        } else {
            profileManager.deleteSshKey(key.id)
            sshKeys = profileManager.getSshKeys()
            if (editingSshKeyId == key.id) {
                editingSshKeyId = null
                newKeyName = ""
                newKeyPrivate = ""
                newKeyPassphrase = ""
                keyRequirePin = false
            }
        }
        keyPinError = null
    }

    fun credentialAction(cred: com.farside.workspace.data.SavedCredential, action: String) {
        if (action == "edit") {
            editingCredentialId = cred.id
            newCredName = cred.name
            newCredUser = cred.user
            newCredPass = ""
            requirePin = cred.requirePin
        } else {
            profileManager.deleteCredential(cred.id)
            credentials = profileManager.getCredentials()
            if (editingCredentialId == cred.id) {
                editingCredentialId = null
                newCredName = ""
                newCredUser = ""
                newCredPass = ""
                requirePin = false
            }
        }
        pinError = null
    }
    if (pendingKeyPinDisable) {
        if (systemPinEnabled) SystemCredentialDialog(title = stringResource(R.string.remove_pin_requirement), allowRemembered = false,
            onUnlocked = { keyRequirePin = false; keyPinError = null; pendingKeyPinDisable = false },
            onCancel = { pendingKeyPinDisable = false }) else
        CredentialPinDialog(
            title = stringResource(R.string.remove_pin_requirement),
            message = stringResource(R.string.enter_the_app_pin_to_remove_pin_requirement),
            onUnlocked = {
                keyRequirePin = false
                keyPinError = null
                pendingKeyPinDisable = false
            },
            onCancel = { pendingKeyPinDisable = false }
        )
    }
    if (pendingPinDisable) {
        if (systemPinEnabled) SystemCredentialDialog(title = stringResource(R.string.remove_pin_requirement), allowRemembered = false,
            onUnlocked = { requirePin = false; pinError = null; pendingPinDisable = false },
            onCancel = { pendingPinDisable = false }) else
        CredentialPinDialog(
            title = stringResource(R.string.remove_pin_requirement),
            message = stringResource(R.string.enter_the_app_pin_to_remove_pin_requirement),
            onUnlocked = {
                requirePin = false
                pinError = null
                pendingPinDisable = false
            },
            onCancel = { pendingPinDisable = false }
        )
    }
    if (enablingSystemPin) {
        SystemCredentialDialog(title = stringResource(R.string.use_headset_passcode), allowRemembered = false,
            message = stringResource(R.string.switch_protected_connections_to_the_quest_headset_passcode_after),
            onUnlocked = {
                profileManager.enableSystemPin()
                systemPinEnabled = true
                appPinVerifier = null
                enablingSystemPin = false
            }, onCancel = { enablingSystemPin = false })
    }
    if (removingAppPin) {
        CredentialPinDialog(
            title = stringResource(R.string.remove_app_pin),
            message = stringResource(R.string.enter_the_current_app_pin_to_remove_it),
            onUnlocked = {
                profileManager.setAppPinVerifier(null)
                appPinVerifier = null
                removingAppPin = false
            },
            onCancel = { removingAppPin = false }
        )
    }
    if (changingAppPin) {
        CredentialPinDialog(
            title = stringResource(R.string.change_app_pin),
            message = stringResource(R.string.enter_the_current_app_pin),
            onUnlocked = {
                changingAppPin = false
                settingAppPin = true
            },
            onCancel = { changingAppPin = false }
        )
    }

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
            Button(
                onClick = onClose,
                enabled = !savingCredential && !savingSshKey,
                modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5), contentColor = Color.White)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.back), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.app_settings), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.tunnels_global)) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.credentials)) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(stringResource(R.string.ssh_keys)) })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(stringResource(R.string.about)) })
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                when (selectedTab) {
                    0 -> {
                        SettingsHeader(stringResource(R.string.app_security))
                        if (systemPinEnabled) {
                            Text(stringResource(R.string.headset_passcode_active), color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
                            Text(stringResource(R.string.manage_your_passcode_in_quest_settings_security_protected_connec),
                                color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp))
                        } else {
                            if (com.farside.workspace.DeviceCredentialAuth.SYSTEM_PIN_SWITCH_VISIBLE) {
                                TextButton(onClick = { enablingSystemPin = true }) { Text(stringResource(R.string.use_headset_passcode_instead)) }
                            }
                            if (appPinVerifier == null || settingAppPin) {
                                Text(if (settingAppPin) stringResource(R.string.enter_new_app_pin) else stringResource(R.string.set_app_pin_help),
                                    color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                                SettingsTextField(appPinNew, { appPinNew = it; appPinError = null }, stringResource(R.string.new_pin_hint),
                                    accessibleLabel = stringResource(R.string.new_pin_hint),
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                                SettingsTextField(appPinConfirm, { appPinConfirm = it; appPinError = null }, stringResource(R.string.confirm_pin),
                                    accessibleLabel = stringResource(R.string.confirm_pin),
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                                appPinError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    TextButton(onClick = {
                                        if (!com.farside.workspace.CredentialPin.valid(appPinNew)) {
                                            appPinError = context.getString(R.string.pin_length_error)
                                            return@TextButton
                                        }
                                        if (appPinNew != appPinConfirm) {
                                            appPinError = context.getString(R.string.pin_mismatch)
                                            return@TextButton
                                        }
                                        val verifier = com.farside.workspace.CredentialPin.create(appPinNew)
                                        profileManager.setAppPinVerifier(verifier)
                                        appPinVerifier = verifier
                                        settingAppPin = false
                                        appPinNew = ""
                                        appPinConfirm = ""
                                        appPinError = null
                                    }) { Text(stringResource(R.string.save_pin), color = Color(0xFF1E88E5)) }
                                    if (settingAppPin) {
                                        TextButton(onClick = { settingAppPin = false; appPinNew = ""; appPinConfirm = ""; appPinError = null }) {
                                            Text(stringResource(R.string.cancel), color = Color.Gray)
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(stringResource(R.string.app_pin_active), color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.secures_credentials_with_require_pin_enabled), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Row {
                                        TextButton(onClick = { changingAppPin = true }) {
                                            Text(stringResource(R.string.change_pin), color = Color(0xFF1E88E5))
                                        }
                                        TextButton(onClick = { removingAppPin = true }) {
                                            Text(stringResource(R.string.remove_pin), color = Color.Red)
                                        }
                                    }
                                }
                            }

                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        AdbTunnelSection()

                        val context = LocalContext.current
                        val isDebuggable = remember {
                            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        }
                        if (isDebuggable) {
                            var debugStatusMessage by remember { mutableStateOf<String?>(null) }
                            Spacer(modifier = Modifier.height(16.dp))
                            SettingsHeader(stringResource(R.string.debug_actions))
                            Text(
                                stringResource(R.string.options_for_testing_sign_in_trust_store_prompts_and_permission_r),
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        com.farside.workspace.RdpCertificateStore(context).clearAll()
                                        debugStatusMessage = context.getString(R.string.trusted_certificates_cleared)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                                ) {
                                    Text(stringResource(R.string.clear_trusted_certificates))
                                }
                                Button(
                                    onClick = {
                                        val runtimePerms = listOf(
                                            android.Manifest.permission.CAMERA,
                                            android.Manifest.permission.RECORD_AUDIO,
                                            "horizonos.permission.HEADSET_CAMERA",
                                            "horizonos.permission.AVATAR_CAMERA",
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                                        )
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            context.revokeSelfPermissionsOnKill(runtimePerms)
                                            debugStatusMessage = context.getString(R.string.permissions_revoke_scheduled)
                                        } else {
                                            debugStatusMessage = context.getString(R.string.permissions_revoke_requires_android_13)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                                ) {
                                    Text(stringResource(R.string.revoke_granted_permissions))
                                }
                            }
                            ClearCloudflareLoginsButton { debugStatusMessage = it }
                            debugStatusMessage?.let { msg ->
                                Text(
                                    msg,
                                    color = Color(0xFF81C784),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    1 -> {
                        SettingsHeader(stringResource(R.string.user_management))
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            credentials.forEach { cred ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text(cred.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(cred.user, color = Color.Gray, fontSize = 12.sp)
                                        if (cred.requirePin) Text(stringResource(R.string.pin_required), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Row {
                                        TextButton(enabled = !savingCredential, onClick = {
                                            credentialAction(cred, "edit")
                                        }) { Text(stringResource(R.string.edit), color = Color(0xFF1E88E5)) }
                                        TextButton(enabled = !savingCredential, onClick = {
                                            credentialAction(cred, "delete")
                                        }) { Text(stringResource(R.string.delete), color = Color.Red) }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(if (editingCredentialId == null) stringResource(R.string.new_credential) else stringResource(R.string.edit_credential), color = Color(0xFF1E88E5), fontSize = 14.sp)
                            SettingsTextField(newCredName, { newCredName = it }, stringResource(R.string.account_display_name))
                            SettingsTextField(newCredUser, { newCredUser = it }, stringResource(R.string.username_optional_for_vnc),
                                modifier = Modifier.onFocusChanged { focusState ->
                                    if (!focusState.isFocused && editingCredentialId == null && newCredName.isBlank()) {
                                        newCredName = newCredUser
                                    }
                                })
                            SettingsTextField(newCredPass, { newCredPass = it }, stringResource(R.string.password), visualTransformation = PasswordVisualTransformation())
                            if (editingCredentialId != null) Text(stringResource(R.string.leave_password_blank_to_keep_the_saved_password), color = Color.Gray, fontSize = 12.sp)
                            SettingsSwitchRow(stringResource(R.string.require_pin), requirePin, { enabled ->
                                val previous = credentials.find { it.id == editingCredentialId }
                                if (!enabled && previous?.requirePin == true) {
                                    pendingPinDisable = true
                                } else if (enabled) {
                                    if (!systemPinEnabled && appPinVerifier == null) {
                                        pinError = context.getString(R.string.set_app_pin_first)
                                    } else {
                                        requirePin = true
                                        pinError = null
                                    }
                                } else {
                                    requirePin = false
                                    pinError = null
                                }
                            })
                            pinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(enabled = !savingCredential && !pendingPinDisable, onClick = {
                                val credentialName = if (editingCredentialId == null && newCredName.isBlank()) newCredUser else newCredName
                                if (credentialName.isNotBlank()) {
                                    val credential = com.farside.workspace.data.SavedCredential(
                                        id = editingCredentialId ?: java.util.UUID.randomUUID().toString(),
                                        name = credentialName, user = newCredUser,
                                        pass = if (editingCredentialId != null && newCredPass.isEmpty())
                                            credentials.first { it.id == editingCredentialId }.pass else newCredPass,
                                        requirePin = requirePin)
                                    val editing = editingCredentialId != null
                                    savingCredential = true
                                    credentialScope.launch {
                                        try {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                                val saved = credential
                                                if (editing) profileManager.updateCredential(saved) else profileManager.addCredential(saved)
                                            }
                                            credentials = profileManager.getCredentials()
                                            editingCredentialId = null
                                            newCredName = ""
                                            newCredUser = ""
                                            newCredPass = ""
                                            requirePin = false
                                        } finally { savingCredential = false }
                                    }
                                }
                            }, modifier = Modifier.align(Alignment.End)) {
                                Text(if (editingCredentialId == null) stringResource(R.string.save_credential) else stringResource(R.string.save_changes), color = Color(0xFF1E88E5))
                            }
                            if (editingCredentialId != null) {
                                TextButton(enabled = !savingCredential && !pendingPinDisable, onClick = {
                                    editingCredentialId = null
                                    newCredName = ""
                                    newCredUser = ""
                                    newCredPass = ""
                                    requirePin = false
                                    pinError = null
                                }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel_edit)) }
                            }
                        }
                    }
                    2 -> {
                        SettingsHeader(stringResource(R.string.ssh_key_management))
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            sshKeys.forEach { key ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(key.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        if (key.requirePin) Text(stringResource(R.string.pin_required), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Row {
                                        TextButton(
                                            modifier = Modifier.semantics { contentDescription = context.getString(R.string.edit_named_item, key.name) },
                                            enabled = !savingSshKey,
                                            onClick = {
                                                sshKeyAction(key, "edit")
                                            }
                                        ) { Text(stringResource(R.string.edit), color = Color(0xFF1E88E5)) }
                                        TextButton(
                                            modifier = Modifier.semantics { contentDescription = context.getString(R.string.delete_named_item, key.name) },
                                            enabled = !savingSshKey,
                                            onClick = {
                                                sshKeyAction(key, "delete")
                                            }
                                        ) { Text(stringResource(R.string.delete), color = Color.Red) }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(if (editingSshKeyId == null) stringResource(R.string.new_ssh_key) else stringResource(R.string.edit_ssh_key), color = Color(0xFF1E88E5), fontSize = 14.sp)
                            SettingsTextField(newKeyName, { newKeyName = it }, stringResource(R.string.key_display_name))
                            SettingsTextField(
                                value = newKeyPrivate,
                                onValueChange = { newKeyPrivate = it },
                                placeholder = stringResource(R.string.begin_openssh_private_key_n),
                                accessibleLabel = stringResource(R.string.private_key),
                                singleLine = false,
                                minLines = 3,
                                maxLines = 6
                            )
                            SettingsTextField(newKeyPassphrase, { newKeyPassphrase = it }, stringResource(R.string.passphrase_optional), visualTransformation = PasswordVisualTransformation())
                            if (editingSshKeyId != null) Text(stringResource(R.string.leave_passphrase_blank_to_keep_the_saved_passphrase), color = Color.Gray, fontSize = 12.sp)
                            SettingsSwitchRow(stringResource(R.string.require_pin), keyRequirePin, { enabled ->
                                val previous = sshKeys.find { it.id == editingSshKeyId }
                                if (!enabled && previous?.requirePin == true) {
                                    pendingKeyPinDisable = true
                                } else if (enabled) {
                                    if (!systemPinEnabled && appPinVerifier == null) {
                                        keyPinError = context.getString(R.string.set_app_pin_first)
                                    } else {
                                        keyRequirePin = true
                                        keyPinError = null
                                    }
                                } else {
                                    keyRequirePin = false
                                    keyPinError = null
                                }
                            })
                            keyPinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(enabled = !savingSshKey && !pendingKeyPinDisable, onClick = {
                                if (newKeyName.isNotBlank() && newKeyPrivate.isNotBlank()) {
                                    val keyToSave = com.farside.workspace.data.SavedSshKey(
                                        id = editingSshKeyId ?: java.util.UUID.randomUUID().toString(),
                                        name = newKeyName,
                                        privateKey = newKeyPrivate,
                                        passphrase = if (editingSshKeyId != null && newKeyPassphrase.isEmpty())
                                            sshKeys.first { it.id == editingSshKeyId }.passphrase else newKeyPassphrase,
                                        requirePin = keyRequirePin
                                    )
                                    val editing = editingSshKeyId != null
                                    savingSshKey = true
                                    sshKeyScope.launch {
                                        try {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                                if (editing) profileManager.updateSshKey(keyToSave) else profileManager.addSshKey(keyToSave)
                                            }
                                            sshKeys = profileManager.getSshKeys()
                                            editingSshKeyId = null
                                            newKeyName = ""
                                            newKeyPrivate = ""
                                            newKeyPassphrase = ""
                                            keyRequirePin = false
                                        } finally { savingSshKey = false }
                                    }
                                }
                            }, modifier = Modifier.align(Alignment.End)) {
                                Text(if (editingSshKeyId == null) stringResource(R.string.save_ssh_key) else stringResource(R.string.save_changes), color = Color(0xFF1E88E5))
                            }
                            if (editingSshKeyId != null) {
                                TextButton(enabled = !savingSshKey && !pendingKeyPinDisable, onClick = {
                                    editingSshKeyId = null
                                    newKeyName = ""
                                    newKeyPrivate = ""
                                    newKeyPassphrase = ""
                                    keyRequirePin = false
                                    keyPinError = null
                                }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel_edit)) }
                            }
                        }
                    }
                    3 -> {
                        SettingsHeader(stringResource(R.string.about_open_source_licenses))
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.farside_workspace), color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.licenses_in_use), color = Color.LightGray, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.license_freerdp), color = Color.Gray)
                            Text(stringResource(R.string.license_sshj), color = Color.Gray)
                            Text(stringResource(R.string.license_termux), color = Color.Gray)
                            Text(stringResource(R.string.license_compose), color = Color.Gray)
                            Text(stringResource(R.string.license_meta), color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClearCloudflareLoginsButton(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Button(
            onClick = {
                onStatus(runCatching {
                    com.farside.workspace.tunnel.CloudflaredTunnel.clearCachedLogins(context)
                    context.getString(R.string.cloudflare_logins_cleared)
                }.getOrElse { it.message ?: context.getString(R.string.cloudflare_logins_clear_failed) })
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
        ) { Text(stringResource(R.string.clear_cloudflare_logins)) }
        Text(
            stringResource(R.string.close_cloudflare_sessions_first_clears_cached_access_tokens_brow),
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .background(Color(0xFF1C1C1E))
            .clickable { onCheckedChange(!checked) }
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        ConnectionType.entries.forEach { type ->
            FilterChip(selected = selectedType == type, onClick = { onTypeSelected(type) },
                label = { Text(stringResource(type.labelResId)) }, colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF1E88E5), selectedLabelColor = Color.White))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraResolutionSelector(
    selectedResolution: com.farside.workspace.data.CameraResolution,
    enabled: Boolean,
    onResolutionSelected: (com.farside.workspace.data.CameraResolution) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .background(Color(0xFF1C1C1E))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.camera_resolution),
            color = if (enabled) Color.White else Color.Gray,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.farside.workspace.data.CameraResolution.values().forEach { res ->
                FilterChip(
                    selected = selectedResolution == res,
                    onClick = { if (enabled) onResolutionSelected(res) },
                    enabled = enabled,
                    label = { Text(stringResource(res.labelResId)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1E88E5),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF2C2C2E),
                        labelColor = Color.LightGray,
                        disabledContainerColor = Color(0xFF2C2C2E).copy(alpha = 0.35f),
                        disabledLabelColor = Color.Gray.copy(alpha = 0.35f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyTypeSelector(selectedType: com.farside.workspace.data.ProxyType, onTypeSelected: (com.farside.workspace.data.ProxyType) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        TextField(
            value = if (selectedType in com.farside.workspace.data.ProxyType.choices) stringResource(selectedType.labelResId) else stringResource(R.string.unsupported_saved_proxy),
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
            com.farside.workspace.data.ProxyType.choices.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(stringResource(selectionOption.labelResId)) },
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
fun ResolutionDropdown(selectedRes: String, onResSelected: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val resolutions = listOf(stringResource(R.string.resolution_1368_x_768), stringResource(R.string.resolution_1600_x_900), stringResource(R.string.resolution_1920_x_1080), stringResource(R.string.resolution_2000_x_1124), stringResource(R.string.resolution_2400_x_1348), stringResource(R.string.resolution_2800_x_1572), stringResource(R.string.resolution_3200_x_1800))

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
    profileManager: com.farside.workspace.data.ProfileManager,
    onNavigateToSettings: (Int) -> Unit = {},
    credentialRevision: Int = 0
) {
    var name by rememberSaveable { mutableStateOf(initialProfile?.name ?: "") }
    var type by rememberSaveable { mutableStateOf(initialProfile?.type ?: ConnectionType.RDP) }
    var host by rememberSaveable { mutableStateOf(initialProfile?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(initialProfile?.port?.toString() ?: "") }
    var user by rememberSaveable { mutableStateOf(initialProfile?.user ?: "") }
    var pass by remember { mutableStateOf(initialProfile?.pass ?: "") }
    val creds = remember(profileManager, credentialRevision) { profileManager.getCredentials() }
    val sshKeys = remember(profileManager, credentialRevision) { profileManager.getSshKeys() }
    var savedCredId by rememberSaveable {
        mutableStateOf(initialProfile?.credentialId ?: initialProfile?.let { prof ->
            creds.singleOrNull { it.user == prof.user && it.pass == prof.pass }?.id
        } ?: "")
    }
    var savedSshKeyId by rememberSaveable { mutableStateOf(initialProfile?.sshKeyId ?: "") }
    var savedJumpHostId by rememberSaveable { mutableStateOf(initialProfile?.jumpHostProfileId ?: "") }
    
    var remoteApp by rememberSaveable { mutableStateOf(initialProfile?.remoteApp ?: "") }
    
    var discoveredHosts by remember { mutableStateOf<List<com.farside.workspace.utils.DiscoveredHost>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var proxyType by rememberSaveable { mutableStateOf(if (initialProfile?.useCloudflare == true) com.farside.workspace.data.ProxyType.CLOUDFLARED else initialProfile?.proxyType ?: com.farside.workspace.data.ProxyType.NONE) }
    var cloudflaredParameters by remember { mutableStateOf(initialProfile?.cloudflaredParameters ?: "") }
    var showCloudflaredParameters by remember { mutableStateOf(false) }
    var cloudflaredError by remember { mutableStateOf<String?>(null) }
    var proxyHost by rememberSaveable { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by rememberSaveable { mutableStateOf(initialProfile?.proxyPort?.toString() ?: "") }
    var proxyUser by rememberSaveable { mutableStateOf(initialProfile?.proxyUser ?: "") }
    var proxyPass by remember { mutableStateOf(initialProfile?.proxyPass ?: "") }
    var proxyExclude by rememberSaveable { mutableStateOf(initialProfile?.proxyExclude ?: "") }
    var proxyCommand by rememberSaveable { mutableStateOf(initialProfile?.proxyCommand ?: "") }
    
    // RDP Specific Options
    var soundRedirection by rememberSaveable { mutableStateOf(initialProfile?.soundRedirection ?: com.farside.workspace.data.SoundRedirection.PLAY_ON_DEVICE) }
    var redirectMicrophone by rememberSaveable {
        mutableStateOf((initialProfile?.redirectMicrophone ?: true) && com.farside.workspace.hasMicrophonePermission(context))
    }
    val microphonePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> redirectMicrophone = granted && com.farside.workspace.hasMicrophonePermission(context) }
    var enableCamera by rememberSaveable {
        mutableStateOf((initialProfile?.enableCamera ?: false) && com.farside.workspace.hasCameraPermission(context))
    }
    var cameraResolution by rememberSaveable {
        mutableStateOf(initialProfile?.cameraResolution ?: com.farside.workspace.data.CameraResolution.HD_720)
    }
    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> enableCamera = com.farside.workspace.hasCameraPermission(context) }
    var redirectStorage by rememberSaveable {
        mutableStateOf((initialProfile?.redirectStorage ?: false) && com.farside.workspace.hasStoragePermission(context))
    }
    var enableSftp by rememberSaveable {
        mutableStateOf((initialProfile?.enableSftp ?: false) && com.farside.workspace.hasStoragePermission(context))
    }
    val sftpPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { enableSftp = com.farside.workspace.hasStoragePermission(context) }
    val storagePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        redirectStorage = com.farside.workspace.hasStoragePermission(context)
    }
    var redirectClipboard by rememberSaveable { mutableStateOf(initialProfile?.redirectClipboard ?: true) }

    var swapMouseButtons by rememberSaveable { mutableStateOf(initialProfile?.swapMouseButtons ?: false) }
    var autoCopySelection by rememberSaveable { mutableStateOf(initialProfile?.autoCopySelection ?: true) }
    var sshForwards by remember { mutableStateOf(initialProfile?.sshForwards ?: emptyList<com.farside.workspace.data.SshForward>()) }
    var showSshForwards by remember { mutableStateOf(false) }
    if (showSshForwards) SshForwardingDialog(sshForwards, onChange = { sshForwards = it }, onDismiss = { showSshForwards = false })

    var adminMode by rememberSaveable { mutableStateOf(initialProfile?.adminMode ?: false) }

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
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color(0xFF1E88E5))
            }
            Text(stringResource(R.string.add_connection), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                    if (proxyType !in com.farside.workspace.data.ProxyType.choices) {
                        showValidationErrors = true
                        return@TextButton
                    }
                    val creds = profileManager.getCredentials()
                    val selectedCred = creds.find { it.id == savedCredId }
                    val credSelected = when (type) {
                        ConnectionType.SSH -> true
                        ConnectionType.VNC -> selectedCred != null && selectedCred.pass.isNotEmpty() && selectedCred.pass.length <= 8 && selectedCred.pass.all { it.code in 1..255 }
                        ConnectionType.RDP -> if (selectedCred != null) selectedCred.user.isNotBlank() else user.isNotBlank()
                    }
                    if (host.isBlank() || !credSelected || port.toIntOrNull() !in 1..65535) {
                        showValidationErrors = true
                        return@TextButton
                    }
                    if (proxyType == com.farside.workspace.data.ProxyType.CLOUDFLARED) {
                        cloudflaredError = runCatching {
                            com.farside.workspace.tunnel.CloudflaredOptions.hostname(host)
                            com.farside.workspace.tunnel.CloudflaredOptions.parse(cloudflaredParameters)
                        }.exceptionOrNull()?.localizedMessage(context)
                        if (cloudflaredError != null) return@TextButton
                    }
                    
                    onSave(
                        ConnectionProfile(
                            id = initialProfile?.id ?: UUID.randomUUID().toString(),
                        name = name.takeIf { it.isNotBlank() } ?: context.getString(R.string.unnamed_connection),
                        type = type,
                        host = host,
                        port = port.toIntOrNull() ?: when (type) { ConnectionType.RDP -> 3389; ConnectionType.SSH -> 22; ConnectionType.VNC -> 5900 },
                        user = if (type == ConnectionType.VNC) "" else selectedCred?.user ?: user,
                        pass = if (type == ConnectionType.VNC) "" else selectedCred?.pass ?: pass,
                        credentialId = selectedCred?.id,
                        sshKeyId = if (type == ConnectionType.SSH) savedSshKeyId.takeIf { it.isNotBlank() } else null,
                        jumpHostProfileId = if (type == ConnectionType.SSH) savedJumpHostId.takeIf { it.isNotBlank() } else null,
                        remoteApp = remoteApp.takeIf { it.isNotBlank() },
                        useCloudflare = false,
                        enableCamera = enableCamera,
                        cameraResolution = cameraResolution,
                        cameraId = null,
                        proxyType = proxyType,
                        proxyHost = proxyHost.takeIf { it.isNotBlank() },
                        proxyPort = proxyPort.toIntOrNull(),
                        proxyUser = proxyUser.takeIf { it.isNotBlank() },
                        proxyPass = proxyPass.takeIf { it.isNotBlank() },
                        proxyExclude = proxyExclude.takeIf { it.isNotBlank() },
                        proxyCommand = proxyCommand.takeIf { it.isNotBlank() },
                        cloudflaredParameters = cloudflaredParameters,
                        soundRedirection = soundRedirection,
                        redirectMicrophone = redirectMicrophone && com.farside.workspace.hasMicrophonePermission(context),
                        redirectStorage = redirectStorage,
                        enableSftp = type == ConnectionType.SSH && enableSftp,
                        redirectClipboard = redirectClipboard,
                        customResolution = initialProfile?.customResolution ?: false,
                        matchDevice = initialProfile?.matchDevice ?: true,
                        useDefaultResolution = initialProfile?.useDefaultResolution ?: false,
                        resolutionWidth = initialProfile?.resolutionWidth ?: 1920,
                        resolutionHeight = initialProfile?.resolutionHeight ?: 1080,
                        resolutionScaling = initialProfile?.resolutionScaling ?: 100,
                        swapMouseButtons = swapMouseButtons,
                        autoCopySelection = autoCopySelection,
                        sshForwards = sshForwards,
                        terminalScrollback = initialProfile?.terminalScrollback ?: 2000,
                        terminalFontSize = initialProfile?.terminalFontSize ?: 14,
                        terminalOpacity = initialProfile?.terminalOpacity ?: 1f,
                        adminMode = adminMode
                    )
                )
            }) {
                Text(stringResource(R.string.save_uppercase), color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
            }
        }
        
        Divider(color = Color.DarkGray, thickness = 1.dp)
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
                    port = when (it) { ConnectionType.RDP -> "3389"; ConnectionType.SSH -> "22"; ConnectionType.VNC -> "5900" }
                    discoveredHosts = emptyList()
                }

                if (type == ConnectionType.RDP) {

                }

                SettingsHeader(stringResource(R.string.connection_name))
                SettingsTextField(name, { name = it }, stringResource(R.string.enter_display_name))

                if (type == ConnectionType.VNC) {
                    SettingsHeader(stringResource(R.string.screen_sharing))
                    Text(stringResource(R.string.on_mac_enable_screen_sharing_then_enable_vnc_viewers_with_a_pass),
                        color = Color.LightGray, modifier = Modifier.padding(horizontal = 16.dp), fontSize = 13.sp)
                }
                SettingsHeader(stringResource(R.string.host_server))
                if (type == ConnectionType.RDP || type == ConnectionType.VNC) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !isScanning, onClick = {
                            val scanType = type
                            isScanning = true
                            coroutineScope.launch {
                                try {
                                    val found = if (scanType == ConnectionType.VNC) com.farside.workspace.utils.NetworkScanner.scanLocalNetworkForVnc(context)
                                        else com.farside.workspace.utils.NetworkScanner.scanLocalNetworkForRdp(context)
                                    if (type == scanType) discoveredHosts = found
                                    if (found.isEmpty()) android.widget.Toast.makeText(context, context.getString(R.string.discovery_no_services), android.widget.Toast.LENGTH_LONG).show()
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    android.widget.Toast.makeText(context, error.message ?: context.getString(R.string.discovery_failed), android.widget.Toast.LENGTH_LONG).show()
                                } finally { isScanning = false }
                            }
                        }) {
                            Text(if (isScanning) stringResource(R.string.finding_local_sessions, stringResource(type.labelResId)) else if (type == ConnectionType.VNC) stringResource(R.string.find_screen_sharing) else stringResource(R.string.find_local_pcs), color = Color(0xFF1E88E5))
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
                                value = if (type == ConnectionType.VNC) stringResource(R.string.select_screen_sharing_server) else stringResource(R.string.select_discovered_pc),
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
                                            port = foundHost.port.toString()
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                SettingsTextField(host, { host = it }, stringResource(R.string.hostname_or_ip_address), isError = showValidationErrors && host.isBlank())
                SettingsTextField(
                    value = port, 
                    onValueChange = { port = it }, 
                    placeholder = stringResource(R.string.port_rdp_3389_ssh_22_vnc_5900),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                SettingsHeader(when (type) { ConnectionType.SSH -> stringResource(R.string.user_account_optional_for_ssh); ConnectionType.VNC -> stringResource(R.string.vnc_password_credential); else -> stringResource(R.string.user_account) })
                var expandedCreds by rememberSaveable { mutableStateOf(false) }
                
                ExposedDropdownMenuBox(
                    expanded = expandedCreds,
                    onExpandedChange = { expandedCreds = !expandedCreds },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    TextField(
                        value = creds.find { it.id == savedCredId }?.name ?: if (type == ConnectionType.SSH && savedCredId.isBlank()) stringResource(R.string.none_interactive_key_auth) else stringResource(R.string.select_saved_credential),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCreds) },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(
                            focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                            focusedIndicatorColor = Color(0xFF1E88E5), unfocusedIndicatorColor = Color.DarkGray,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        isError = showValidationErrors && type != ConnectionType.SSH && savedCredId.isBlank()
                    )
                    ExposedDropdownMenu(expanded = expandedCreds, onDismissRequest = { expandedCreds = false }) {
                        if (type == ConnectionType.SSH) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.none_interactive_key_auth)) },
                                onClick = {
                                    savedCredId = ""
                                    user = ""
                                    pass = ""
                                    expandedCreds = false
                                }
                            )
                        }
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
                            text = { Text(stringResource(R.string.add_new_credential), color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                expandedCreds = false
                                onNavigateToSettings(1)
                            }
                        )
                    }
                }

                if (type == ConnectionType.VNC && showValidationErrors) {
                    Text(stringResource(R.string.select_a_saved_vnc_password_1_to_8_latin_1_characters_and_a_vali), color = Color.Red, modifier = Modifier.padding(16.dp))
                }
                if (type == ConnectionType.SSH && savedCredId.isBlank()) {
                    SettingsTextField(user, { user = it }, stringResource(R.string.username_or_blank_to_prompt_on_connect))
                }

                if (type == ConnectionType.SSH) {
                    SettingsHeader(stringResource(R.string.ssh_key))
                    var expandedSshKeys by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedSshKeys,
                        onExpandedChange = { expandedSshKeys = !expandedSshKeys },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        TextField(
                            value = sshKeys.find { it.id == savedSshKeyId }?.name ?: stringResource(R.string.none_password_interactive),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSshKeys) },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(
                                focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                                focusedIndicatorColor = Color(0xFF1E88E5), unfocusedIndicatorColor = Color.DarkGray,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedSshKeys, onDismissRequest = { expandedSshKeys = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.none_password_interactive)) },
                                onClick = {
                                    savedSshKeyId = ""
                                    expandedSshKeys = false
                                }
                            )
                            sshKeys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.name) },
                                    onClick = {
                                        savedSshKeyId = key.id
                                        expandedSshKeys = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_new_ssh_key), color = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    expandedSshKeys = false
                                    onNavigateToSettings(2)
                                }
                            )
                        }
                    }
                    SettingsHeader(stringResource(R.string.jump_host))
                    val allProfiles = remember(profileManager) { profileManager.getProfiles() }
                    val editedProfile = (initialProfile ?: ConnectionProfile(id = "", name = name, type = ConnectionType.SSH, host = host, user = "", pass = ""))
                        .copy(jumpHostProfileId = savedJumpHostId.takeIf { it.isNotBlank() })
                    val jumpCandidates = remember(allProfiles, initialProfile?.id) {
                        com.farside.workspace.ssh.JumpChains.candidates(editedProfile.copy(jumpHostProfileId = null), allProfiles)
                    }
                    var expandedJump by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expandedJump,
                        onExpandedChange = { expandedJump = !expandedJump },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        TextField(
                            value = allProfiles.find { it.id == savedJumpHostId }?.name ?: stringResource(R.string.none_connect_directly),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedJump) },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(
                                focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                                focusedIndicatorColor = Color(0xFF1E88E5), unfocusedIndicatorColor = Color.DarkGray,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedJump, onDismissRequest = { expandedJump = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.none_connect_directly)) },
                                onClick = { savedJumpHostId = ""; expandedJump = false }
                            )
                            jumpCandidates.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.name) },
                                    onClick = { savedJumpHostId = candidate.id; expandedJump = false }
                                )
                            }
                        }
                    }
                    val (chainPreview, chainProblem) = com.farside.workspace.ssh.JumpChains.preview(context, editedProfile, allProfiles)
                    val previewText = chainProblem ?: chainPreview?.takeIf { it.jumpHosts.isNotEmpty() }?.let {
                        context.getString(R.string.jump_route_preview, editedProfile.name.ifBlank { context.getString(R.string.this_profile) }, it.jumpHosts.joinToString(context.getString(R.string.jump_route_separator)) { h -> h.name })
                    }
                    if (previewText != null) {
                        Text(previewText, fontSize = 12.sp, color = if (chainProblem != null) Color.Red else Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }                }
                
                if (type == ConnectionType.RDP) {
                    SettingsHeader(stringResource(R.string.general))
                } else if (type == ConnectionType.SSH) {
                    SettingsHeader(stringResource(R.string.files))
                    SettingsSwitchRow(stringResource(R.string.enable_sftp_file_access), enableSftp, { enabled ->
                        if (!enabled) enableSftp = false
                        else if (com.farside.workspace.hasStoragePermission(context)) enableSftp = true
                        else com.farside.workspace.requestStoragePermission(context) { sftpPermissionLauncher.launch(it) }
                    })
                    SettingsHeader(stringResource(R.string.terminal_clipboard))
                    SettingsSwitchRow(stringResource(R.string.auto_copy_selection_to_clipboard), autoCopySelection, { autoCopySelection = it })
                    OutlinedButton(onClick = { showSshForwards = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(pluralStringResource(R.plurals.saved_port_forward_count, sshForwards.size, sshForwards.size))
                    }

                }
                
                SettingsHeader(stringResource(R.string.proxy_settings))
                ProxyTypeSelector(proxyType, { proxyType = it })
                if (proxyType !in com.farside.workspace.data.ProxyType.choices) {
                    Text(stringResource(R.string.select_none_cloudflared_socks5_or_http_before_saving), color = Color.Red, modifier = Modifier.padding(16.dp))
                }
                if (proxyType == com.farside.workspace.data.ProxyType.CLOUDFLARED) {
                    Text(stringResource(R.string.routes_the_connection_through_cloudflare_access_browser_sign_in), color = Color.Gray, modifier = Modifier.padding(16.dp))
                    SettingsTextField(cloudflaredParameters, { cloudflaredParameters = it; cloudflaredError = null }, stringResource(R.string.extra_parameters_optional),
                        visualTransformation = if (showCloudflaredParameters) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrect = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                    SettingsSwitchRow(stringResource(R.string.show_cloudflared_parameters), showCloudflaredParameters, { showCloudflaredParameters = it })
                    Text(stringResource(R.string.allowed_service_token_id_and_service_token_secret_or_header_h_fo), color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp))
                    cloudflaredError?.let { Text(it, color = Color.Red, modifier = Modifier.padding(16.dp)) }
                } else if (proxyType == com.farside.workspace.data.ProxyType.SOCKS5 || proxyType == com.farside.workspace.data.ProxyType.HTTP) {
                    Text(stringResource(R.string.proxy_connections_unavailable, stringResource(proxyType.labelResId)), color = Color.Gray, modifier = Modifier.padding(16.dp))
                    SettingsTextField(proxyHost, { proxyHost = it }, stringResource(R.string.proxy_hostname_or_ip))
                    SettingsTextField(proxyPort, { proxyPort = it }, stringResource(R.string.proxy_port))
                    SettingsTextField(proxyUser, { proxyUser = it }, stringResource(R.string.proxy_username_optional))
                    SettingsTextField(proxyPass, { proxyPass = it }, stringResource(R.string.proxy_password_optional), visualTransformation = PasswordVisualTransformation())
                    SettingsTextField(proxyExclude, { proxyExclude = it }, stringResource(R.string.exclude_hosts_ips_comma_separated))
                }

                if (type == ConnectionType.RDP) {
                    SettingsHeader(stringResource(R.string.device_audio_redirection))
                    // We can reuse ProxyTypeSelector logic for SoundRedirection dropdown if we wanted, or just a simple segmented control.
                    SettingsSwitchRow(stringResource(R.string.redirect_microphone), redirectMicrophone, { enabled ->
                        if (!enabled) redirectMicrophone = false
                        else if (com.farside.workspace.hasMicrophonePermission(context)) redirectMicrophone = true
                        else microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    })
                    SettingsSwitchRow(stringResource(R.string.redirect_camera), enableCamera, { enabled ->
                        if (!enabled) enableCamera = false
                        else if (com.farside.workspace.hasCameraPermission(context)) enableCamera = true
                        else cameraPermission.launch(com.farside.workspace.REQUIRED_CAMERA_PERMISSIONS)
                    })
                    CameraResolutionSelector(
                        selectedResolution = cameraResolution,
                        enabled = enableCamera,
                        onResolutionSelected = { cameraResolution = it }
                    )
                    SettingsSwitchRow(stringResource(R.string.redirect_storage), redirectStorage, { enabled ->
                        if (!enabled) redirectStorage = false
                        else if (com.farside.workspace.hasStoragePermission(context)) redirectStorage = true
                        else com.farside.workspace.requestStoragePermission(context) { intent -> storagePermissionLauncher.launch(intent) }
                    })
                    SettingsSwitchRow(stringResource(R.string.redirect_clipboard), redirectClipboard, { redirectClipboard = it })
                    SettingsSwitchRow(stringResource(R.string.admin_mode), adminMode, { adminMode = it })
                }
            }
        }
    }
}
