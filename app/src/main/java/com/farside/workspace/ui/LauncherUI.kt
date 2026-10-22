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
import androidx.compose.material.icons.filled.Info
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
    var isVaultOpen by rememberSaveable { mutableStateOf(false) }
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
                    val actionCount = 3 + (if (windowManagerAvailable) 1 else 0) + (if (profiles.any { it.type == ConnectionType.SSH && it.enableSftp }) 1 else 0)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!compact) Spacer(Modifier.width((actionCount * 32 + (actionCount - 1) * 2).dp))
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
                        LauncherIconButton(
                            onClick = { isAdding = true },
                            interactionSource = interaction,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (hovered) Color.Transparent else pulseColor
                            )
                        ) {
                            LauncherIcon(Icons.Default.Add, contentDescription = stringResource(R.string.add_connection), tint = Color.White)
                        }
                    }
                    if (windowManagerAvailable) {
                        CardActionTooltip(stringResource(R.string.window_manager)) {
                            LauncherIconButton(onClick = { QuestWindowManager.toggle(context) }) {
                                LauncherIcon(Icons.Default.Dashboard, contentDescription = stringResource(R.string.window_manager), tint = Color.White)
                            }
                        }
                    }
                    if (profiles.any { it.type == ConnectionType.SSH && it.enableSftp }) {
                        CardActionTooltip(stringResource(R.string.sftp_browser)) {
                            LauncherIconButton(onClick = { filesOpen = true }) {
                                LauncherIcon(androidx.compose.material.icons.Icons.Default.Folder, contentDescription = stringResource(R.string.files), tint = Color.White)
                            }
                        }
                    }
                    CardActionTooltip(stringResource(R.string.setup_vault)) {
                        LauncherIconButton(onClick={isVaultOpen=true}) {
                            LauncherIcon(Icons.Default.VpnKey,contentDescription=stringResource(R.string.setup_vault),tint=Color.White)
                        }
                    }
                    CardActionTooltip(stringResource(R.string.setup_info)) {
                        LauncherIconButton(
                            onClick = {
                                settingsInitialTab = 0
                                isSettingsOpen = true
                            },
                        ) {
                            LauncherIcon(Icons.Default.Info, contentDescription = stringResource(R.string.setup_info), tint = Color.White)
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

        if(isVaultOpen) com.farside.workspace.ui.setup.SignInVault(profileManager,onClose={credentialRevision++;isVaultOpen=false})
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
                LauncherIcon(
                    imageVector = when (profile.type) { ConnectionType.RDP -> Icons.Default.Computer; ConnectionType.SSH -> Icons.Default.Terminal; ConnectionType.VNC -> Icons.Default.DesktopMac },
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
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
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val starLabel = if (starred) stringResource(R.string.unstar_connection, profile.name)
                    else stringResource(R.string.star_connection, profile.name)
                val starTooltip = if (starred) stringResource(R.string.unfavorite)
                    else stringResource(R.string.favorite)
                CardActionTooltip(starTooltip) {
                    LauncherIconButton(onClick = onToggleStar) {
                        LauncherIcon(if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = starLabel,
                            tint = if (starred) Color(0xFFFFD166) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if ((connected || profile.cardBackground != null) && !reordering) {
                    val hasPhoto = profile.cardBackground != null
                    val captureLabel = stringResource(if (hasPhoto) R.string.remove_connection_photo else R.string.capture_connection, profile.name)
                    CardActionTooltip(captureLabel) {
                        LauncherIconButton(onClick = onCapture) {
                            LauncherIcon(if (hasPhoto) Icons.Default.ImageNotSupported else Icons.Default.PhotoCamera, captureLabel)
                        }
                    }
                }
            }
            if (reordering) {
                Row(modifier = Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LauncherIconButton(onClick = { onMoveEarlier?.invoke() }, enabled = onMoveEarlier != null) {
                        LauncherIcon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.move_connection_earlier, profile.name))
                    }
                    LauncherIconButton(onClick = { onMoveLater?.invoke() }, enabled = onMoveLater != null) {
                        LauncherIcon(Icons.Default.KeyboardArrowRight, stringResource(R.string.move_connection_later, profile.name))
                    }
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 4.dp)) {
                LauncherIconButton(onClick = { menuExpanded = true }) {
                    LauncherIcon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
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
    if(initialTab==0) com.farside.workspace.ui.setup.InfoScreen(onClose)
    else com.farside.workspace.ui.setup.SignInVault(profileManager,onClose)
}

@Composable
fun ClearCloudflareLoginsButton(compact:Boolean=false,onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val loginsClearedMsg = stringResource(R.string.cloudflare_logins_cleared)
    val loginsClearFailedMsg = stringResource(R.string.cloudflare_logins_clear_failed)
    Column(if(compact) Modifier else Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Button(
            shape=RoundedCornerShape(if(compact) 8.dp else 20.dp),
            onClick = {
                onStatus(runCatching {
                    com.farside.workspace.tunnel.CloudflaredTunnel.clearCachedLogins(context)
                    loginsClearedMsg
                }.getOrElse { it.message ?: loginsClearFailedMsg })
            },
            colors = if(compact) ButtonDefaults.buttonColors(containerColor=Color(0xFFEA5A00),contentColor=Color.White) else ButtonDefaults.buttonColors(containerColor=Color(0xFFD32F2F))
        ) { Text(stringResource(R.string.clear_cloudflare_logins),fontSize=if(compact) 12.sp else 14.sp) }
        if(!compact) Text(
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

@Composable
private fun LauncherIcon(imageVector:androidx.compose.ui.graphics.vector.ImageVector,contentDescription:String?,modifier:Modifier=Modifier.size(20.dp),tint:Color=LocalContentColor.current) {
    Icon(imageVector,contentDescription,modifier=modifier,tint=tint)
}

@Composable
private fun LauncherIconButton(onClick:()->Unit,enabled:Boolean=true,
    interactionSource:MutableInteractionSource=remember {MutableInteractionSource()},colors:IconButtonColors=IconButtonDefaults.iconButtonColors(),content:@Composable ()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(32.dp),interactionSource=interactionSource,colors=colors,content=content)
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
