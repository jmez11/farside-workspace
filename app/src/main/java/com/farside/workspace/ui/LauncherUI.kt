@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.farside.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.farside.workspace.R
import com.farside.workspace.localizedMessage
import com.farside.workspace.resolve
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.onClick
import com.farside.workspace.ConnectionSpatialDrag
import com.farside.workspace.WindowPlacementSupport
import com.farside.workspace.QuestWindowManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.SavedCredential
import androidx.compose.material.icons.filled.DesktopMac
import com.farside.workspace.data.ConnectionType
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    onMoveProfile: (String, String) -> Unit = { _, _ -> },
    onCaptureProfile: (String) -> Unit = {}
) {
    var isAdding by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var filesOpen by remember { mutableStateOf(false) }
    var settingsInitialTab by rememberSaveable { mutableStateOf(0) }
    var credentialRevision by remember { mutableIntStateOf(0) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(profiles.size) { if (profiles.size < 2) reordering = false }
    val dragView = LocalView.current
    val context = LocalContext.current
    val windowManagerAvailable = remember(context) { QuestWindowManager.isAvailable(context) }
    val placementAvailable = remember { WindowPlacementSupport.isAvailable() }
    DisposableEffect(dragView) {
        val detach = ConnectionSpatialDrag.attach(dragView)
        onDispose { detach() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isAdding || editingProfile != null) {
            com.farside.workspace.ui.setup.ConnectionSetupFlow(
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
                savedProfiles = profiles,
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
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 600.dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!compact) Spacer(Modifier.width(if (windowManagerAvailable) 192.dp else 144.dp))
                    Text(stringResource(R.string.farside_connections),
                        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CardActionTooltip(stringResource(R.string.add_connection)) {
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        val pulseColor = if (profiles.isEmpty()) {
                            val pulse = rememberInfiniteTransition(label = "Add connection pulse")
                            val intensity by pulse.animateFloat(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 1800),
                                    repeatMode = RepeatMode.Reverse
                                ), label = "Add connection button color"
                            )
                            Color.White.copy(alpha = 0.12f * intensity)
                        } else Color.Transparent
                        IconButton(
                            onClick = { isAdding = true },
                            interactionSource = interaction,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (hovered) Color.Transparent else pulseColor
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_connection), tint = Color.White)
                        }
                    }
                    if (windowManagerAvailable) {
                        CardActionTooltip(stringResource(R.string.window_manager)) {
                            IconButton(onClick = { QuestWindowManager.toggle(context) }) {
                                Icon(Icons.Default.Dashboard, contentDescription = stringResource(R.string.window_manager), tint = Color.White)
                            }
                        }
                    }
                    if (profiles.any { it.type == ConnectionType.SSH && it.enableSftp }) {
                        CardActionTooltip(stringResource(R.string.sftp_browser)) {
                            IconButton(onClick = { filesOpen = true }) {
                                Icon(androidx.compose.material.icons.Icons.Default.Folder, contentDescription = stringResource(R.string.files), tint = Color.White)
                            }
                        }
                    }
                    CardActionTooltip(stringResource(R.string.settings)) {
                        IconButton(
                            onClick = {
                                settingsInitialTab = 0
                                isSettingsOpen = true
                            },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White)
                        }
                    }
                    }
                }
                if (profiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (reordering) stringResource(R.string.reorder_hint)
                            else stringResource(if (placementAvailable) R.string.open_or_place_hint else R.string.click_to_open_hint),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (profiles.size > 1) {
                            OutlinedButton(onClick = { reordering = !reordering }) {
                                Text(if (reordering) stringResource(R.string.done_reordering) else stringResource(R.string.reorder))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LauncherConnectionGrid(profiles = profiles, starredIds = starredIds, reordering = reordering && profiles.size > 1,
                    modifier = Modifier.weight(1f), onEdit = { editingProfile = it }, onDelete = onDeleteProfile,
                    onConnect = onConnectProfile, onToggleStar = onToggleStar, onMove = onMoveProfile,
                    onCapture = onCaptureProfile)

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
    jumpLabel: String? = null, connected: Boolean = false,
    onCapture: () -> Unit = {}) {
    var menuExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val latestProfile by rememberUpdatedState(profile)
    val placementAvailable = remember { WindowPlacementSupport.isAvailable() }
    val context = LocalContext.current
    val background = remember(profile.cardBackground) {
        profile.cardBackground?.let { name ->
            runCatching { BitmapFactory.decodeFile(File(context.filesDir, "card-backgrounds/$name").path)?.asImageBitmap() }.getOrNull()
        }
    }
    val launchModifier = if (!reordering) Modifier
        .semantics { onClick { onConnect(); true } }
        .pointerInput(profile.id, view, placementAvailable) {
            detectTapGestures(
                onTap = { onConnect() },
                // Consume an unsupported hold so releasing it cannot become a tap.
                onLongPress = { if (placementAvailable) ConnectionSpatialDrag.start(view, latestProfile) }
            )
        } else Modifier

    val cardHeight = if (reordering) 196.dp else 220.dp
    Card(
        modifier = Modifier.padding(8.dp).fillMaxWidth().height(cardHeight).then(launchModifier),
        shape = RoundedCornerShape(12.dp),
        border = if (dragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(cardHeight)) {
            if (background != null) {
                Image(background, contentDescription = null, alignment = Alignment.Center, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.5f })
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.15f)))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 52.dp, end = 16.dp,
                    bottom = if (reordering) 44.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when (profile.type) { ConnectionType.RDP -> Icons.Default.Computer; ConnectionType.SSH -> Icons.Default.Terminal; ConnectionType.VNC -> Icons.Default.DesktopMac },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                    Text(stringResource(profile.type.labelResId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
                }
                Text(profile.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = if (reordering) 1 else 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(stringResource(R.string.connection_address, profile.host, profile.port), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (reordering) 1 else 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (jumpLabel != null && !reordering) Text(jumpLabel, style = MaterialTheme.typography.labelSmall,
                    color = if (jumpLabel.startsWith("\u26A0")) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val starLabel = if (starred) stringResource(R.string.unstar_connection, profile.name)
                    else stringResource(R.string.star_connection, profile.name)
                val starTooltip = if (starred) stringResource(R.string.unfavorite)
                    else stringResource(R.string.favorite)
                CardActionTooltip(starTooltip) {
                    IconButton(onClick = onToggleStar) {
                        Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = starLabel,
                            tint = if (starred) Color(0xFFFFD166) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if ((connected || profile.cardBackground != null) && !reordering) {
                    val hasPhoto = profile.cardBackground != null
                    val captureLabel = stringResource(if (hasPhoto) R.string.remove_connection_photo else R.string.capture_connection, profile.name)
                    CardActionTooltip(captureLabel) {
                        IconButton(onClick = onCapture) {
                            Icon(if (hasPhoto) Icons.Default.ImageNotSupported else Icons.Default.PhotoCamera, captureLabel)
                        }
                    }
                }
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
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 4.dp)) {
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
private fun CardActionTooltip(label: String, content: @Composable () -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    LaunchedEffect(hovering) {
        visible = false
        if (hovering) {
            delay(450)
            visible = true
        }
    }
    Box(Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) when (awaitPointerEvent().type) {
                PointerEventType.Enter, PointerEventType.Move -> hovering = true
                PointerEventType.Exit -> hovering = false
                PointerEventType.Press -> visible = false
                else -> Unit
            }
        }
    }) {
        content()
        if (visible) Popup(popupPositionProvider = remember(gap) { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
        } }, properties = PopupProperties(focusable = false), onDismissRequest = { visible = false }) {
            Surface(modifier = Modifier.widthIn(max = 320.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp) {
                Text(label, color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun FormValidationField(message: String?, content: @Composable () -> Unit) {
    var active by remember { mutableStateOf(false) }
    if (message != null) active = true
    if (!active) {
        content()
        return
    }
    var hovering by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    LaunchedEffect(hovering, message) {
        visible = false
        if (hovering && message != null) {
            delay(450)
            visible = true
        }
    }
    val hoverModifier = Modifier.pointerInput(message) {
        awaitPointerEventScope {
            while (true) when (awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).type) {
                PointerEventType.Enter, PointerEventType.Move -> hovering = true
                PointerEventType.Exit -> hovering = false
                PointerEventType.Press -> visible = false
                else -> Unit
            }
        }
    }
    Box(hoverModifier.semantics { if (message != null) error(message) }) {
        content()
        if (visible && message != null) Popup(popupPositionProvider = remember(gap) { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
        } }, properties = PopupProperties(focusable = false), onDismissRequest = { visible = false }) {
            Surface(modifier = Modifier.widthIn(max = 320.dp), shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
                Text(message, color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}



@Composable
private fun FormTabs(labels: List<Int>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    TabRow(selectedTabIndex = selectedIndex, modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.primary,
        indicator = { positions ->
            TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(positions[selectedIndex]), color = MaterialTheme.colorScheme.primary)
        }) {
        labels.forEachIndexed { index, label ->
            Tab(selected = index == selectedIndex, onClick = { onSelect(index) },
                selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(stringResource(label), style = MaterialTheme.typography.titleSmall) })
        }
    }
}

@Composable
fun SettingsHeader(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
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
    errorMessage: String? = null,
    accessibleLabel: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    modifier: Modifier = Modifier
) {
    val invalid = isError || errorMessage != null
    FormValidationField(errorMessage) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.Gray) },
        label = if (accessibleLabel != null) { { Text(accessibleLabel, color = Color.Gray) } } else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).then(modifier)
            .then(if (invalid) Modifier.border(2.dp, Color.Red, RoundedCornerShape(4.dp)) else Modifier)
            .semantics { if (accessibleLabel != null) contentDescription = accessibleLabel },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF1C1C1E),
            unfocusedContainerColor = Color(0xFF1C1C1E),
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.DarkGray,
            errorIndicatorColor = Color.Red,
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
        isError = invalid
    )
    }
}

@Composable
fun SettingsScreen(onClose: () -> Unit, profileManager: com.farside.workspace.data.ProfileManager, initialTab: Int = 0) {
    val context = LocalContext.current
    // Hoisted for LocalContextGetResourceValueCall: resolve in composable scope, use in callbacks.
    val sshPublicKeyTitleLabel = stringResource(R.string.ssh_public_key_title)
    val sshPublicKeyCopiedMsg = stringResource(R.string.ssh_public_key_copied)
    val pinLengthErrorMsg = stringResource(R.string.pin_length_error)
    val pinMismatchMsg = stringResource(R.string.pin_mismatch)
    val trustedCertificatesClearedMsg = stringResource(R.string.trusted_certificates_cleared)
    val permissionsRevokeScheduledMsg = stringResource(R.string.permissions_revoke_scheduled)
    val permissionsRevokeRequires13Msg = stringResource(R.string.permissions_revoke_requires_android_13)
    val setAppPinFirstMsg = stringResource(R.string.set_app_pin_first)
    val sshPublicKeyShowNamedFmt = stringResource(R.string.ssh_public_key_show_named)
    val editNamedItemFmt = stringResource(R.string.edit_named_item)
    val deleteNamedItemFmt = stringResource(R.string.delete_named_item)
    val backupExportSuccessMsg = stringResource(R.string.backup_export_success)
    val backupExportFailedFmt = stringResource(R.string.backup_export_failed)
    val backupRestoreSuccessFmt = stringResource(R.string.backup_restore_success)
    val backupRestoreFailedFmt = stringResource(R.string.backup_restore_failed)
    val backupInvalidPassphraseMsg = stringResource(R.string.backup_invalid_passphrase)
    val backupPassphraseMismatchMsg = stringResource(R.string.backup_passphrase_mismatch)
    val backupPassphraseEmptyMsg = stringResource(R.string.backup_passphrase_empty)
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }

    var backupStatusMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showRestorePassphraseDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var exportPassphrase by remember { mutableStateOf("") }
    var exportPassphraseConfirm by remember { mutableStateOf("") }
    var exportPassphraseError by remember { mutableStateOf<String?>(null) }
    var restorePassphrase by remember { mutableStateOf("") }
    var restorePassphraseError by remember { mutableStateOf<String?>(null) }
    var isExportingBackup by remember { mutableStateOf(false) }
    var isRestoringBackup by remember { mutableStateOf(false) }
    var showStoragePermissionNotice by remember { mutableStateOf(false) }
    val backupCoroutineScope = rememberCoroutineScope()

    val storagePermissionLauncherForBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        showStoragePermissionNotice = false
    }

    val createBackupFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val passChars = exportPassphrase.toCharArray()
            isExportingBackup = true
            backupCoroutineScope.launch {
                try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.farside.workspace.data.BackupManager.exportToUri(context, profileManager, uri, passChars)
                    }
                    backupStatusMessage = backupExportSuccessMsg
                } catch (e: Exception) {
                    backupStatusMessage = String.format(backupExportFailedFmt, e.message ?: e.javaClass.simpleName)
                } finally {
                    java.util.Arrays.fill(passChars, '0')
                    exportPassphrase = ""
                    exportPassphraseConfirm = ""
                    isExportingBackup = false
                }
            }
        } else {
            exportPassphrase = ""
            exportPassphraseConfirm = ""
        }
    }

    val openBackupFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            restorePassphrase = ""
            restorePassphraseError = null
            showRestorePassphraseDialog = true
        }
    }

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
    var publicKeyPending by remember { mutableStateOf<com.farside.workspace.data.SavedSshKey?>(null) }
    var publicKeyShown by remember { mutableStateOf<Pair<String, com.farside.workspace.data.SshPublicKey>?>(null) }
    var publicKeyError by remember { mutableStateOf<String?>(null) }

    fun showPublicKey(key: com.farside.workspace.data.SavedSshKey) {
        try {
            publicKeyShown = key.name to com.farside.workspace.data.deriveSshPublicKey(key.privateKey, key.passphrase, key.name)
        } catch (error: com.farside.workspace.LocalizableException) {
            publicKeyError = error.resolve(context.applicationContext)
        }
    }

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
    publicKeyPending?.let { key ->
        if (systemPinEnabled) SystemCredentialDialog(
            title = stringResource(R.string.ssh_public_key_title), allowRemembered = false,
            onUnlocked = { publicKeyPending = null; showPublicKey(key) },
            onCancel = { publicKeyPending = null }
        ) else CredentialPinDialog(
            title = stringResource(R.string.ssh_public_key_title),
            message = stringResource(R.string.ssh_public_key_pin_prompt),
            onUnlocked = { publicKeyPending = null; showPublicKey(key) },
            onCancel = { publicKeyPending = null }
        )
    }
    publicKeyError?.let { message ->
        AlertDialog(onDismissRequest = { publicKeyError = null },
            title = { Text(stringResource(R.string.ssh_public_key_title)) },
            text = { Text(message) },
            confirmButton = { OutlinedButton(onClick = { publicKeyError = null }) { Text(stringResource(R.string.ssh_public_key_close)) } })
    }
    publicKeyShown?.let { (name, publicKey) ->
        AlertDialog(onDismissRequest = { publicKeyShown = null },
            title = { Text(name) },
            text = { Column {
                Text(stringResource(R.string.ssh_public_key_fingerprint, publicKey.fingerprint))
                Spacer(Modifier.height(12.dp))
                SelectionContainer { Text(publicKey.line, fontFamily = FontFamily.Monospace) }
            } },
            confirmButton = { OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(sshPublicKeyTitleLabel, publicKey.line))
                android.widget.Toast.makeText(context.applicationContext, sshPublicKeyCopiedMsg, android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.ssh_public_key_copy)) } },
            dismissButton = { OutlinedButton(onClick = { publicKeyShown = null }) { Text(stringResource(R.string.ssh_public_key_close)) } })
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

    if (showStoragePermissionNotice) {
        AlertDialog(
            onDismissRequest = { showStoragePermissionNotice = false },
            title = { Text(stringResource(R.string.backup_settings_title)) },
            text = { Text(stringResource(R.string.storage_permission_needed_backup)) },
            confirmButton = {
                OutlinedButton(onClick = {
                    showStoragePermissionNotice = false
                    com.farside.workspace.requestStoragePermission(context) { intent ->
                        storagePermissionLauncherForBackup.launch(intent)
                    }
                }) { Text(stringResource(R.string.grant_permission)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showStoragePermissionNotice = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isExportingBackup) showExportDialog = false },
            title = { Text(stringResource(R.string.backup_passphrase_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_passphrase_help), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportPassphrase,
                        onValueChange = { exportPassphrase = it; exportPassphraseError = null },
                        label = { Text(stringResource(R.string.backup_passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPassphraseConfirm,
                        onValueChange = { exportPassphraseConfirm = it; exportPassphraseError = null },
                        label = { Text(stringResource(R.string.backup_passphrase_confirm_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    exportPassphraseError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    enabled = !isExportingBackup,
                    onClick = {
                        if (exportPassphrase.isBlank()) {
                            exportPassphraseError = backupPassphraseEmptyMsg
                            return@OutlinedButton
                        }
                        if (exportPassphrase != exportPassphraseConfirm) {
                            exportPassphraseError = backupPassphraseMismatchMsg
                            return@OutlinedButton
                        }
                        showExportDialog = false
                        val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                        createBackupFileLauncher.launch("farside_backup_$dateStr.fsbk")
                    }
                ) {
                    Text(stringResource(R.string.export_backup))
                }
            },
            dismissButton = {
                OutlinedButton(enabled = !isExportingBackup, onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRestorePassphraseDialog && pendingRestoreUri != null) {
        val targetUri = pendingRestoreUri!!
        AlertDialog(
            onDismissRequest = { if (!isRestoringBackup) { showRestorePassphraseDialog = false; pendingRestoreUri = null } },
            title = { Text(stringResource(R.string.restore_passphrase_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.restore_passphrase_help), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = restorePassphrase,
                        onValueChange = { restorePassphrase = it; restorePassphraseError = null },
                        label = { Text(stringResource(R.string.backup_passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    restorePassphraseError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    enabled = !isRestoringBackup,
                    onClick = {
                        if (restorePassphrase.isBlank()) {
                            restorePassphraseError = backupPassphraseEmptyMsg
                            return@OutlinedButton
                        }
                        val passChars = restorePassphrase.toCharArray()
                        isRestoringBackup = true
                        backupCoroutineScope.launch {
                            try {
                                val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.farside.workspace.data.BackupManager.importFromUri(context, profileManager, targetUri, passChars)
                                }
                                credentials = profileManager.getCredentials()
                                sshKeys = profileManager.getSshKeys()
                                appPinVerifier = profileManager.getAppPinVerifier()
                                systemPinEnabled = profileManager.usesSystemPin()
                                backupStatusMessage = String.format(backupRestoreSuccessFmt, result.profilesRestored, result.credentialsRestored, result.sshKeysRestored)
                                showRestorePassphraseDialog = false
                                pendingRestoreUri = null
                            } catch (e: javax.crypto.AEADBadTagException) {
                                restorePassphraseError = backupInvalidPassphraseMsg
                            } catch (e: Exception) {
                                restorePassphraseError = e.message ?: backupInvalidPassphraseMsg
                            } finally {
                                java.util.Arrays.fill(passChars, '0')
                                restorePassphrase = ""
                                isRestoringBackup = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.restore_backup))
                }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !isRestoringBackup,
                    onClick = {
                        showRestorePassphraseDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val formWidth = minOf(700.dp, (maxWidth - 192.dp).coerceAtLeast(128.dp))
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            val editing = editingCredentialId != null || editingSshKeyId != null
            IconButton(onClick = {
                if (editingCredentialId != null) {
                    editingCredentialId = null
                    newCredName = ""; newCredUser = ""; newCredPass = ""; requirePin = false; pinError = null
                } else if (editingSshKeyId != null) {
                    editingSshKeyId = null
                    newKeyName = ""; newKeyPrivate = ""; newKeyPassphrase = ""; keyRequirePin = false; keyPinError = null
                } else onClose()
            }, enabled = !savingCredential && !savingSshKey, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(if (editing) Icons.Default.ArrowBack else Icons.Default.Close,
                    contentDescription = stringResource(if (editing) R.string.back else R.string.close), tint = Color.White)
            }
            FormTabs(listOf(R.string.credentials, R.string.ssh_keys, R.string.general),
                when (selectedTab) { 1 -> 0; 2 -> 1; else -> 2 },
                { selectedTab = listOf(1, 2, 0)[it] }, Modifier.width(formWidth).padding(horizontal = 16.dp))
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .width(formWidth)
                    .fillMaxHeight()
                    .verticalScroll(remember(selectedTab) { androidx.compose.foundation.ScrollState(0) })
                    .padding(bottom = 32.dp)
            ) {
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
                                OutlinedButton(onClick = { enablingSystemPin = true }) { Text(stringResource(R.string.use_headset_passcode_instead)) }
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
                                    OutlinedButton(onClick = {
                                        if (!com.farside.workspace.CredentialPin.valid(appPinNew)) {
                                            appPinError = pinLengthErrorMsg
                                            return@OutlinedButton
                                        }
                                        if (appPinNew != appPinConfirm) {
                                            appPinError = pinMismatchMsg
                                            return@OutlinedButton
                                        }
                                        val verifier = com.farside.workspace.CredentialPin.create(appPinNew)
                                        profileManager.setAppPinVerifier(verifier)
                                        appPinVerifier = verifier
                                        settingAppPin = false
                                        appPinNew = ""
                                        appPinConfirm = ""
                                        appPinError = null
                                    }) { Text(stringResource(R.string.save_pin)) }
                                    if (settingAppPin) {
                                        OutlinedButton(onClick = { settingAppPin = false; appPinNew = ""; appPinConfirm = ""; appPinError = null }) {
                                            Text(stringResource(R.string.cancel))
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
                                        OutlinedButton(onClick = { changingAppPin = true }) {
                                            Text(stringResource(R.string.change_pin))
                                        }
                                        OutlinedButton(onClick = { removingAppPin = true }) {
                                            Text(stringResource(R.string.remove_pin))
                                        }
                                    }
                                }
                            }

                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        SettingsHeader(stringResource(R.string.backup_settings_title))
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                stringResource(R.string.backup_settings_help),
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (!com.farside.workspace.hasStoragePermission(context)) {
                                            showStoragePermissionNotice = true
                                        } else {
                                            exportPassphrase = ""
                                            exportPassphraseConfirm = ""
                                            exportPassphraseError = null
                                            showExportDialog = true
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.export_backup))
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (!com.farside.workspace.hasStoragePermission(context)) {
                                            showStoragePermissionNotice = true
                                        } else {
                                            openBackupFileLauncher.launch(arrayOf("*/*"))
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.restore_backup))
                                }
                            }
                            backupStatusMessage?.let { status ->
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    status,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.background(Color(0xFF2C2C2E), RoundedCornerShape(8.dp)).padding(12.dp)
                                )
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
                                        debugStatusMessage = trustedCertificatesClearedMsg
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
                                            debugStatusMessage = permissionsRevokeScheduledMsg
                                        } else {
                                            debugStatusMessage = permissionsRevokeRequires13Msg
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
                        Spacer(modifier = Modifier.height(16.dp))
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
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        OutlinedButton(enabled = !savingCredential, onClick = {
                                            credentialAction(cred, "edit")
                                        }) { Text(stringResource(R.string.edit)) }
                                        OutlinedButton(enabled = !savingCredential, onClick = {
                                            credentialAction(cred, "delete")
                                        }) { Text(stringResource(R.string.delete)) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(if (editingCredentialId == null) stringResource(R.string.new_credential) else stringResource(R.string.edit_credential), color = Color.White, fontSize = 14.sp)
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
                                        pinError = setAppPinFirstMsg
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
                            OutlinedButton(enabled = !savingCredential && !pendingPinDisable, onClick = {
                                val credentialName = if (editingCredentialId == null && newCredName.isBlank()) newCredUser else newCredName
                                if (credentialName.isNotBlank()) {
                                    val credential = com.farside.workspace.data.SavedCredential(
                                        id = editingCredentialId ?: java.util.UUID.randomUUID().toString(),
                                        name = credentialName, user = newCredUser,
                                        pass = if (editingCredentialId != null && newCredPass.isEmpty())
                                            credentials.first { it.id == editingCredentialId }.pass else newCredPass,
                                        requirePin = requirePin,
                                        userShort = credentials.firstOrNull { it.id == editingCredentialId }?.userShort)
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
                                Text(if (editingCredentialId == null) stringResource(R.string.save_credential) else stringResource(R.string.save_changes))
                            }
                            if (editingCredentialId != null) {
                                OutlinedButton(enabled = !savingCredential && !pendingPinDisable, onClick = {
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
                                        IconButton(
                                            modifier = Modifier.semantics { contentDescription = sshPublicKeyShowNamedFmt.format(key.name) },
                                            enabled = !savingSshKey,
                                            onClick = { if (key.requirePin) publicKeyPending = key else showPublicKey(key) }
                                        ) { Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color.White) }
                                        OutlinedButton(
                                            modifier = Modifier.semantics { contentDescription = editNamedItemFmt.format(key.name) },
                                            enabled = !savingSshKey,
                                            onClick = {
                                                sshKeyAction(key, "edit")
                                            }
                                        ) { Text(stringResource(R.string.edit)) }
                                        OutlinedButton(
                                            modifier = Modifier.semantics { contentDescription = deleteNamedItemFmt.format(key.name) },
                                            enabled = !savingSshKey,
                                            onClick = {
                                                sshKeyAction(key, "delete")
                                            }
                                        ) { Text(stringResource(R.string.delete)) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(if (editingSshKeyId == null) stringResource(R.string.new_ssh_key) else stringResource(R.string.edit_ssh_key), color = Color.White, fontSize = 14.sp)
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
                                        keyPinError = setAppPinFirstMsg
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
                            OutlinedButton(enabled = !savingSshKey && !pendingKeyPinDisable, onClick = {
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
                                Text(if (editingSshKeyId == null) stringResource(R.string.save_ssh_key) else stringResource(R.string.save_changes))
                            }
                            if (editingSshKeyId != null) {
                                OutlinedButton(enabled = !savingSshKey && !pendingKeyPinDisable, onClick = {
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
                }
            }
        }
    }
}
}

@Composable
fun ClearCloudflareLoginsButton(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val loginsClearedMsg = stringResource(R.string.cloudflare_logins_cleared)
    val loginsClearFailedMsg = stringResource(R.string.cloudflare_logins_clear_failed)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Button(
            onClick = {
                onStatus(runCatching {
                    com.farside.workspace.tunnel.CloudflaredTunnel.clearCachedLogins(context)
                    loginsClearedMsg
                }.getOrElse { it.message ?: loginsClearFailedMsg })
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
            colors = SwitchDefaults.colors()
        )
    }
}

@Composable
internal fun connectionChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    containerColor = Color(0xFF2C2C2E),
    labelColor = Color.LightGray,
    disabledContainerColor = Color(0xFF2C2C2E).copy(alpha = 0.35f),
    disabledLabelColor = Color.Gray.copy(alpha = 0.35f)
)

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
                    colors = connectionChipColors()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyTypeSelector(selectedType: com.farside.workspace.data.ProxyType, errorMessage: String? = null,
                      onTypeSelected: (com.farside.workspace.data.ProxyType) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FormValidationField(errorMessage) {
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
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.DarkGray,
                errorIndicatorColor = Color.Red,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
                .then(if (errorMessage != null) Modifier.border(2.dp, Color.Red, RoundedCornerShape(4.dp)) else Modifier),
            isError = errorMessage != null
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
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
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
                        RadioButton(selected = selectedRes == res, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                    }
                )
            }
        }
    }
}

internal fun profileCredentialErrorRes(type: ConnectionType, vncMode: com.farside.workspace.data.VncAuthMode,
                                       credential: SavedCredential?, legacyUser: String): Int? = when (type) {
    ConnectionType.SSH -> null
    ConnectionType.RDP -> when {
        credential?.user?.isNotBlank() == true -> null
        credential != null -> R.string.profile_credential_username_missing
        legacyUser.isNotBlank() -> null
        else -> R.string.profile_rdp_credential_required
    }
    ConnectionType.VNC -> when (vncMode) {
        com.farside.workspace.data.VncAuthMode.MAC_USER -> when {
            credential == null -> R.string.profile_mac_credential_required
            credential.user.isBlank() -> R.string.profile_mac_username_missing
            credential.pass.isEmpty() -> R.string.profile_mac_password_required
            else -> null
        }
        com.farside.workspace.data.VncAuthMode.VNC_PASSWORD -> when {
            credential == null -> R.string.choose_vnc_password_credential
            credential.pass.isEmpty() -> R.string.profile_selected_credential_no_password
            credential.pass.length > 8 || credential.pass.any { it.code !in 1..255 } -> R.string.vnc_password_latin1
            else -> null
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
    savedProfiles: List<ConnectionProfile> = profileManager.getProfiles(),
    onNavigateToSettings: (Int) -> Unit = {},
    credentialRevision: Int = 0
) {
    var name by rememberSaveable { mutableStateOf(initialProfile?.name ?: "") }
    var type by rememberSaveable { mutableStateOf(initialProfile?.type ?: ConnectionType.RDP) }
    var host by rememberSaveable { mutableStateOf(initialProfile?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(initialProfile?.port?.toString() ?: "3389") }
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
    var vncAuthMode by rememberSaveable { mutableStateOf(initialProfile?.vncAuthMode ?: com.farside.workspace.data.VncAuthMode.MAC_USER) }
    var sftpPairProfileId by rememberSaveable { mutableStateOf(initialProfile?.sftpPairProfileId ?: initialProfile?.macSshRelayProfileId ?: "") }
    var enableMacSshRelay by rememberSaveable { mutableStateOf(initialProfile?.enableMacSshRelay ?: false) }
    var macSshRelaySameMac by rememberSaveable { mutableStateOf(initialProfile?.macSshRelaySameMac ?: false) }
    var showMacRelayConfirm by rememberSaveable { mutableStateOf(false) }

    
    var remoteApp by rememberSaveable { mutableStateOf(initialProfile?.remoteApp ?: "") }

    var discoveredHosts by remember { mutableStateOf<List<com.farside.workspace.utils.DiscoveredHost>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Hoisted for LocalContextGetResourceValueCall: resolve in composable scope, use in callbacks.
    val discoveryNoServicesMsg = stringResource(R.string.discovery_no_services)
    val discoveryFailedMsg = stringResource(R.string.discovery_failed)
    val thisProfileName = stringResource(R.string.this_profile)
    val jumpRouteSeparator = stringResource(R.string.jump_route_separator)
    val jumpRoutePreviewFmt = stringResource(R.string.jump_route_preview)

    var proxyType by rememberSaveable { mutableStateOf(if (initialProfile?.useCloudflare == true) com.farside.workspace.data.ProxyType.CLOUDFLARED else initialProfile?.proxyType ?: com.farside.workspace.data.ProxyType.NONE) }
    var cloudflaredParameters by remember { mutableStateOf(initialProfile?.cloudflaredParameters ?: "") }
    var showCloudflaredParameters by remember { mutableStateOf(false) }
    var cloudflaredHostError by remember { mutableStateOf<String?>(null) }
    var cloudflaredParametersError by remember { mutableStateOf<String?>(null) }
    var proxyHost by rememberSaveable { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by rememberSaveable { mutableStateOf(initialProfile?.proxyPort?.toString() ?: "") }
    var proxyUser by rememberSaveable { mutableStateOf(initialProfile?.proxyUser ?: "") }
    var proxyPass by remember { mutableStateOf(initialProfile?.proxyPass ?: "") }
    var proxyExclude by rememberSaveable { mutableStateOf(initialProfile?.proxyExclude ?: "") }
    var proxyCommand by rememberSaveable { mutableStateOf(initialProfile?.proxyCommand ?: "") }

    // RDP Specific Options
    var soundRedirection by rememberSaveable { mutableStateOf(initialProfile?.soundRedirection ?: com.farside.workspace.data.SoundRedirection.PLAY_ON_DEVICE) }
    var redirectMicrophone by rememberSaveable {
        mutableStateOf((initialProfile?.redirectMicrophone ?: false) && com.farside.workspace.hasMicrophonePermission(context))
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
        mutableStateOf(initialProfile?.enableSftp ?: true)
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
    var reconnectAutomatically by rememberSaveable { mutableStateOf(initialProfile?.reconnectAutomatically ?: false) }

    var swapMouseButtons by rememberSaveable { mutableStateOf(initialProfile?.swapMouseButtons ?: false) }
    var autoCopySelection by rememberSaveable { mutableStateOf(initialProfile?.autoCopySelection ?: true) }
    var sshForwards by remember { mutableStateOf(initialProfile?.sshForwards ?: emptyList<com.farside.workspace.data.SshForward>()) }
    var showSshForwards by remember { mutableStateOf(false) }
    if (showSshForwards) SshForwardingDialog(sshForwards, onChange = { sshForwards = it }, onDismiss = { showSshForwards = false })

    var connectToConsole by rememberSaveable { mutableStateOf(initialProfile?.connectToConsole ?: false) }
    var adminMode by rememberSaveable { mutableStateOf(initialProfile?.adminMode ?: false) }

    var showValidationErrors by rememberSaveable { mutableStateOf(false) }
    val selectedCredential = creds.find { it.id == savedCredId }
    val credentialErrorRes = profileCredentialErrorRes(type, vncAuthMode, selectedCredential, user)
    val credentialErrorMessage = if (showValidationErrors) credentialErrorRes?.let { stringResource(it) } else null

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val formWidth = minOf(700.dp, (maxWidth - 192.dp).coerceAtLeast(128.dp))
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(if (initialProfile == null) Icons.Default.Close else Icons.Default.ArrowBack,
                    contentDescription = stringResource(if (initialProfile == null) R.string.close else R.string.back), tint = Color.White)
            }
            FormTabs(ConnectionType.entries.map { if (it == ConnectionType.VNC) R.string.mac_vnc else it.labelResId },
                ConnectionType.entries.indexOf(type), { index ->
                    type = ConnectionType.entries[index]
                    port = when (type) { ConnectionType.RDP -> "3389"; ConnectionType.SSH -> "22"; ConnectionType.VNC -> "5900" }
                    discoveredHosts = emptyList()
                }, Modifier.width(formWidth).padding(horizontal = 16.dp))
            OutlinedButton(modifier = Modifier.align(Alignment.CenterEnd), onClick = {
                    showValidationErrors = true
                    if (proxyType !in com.farside.workspace.data.ProxyType.choices) {
                        return@OutlinedButton
                    }
                    val creds = profileManager.getCredentials()
                    val selectedCred = creds.find { it.id == savedCredId }
                    if (name.isBlank() || host.isBlank() || profileCredentialErrorRes(type, vncAuthMode, selectedCred, user) != null ||
                        port.toIntOrNull() !in 1..65535 ||
                        (proxyType in listOf(com.farside.workspace.data.ProxyType.HTTP, com.farside.workspace.data.ProxyType.SOCKS5) &&
                            (proxyHost.isBlank() || proxyPort.toIntOrNull() !in 1..65535))) {
                        return@OutlinedButton
                    }
                    if (proxyType == com.farside.workspace.data.ProxyType.CLOUDFLARED) {
                        cloudflaredHostError = runCatching { com.farside.workspace.tunnel.CloudflaredOptions.hostname(host) }
                            .exceptionOrNull()?.localizedMessage(context)
                        cloudflaredParametersError = runCatching { com.farside.workspace.tunnel.CloudflaredOptions.parse(cloudflaredParameters) }
                            .exceptionOrNull()?.localizedMessage(context)
                        if (cloudflaredHostError != null || cloudflaredParametersError != null) return@OutlinedButton
                    }

                    onSave(
                        ConnectionProfile(
                            id = initialProfile?.id ?: UUID.randomUUID().toString(),
                        name = name,
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
                        vncAuthMode = vncAuthMode,
                        sftpPairProfileId = if (type == ConnectionType.VNC) sftpPairProfileId.takeIf { id ->
                            savedProfiles.any { it.id == id && it.type == ConnectionType.SSH }
                        } else null,
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
                        connectToConsole = connectToConsole,
                        adminMode = adminMode,
                        reconnectAutomatically = reconnectAutomatically,
                        expMacHevc = type == ConnectionType.VNC && vncAuthMode == com.farside.workspace.data.VncAuthMode.MAC_USER,
                        expMacShareConsole = initialProfile?.expMacShareConsole ?: false,
                        macPreferAvc = true,
                        macAudioEnabled = true,
                        macSshRelayProfileId = null,
                        enableMacSshRelay = type == ConnectionType.VNC && sftpPairProfileId.isNotBlank() && enableMacSshRelay,
                        macSshRelaySameMac = macSshRelaySameMac
                    )
                )
            }) {
                Text(stringResource(R.string.save_uppercase))
            }
        }

        Divider(color = Color.DarkGray, thickness = 1.dp)
            // Settings List centered in a wide window
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .width(formWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                SettingsHeader(stringResource(R.string.connection_name))
                SettingsTextField(name, { name = it }, stringResource(R.string.enter_display_name),
                    errorMessage = if (showValidationErrors && name.isBlank()) stringResource(R.string.profile_name_required) else null)

                if (type == ConnectionType.VNC) {
                    SettingsHeader(stringResource(R.string.vnc_auth_mode))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        listOf(com.farside.workspace.data.VncAuthMode.MAC_USER to R.string.vnc_auth_mac_user,
                            com.farside.workspace.data.VncAuthMode.VNC_PASSWORD to R.string.vnc_auth_password).forEach { (mode, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable { vncAuthMode = mode }) {
                                RadioButton(selected = vncAuthMode == mode, onClick = { vncAuthMode = mode })
                                Text(stringResource(label), color = Color.White)
                            }
                        }
                    }
                }
                SettingsHeader(stringResource(R.string.host_server))
                if (type == ConnectionType.RDP || type == ConnectionType.VNC) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).zIndex(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !isScanning && (type != ConnectionType.VNC || vncAuthMode == com.farside.workspace.data.VncAuthMode.MAC_USER), onClick = {
                            focusManager.clearFocus()
                            val scanType = type
                            isScanning = true
                            coroutineScope.launch {
                                try {
                                    val found = if (scanType == ConnectionType.VNC) com.farside.workspace.utils.NetworkScanner.scanLocalNetworkForVnc(context)
                                        else com.farside.workspace.utils.NetworkScanner.scanLocalNetworkForRdp(context)
                                    if (type == scanType) discoveredHosts = found
                                    if (found.isEmpty()) android.widget.Toast.makeText(context, discoveryNoServicesMsg, android.widget.Toast.LENGTH_LONG).show()
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    android.widget.Toast.makeText(context, error.message ?: discoveryFailedMsg, android.widget.Toast.LENGTH_LONG).show()
                                } finally { isScanning = false }
                            }
                        }) {
                            Text(if (isScanning && type == ConnectionType.VNC) stringResource(R.string.finding_local_macs)
                                else if (isScanning) stringResource(R.string.finding_local_sessions, stringResource(type.labelResId))
                                else if (type == ConnectionType.VNC) stringResource(R.string.find_local_macs)
                                else stringResource(R.string.find_local_pcs))
                        }
                    }
                    if (discoveredHosts.isNotEmpty()) {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { focusManager.clearFocus(); expanded = !expanded },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).zIndex(1f)
                        ) {
                            TextField(
                                value = if (type == ConnectionType.VNC) stringResource(R.string.select_screen_sharing_server) else stringResource(R.string.select_discovered_pc),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                colors = ExposedDropdownMenuDefaults.textFieldColors(
                                    focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.DarkGray,
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                discoveredHosts.forEach { foundHost ->
                                    DropdownMenuItem(
                                        text = { Text(foundHost.name) },
                                        onClick = {
                                            focusManager.clearFocus()
                                            name = foundHost.name
                                            host = if (type == ConnectionType.RDP) foundHost.savedRdpHost() else foundHost.savedVncHost()
                                            cloudflaredHostError = null
                                            port = foundHost.port.toString()
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                SettingsTextField(host, { host = it; cloudflaredHostError = null }, stringResource(R.string.hostname_or_ip_address),
                    errorMessage = if (showValidationErrors && host.isBlank()) stringResource(R.string.profile_host_required)
                        else if (proxyType == com.farside.workspace.data.ProxyType.CLOUDFLARED) cloudflaredHostError else null)
                SettingsTextField(
                    value = port,
                    onValueChange = { port = it },
                    placeholder = stringResource(R.string.port_rdp_3389_ssh_22_vnc_5900),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    errorMessage = if (showValidationErrors && port.toIntOrNull() !in 1..65535) stringResource(R.string.profile_port_invalid) else null
                )

                SettingsHeader(when (type) { ConnectionType.SSH -> stringResource(R.string.user_account_optional_for_ssh); ConnectionType.VNC -> stringResource(if (vncAuthMode == com.farside.workspace.data.VncAuthMode.MAC_USER) R.string.vnc_auth_mac_user else R.string.vnc_password_credential); else -> stringResource(R.string.user_account) })
                var expandedCreds by rememberSaveable { mutableStateOf(false) }

                FormValidationField(credentialErrorMessage) {
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
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.DarkGray,
                            errorIndicatorColor = Color.Red,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                            .then(if (credentialErrorMessage != null) Modifier.border(2.dp, Color.Red, RoundedCornerShape(4.dp)) else Modifier),
                        isError = credentialErrorMessage != null
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
                }
                if (type == ConnectionType.VNC) {
                    val sshProfiles = savedProfiles.filter { it.type == ConnectionType.SSH }
                    var expandedPair by rememberSaveable { mutableStateOf(false) }
                    val companionLabel=stringResource(R.string.companion_remote_login)
                    SettingsHeader(companionLabel)
                    ExposedDropdownMenuBox(expanded = expandedPair, onExpandedChange = { expandedPair = !expandedPair },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        TextField(value = sshProfiles.find { it.id == sftpPairProfileId }?.name ?: stringResource(R.string.vnc_sftp_unpaired),
                            onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription=companionLabel },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedPair) })
                        ExposedDropdownMenu(expanded = expandedPair, onDismissRequest = { expandedPair = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.vnc_sftp_unpaired)) },
                                onClick = { sftpPairProfileId = ""; expandedPair = false })
                            sshProfiles.forEach { ssh -> DropdownMenuItem(text = { Text(ssh.name) },
                                onClick = { sftpPairProfileId = ssh.id; expandedPair = false }) }
                        }
                    }
                    if (sftpPairProfileId.isNotBlank()) {
                        val hasSeenWarning = savedProfiles.any { it.enableMacSshRelay }
                        SettingsSwitchRow(stringResource(R.string.mac_ssh_relay_toggle), enableMacSshRelay) { enabled ->
                            if (enabled && !hasSeenWarning) {
                                showMacRelayConfirm = true
                            } else {
                                enableMacSshRelay = enabled
                            }
                        }
                        if (enableMacSshRelay) {
                            SettingsSwitchRow(stringResource(R.string.mac_ssh_relay_same_mac), macSshRelaySameMac) {
                                macSshRelaySameMac = it
                            }
                        }
                        if (showMacRelayConfirm) {
                            AlertDialog(
                                onDismissRequest = { showMacRelayConfirm = false },
                                title = { Text(stringResource(R.string.mac_ssh_relay_dialog_title)) },
                                text = { Text(stringResource(R.string.mac_ssh_relay_dialog_desc)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        enableMacSshRelay = true
                                        showMacRelayConfirm = false
                                    }) { Text(stringResource(R.string.enable)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showMacRelayConfirm = false }) { Text(stringResource(R.string.cancel)) }
                                }
                            )
                        }
                    }
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
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.DarkGray,
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
                    val (chainPreview, chainProblem) = com.farside.workspace.ssh.JumpChains.preview(context, editedProfile, allProfiles)
                    var expandedJump by rememberSaveable { mutableStateOf(false) }
                    FormValidationField(chainProblem) {
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
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.DarkGray,
                                errorIndicatorColor = Color.Red,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                .then(if (chainProblem != null) Modifier.border(2.dp, Color.Red, RoundedCornerShape(4.dp)) else Modifier),
                            isError = chainProblem != null
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
                    }
                    val previewText = chainPreview?.takeIf { chainProblem == null && it.jumpHosts.isNotEmpty() }?.let {
                        jumpRoutePreviewFmt.format(editedProfile.name.ifBlank { thisProfileName }, it.jumpHosts.joinToString(jumpRouteSeparator) { h -> h.name })
                    }
                    if (previewText != null) {
                        Text(previewText, fontSize = 12.sp, color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }                }

                if (type == ConnectionType.SSH) {
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

                SettingsHeader(stringResource(R.string.general))
                SettingsSwitchRow(stringResource(R.string.reconnect_automatically), reconnectAutomatically, { reconnectAutomatically = it })

                SettingsHeader(stringResource(R.string.proxy_settings))
                ProxyTypeSelector(proxyType,
                    errorMessage = if (showValidationErrors && proxyType !in com.farside.workspace.data.ProxyType.choices)
                        stringResource(R.string.profile_proxy_type_invalid) else null) {
                    proxyType = it
                    cloudflaredHostError = null
                    cloudflaredParametersError = null
                }
                if (proxyType == com.farside.workspace.data.ProxyType.CLOUDFLARED) {
                    Text(stringResource(R.string.routes_the_connection_through_cloudflare_access_browser_sign_in), color = Color.Gray, modifier = Modifier.padding(16.dp))
                    SettingsTextField(cloudflaredParameters, { cloudflaredParameters = it; cloudflaredParametersError = null }, stringResource(R.string.extra_parameters_optional),
                        visualTransformation = if (showCloudflaredParameters) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        errorMessage = cloudflaredParametersError)
                    SettingsSwitchRow(stringResource(R.string.show_cloudflared_parameters), showCloudflaredParameters, { showCloudflaredParameters = it })
                    Text(stringResource(R.string.allowed_service_token_id_and_service_token_secret_or_header_h_fo), color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp))
                } else if (proxyType == com.farside.workspace.data.ProxyType.SOCKS5 || proxyType == com.farside.workspace.data.ProxyType.HTTP) {
                    Text(stringResource(if (proxyType == com.farside.workspace.data.ProxyType.SOCKS5) R.string.socks5_proxy_connect else R.string.http_proxy_connect),
                        color = Color.Gray, modifier = Modifier.padding(16.dp))
                    SettingsTextField(proxyHost, { proxyHost = it }, stringResource(R.string.proxy_hostname_or_ip),
                        errorMessage = if (showValidationErrors && proxyHost.isBlank()) stringResource(R.string.profile_proxy_host_required) else null)
                    SettingsTextField(proxyPort, { proxyPort = it }, stringResource(R.string.proxy_port),
                        errorMessage = if (showValidationErrors && proxyPort.toIntOrNull() !in 1..65535) stringResource(R.string.profile_port_invalid) else null)
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
                    SettingsSwitchRow(stringResource(R.string.connect_to_console), connectToConsole, { connectToConsole = it })
                    SettingsSwitchRow(stringResource(R.string.admin_mode), adminMode, { adminMode = it })
                }
            }
        }
    }
}
}
