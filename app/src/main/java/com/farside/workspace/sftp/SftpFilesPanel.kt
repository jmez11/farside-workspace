package com.farside.workspace.sftp

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.*
import android.os.Environment
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.farside.workspace.data.*
import com.farside.workspace.ui.CredentialPinDialog
import com.farside.workspace.ui.SystemCredentialDialog
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class HeadsetFile(val file: File, val directory: Boolean)

@Composable
private fun TruncatedPathText(
    path: String,
    cachedText: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color(0xFFF1F0F4)
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val maxWidthPx = with(density) { maxWidth.toPx() }

        val displayText = remember(path, cachedText, maxWidthPx, style) {
            if (path.isEmpty() || maxWidthPx <= 0) return@remember ""
            if (cachedText != null) {
                val cachedWidth = textMeasurer.measure(cachedText, style = style, maxLines = 1).size.width
                if (cachedWidth <= maxWidthPx) return@remember cachedText
            }
            val pathWidth = textMeasurer.measure(path, style = style, maxLines = 1).size.width
            if (pathWidth <= maxWidthPx) return@remember path

            var low = 1
            var high = path.length
            var best = "..."
            while (low <= high) {
                val mid = (low + high) / 2
                val candidate = "..." + path.takeLast(mid)
                val width = textMeasurer.measure(candidate, style = style, maxLines = 1).size.width
                if (width <= maxWidthPx) {
                    best = candidate
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            best
        }

        Text(
            text = displayText,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SftpListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layout = state.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo.size.coerceAtLeast(1)
    if (total <= visible) return
    val fraction = (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val thumbColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.width(24.dp).fillMaxHeight().pointerInput(state, total) {
        fun scrollTo(y: Float) {
            val target = ((y / size.height) * total).toInt().coerceIn(0, total - 1)
            scope.launch { state.scrollToItem(target) }
        }
        detectDragGestures(onDragStart = { scrollTo(it.y) }) { change, _ ->
            change.consume()
            scrollTo(change.position.y)
        }
    }, contentAlignment = Alignment.TopCenter) {
        val trackHeight = constraints.maxHeight.toFloat()
        val thumbHeight = (trackHeight * visible / total).coerceAtLeast(with(density) { 32.dp.toPx() }).coerceAtMost(trackHeight)
        val thumbTop = (trackHeight - thumbHeight) * fraction
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 3.dp)) {
            val inset = size.width / 2f
            drawLine(trackColor, Offset(inset, 0f), Offset(inset, size.height), strokeWidth = 2.dp.toPx())
            val normalizedTop = (thumbTop / trackHeight * size.height).coerceIn(0f, size.height)
            val normalizedHeight = (thumbHeight / trackHeight * size.height).coerceAtLeast(2.dp.toPx())
            drawLine(thumbColor, Offset(inset, normalizedTop), Offset(inset, (normalizedTop + normalizedHeight).coerceAtMost(size.height)), strokeWidth = 4.dp.toPx())
        }
    }
}

@Composable
internal fun SftpFilesEntry(onClose: () -> Unit, profileId: String? = null, allowDisabledPairedProfile: Boolean = false) {
    val context = LocalContext.current
    var security by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        security = withContext(Dispatchers.IO) { ProfileManager(context).let { it.usesSystemPin() to it.getAppPinVerifier() } }
    }
    BackHandler { onClose() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF1F0F4)) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF202124), contentColor = Color(0xFFF1F0F4)) {
                val auth = security
                when {
                    auth == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFF1F0F4)) }
                    !unlocked && auth.first -> SystemCredentialDialog(title = stringResource(R.string.unlock_files), message = stringResource(R.string.confirm_headset_files), onUnlocked = { unlocked = true }, onCancel = onClose)
                    !unlocked && auth.second != null -> CredentialPinDialog(title = stringResource(R.string.unlock_files), verifier = auth.second, onUnlocked = { unlocked = true }, onCancel = onClose)
                    else -> SftpFilesPanel(onClose, profileId, allowDisabledPairedProfile)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SftpFilesPanel(onClose: () -> Unit, profileId: String?, allowDisabledPairedProfile: Boolean) {
    val context = LocalContext.current
    // Hoisted for LocalContextGetResourceValueCall: resolve in composable scope, use in callbacks.
    val doneLabel = stringResource(R.string.done)
    val cancelledLabel = stringResource(R.string.cancelled)
    val loadingLabel = stringResource(R.string.loading)
    val connectingLabel = stringResource(R.string.connecting)
    val storagePermissionRequiredMsg = stringResource(R.string.storage_permission_required)
    val savedCredentialRemovedMsg = stringResource(R.string.saved_credential_removed)
    val cloudflareTunnelExitedMsg = stringResource(R.string.cloudflare_tunnel_exited)
    val savedToDownloadsMsg = stringResource(R.string.saved_to_downloads)
    val checkingDownloadsLabel = stringResource(R.string.checking_downloads)
    val headsetFolderUnreadableMsg = stringResource(R.string.headset_folder_unreadable)
    val refreshingLabel = stringResource(R.string.refreshing)
    val newFolderLabel = stringResource(R.string.new_folder)
    val cannotReadFolderMsg = stringResource(R.string.cannot_read_folder)
    val renameLabel = stringResource(R.string.rename)
    val deletingLabel = stringResource(R.string.deleting)
    val checkingDestinationLabel = stringResource(R.string.checking_destination)
    val sftpDownloadingFmt = stringResource(R.string.sftp_downloading)
    val sftpFolderDownloadedFmt = stringResource(R.string.sftp_folder_downloaded)
    val sftpReplaceDownloadFmt = stringResource(R.string.sftp_replace_download)
    val sftpConfirmDownloadFmt = stringResource(R.string.sftp_confirm_download)
    val sftpUploadingFmt = stringResource(R.string.sftp_uploading)
    val sftpReplaceUploadFmt = stringResource(R.string.sftp_replace_upload)
    val sftpConfirmUploadFmt = stringResource(R.string.sftp_confirm_upload)
    val sftpConfirmDeleteFolderFmt = stringResource(R.string.sftp_confirm_delete_folder)
    val sftpConfirmDeleteFileFmt = stringResource(R.string.sftp_confirm_delete_file)
    val scope = rememberCoroutineScope()
    val fileListState = rememberLazyListState()
    var profiles by remember { mutableStateOf<List<ConnectionProfile>>(emptyList()) }
    var session by remember { mutableStateOf<SftpSession?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    val listingCache = remember { HashMap<String, List<SftpEntry>>() }
    var cachedView by remember { mutableStateOf(false) }
    var sortByDate by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var autoConnected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<SftpOperation?>(null) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var menu by remember { mutableStateOf<SftpEntry?>(null) }
    var edit by remember { mutableStateOf<Pair<String, SftpEntry?>?>(null) }
    var editName by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var localFolder by remember { mutableStateOf<File?>(null) }
    var pendingFolderDownload by remember { mutableStateOf<SftpEntry?>(null) }
    var localFiles by remember { mutableStateOf<List<HeadsetFile>>(emptyList()) }
    val latestSession by rememberUpdatedState(session)
    val latestOperation by rememberUpdatedState(operation)
    val latestClose by rememberUpdatedState(onClose)
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                latestOperation?.cancel(); latestSession?.close(); latestClose()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        onDispose { context.unregisterReceiver(receiver); latestOperation?.cancel(); latestSession?.close() }
    }
    LaunchedEffect(profileId, allowDisabledPairedProfile) { profiles = withContext(Dispatchers.IO) {
        ProfileManager(context).getProfiles().filter { it.type == ConnectionType.SSH &&
            (it.enableSftp || (allowDisabledPairedProfile && it.id == profileId)) }
    } }
    fun work(label: String, task: suspend (SftpOperation) -> Unit) {
        if (operation != null) return
        val op = SftpOperation()
        operation = op; status = label; error = null; progress = null
        scope.launch {
            try { task(op); op.check(); status = doneLabel }
            catch (e: Exception) {
                Log.e("SftpFiles", "Operation failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}", e)
                val isConnError = e is net.schmizz.sshj.transport.TransportException ||
                    e is net.schmizz.sshj.connection.ConnectionException ||
                    e is SftpUnavailable ||
                    (e is IllegalStateException && e.message == "Connection lost") ||
                    (session?.isConnected == false)
                if (isConnError) {
                    session?.close()
                    session = null
                }
                error = try { op.check(); sftpError(context, e) } catch (_: java.util.concurrent.CancellationException) { cancelledLabel }
            }
            finally { operation = null; progress = null }
        }
    }
    suspend fun refresh(op: SftpOperation, folder: String = path) {
        val current = checkNotNull(session)
        val result = withContext(Dispatchers.IO) { current.list(folder, op) }
        op.check(); entries = result; path = folder; cachedView = false; listingCache[folder] = result
    }
    /** Shows a cached listing instantly when available; Refresh always re-reads from the server. */
    fun open(folder: String) {
        val hit = listingCache[folder]
        if (operation == null && hit != null) { entries = hit; path = folder; cachedView = true; error = null; status = "" }
        else work(loadingLabel) { refresh(it, folder) }
    }
    fun connect(id: String) {
        work(connectingLabel) { op ->
            session?.close(); session = null; entries = emptyList(); path = ""; selectedId = id; listingCache.clear(); cachedView = false
            val profile = withContext(Dispatchers.IO) {
                check(com.farside.workspace.hasStoragePermission(context)) { storagePermissionRequiredMsg }
                val pm = ProfileManager(context)
                val p = pm.getProfiles().single { it.id == id && it.type == ConnectionType.SSH &&
                    (it.enableSftp || (allowDisabledPairedProfile && id == profileId)) }
                check(p.credentialId == null || pm.getCredentials().any { it.id == p.credentialId }) { savedCredentialRemovedMsg }
                p
            }
            val next = SftpSession(context.applicationContext, profile)
            session = next
            try {
                path = withContext(Dispatchers.IO) { next.connect(
                    onStatus = { text -> scope.launch { status = text.trim().removeSurrounding("[", "]") } },
                    onExit = { scope.launch { session?.close(); session = null; error = cloudflareTunnelExitedMsg } }, operation = op) }
                op.check(); refresh(op)
            } catch (e: Exception) { next.close(); session = null; throw e }
        }
    }
    LaunchedEffect(profiles) {
        if (profileId != null && !autoConnected && profiles.any { it.id == profileId }) { autoConnected = true; connect(profileId) }
    }
    fun root() { operation?.cancel(); session?.close(); session = null; selectedId = null; entries = emptyList(); path = ""; error = null; listingCache.clear(); cachedView = false }
    fun back() {
        if (operation != null) { operation?.cancel(); return }
        if (localFolder != null) { localFolder = null; return }
        if (selectedId == null) onClose()
        else if (path.isEmpty() || path == "/") { if (profileId != null) onClose() else root() }
        else open(path.substringBeforeLast('/').ifEmpty { "/" })
    }
    BackHandler { back() }
    val report: (Long, Long) -> Unit = remember { var last = 0L; { done, total ->
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last >= 100 || done == total) { last = now; scope.launch { progress = done to total } }
    } }
    val folderDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val entry = pendingFolderDownload
        pendingFolderDownload = null
        if (uri != null && entry != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            work(sftpDownloadingFmt.format(entry.name)) { op ->
                withContext(Dispatchers.IO) { SftpDownloads(context).downloadFolder(checkNotNull(session), entry, uri, op, report) { text -> scope.launch { if (operation === op) status = text } } }
                status = sftpFolderDownloadedFmt.format(entry.name)
            }
        }
    }
    fun download(entry: SftpEntry, overwrite: Boolean) { work(sftpDownloadingFmt.format(entry.name)) { op ->
        withContext(Dispatchers.IO) { SftpDownloads(context).download(checkNotNull(session), entry, overwrite, op, report) }
        status = savedToDownloadsMsg
    } }
    fun requestDownload(entry: SftpEntry) { work(checkingDownloadsLabel) { _ ->
        val exists = withContext(Dispatchers.IO) { SftpDownloads(context).existing(entry.name) != null }
        if (exists) confirm = sftpReplaceDownloadFmt.format(entry.name) to { download(entry, true) }
        else confirm = sftpConfirmDownloadFmt.format(entry.name) to { download(entry, false) }
    } }
    fun upload(file: File, overwrite: Boolean) { work(sftpUploadingFmt.format(file.name)) { op ->
        val target = remoteChild(path, file.name)
        withContext(Dispatchers.IO) { checkNotNull(session).upload(file, target, overwrite, op, report) }
        refresh(op)
    } }
    LaunchedEffect(localFolder) {
        localFolder?.let { folder ->
            try { localFiles = withContext(Dispatchers.IO) { folder.listFiles()?.filter { it.canRead() && it.canonicalFile.toPath().startsWith(Environment.getExternalStorageDirectory().canonicalFile.toPath()) }?.map { HeadsetFile(it, it.isDirectory) }?.sortedWith(compareByDescending<HeadsetFile> { it.directory }.thenBy { it.file.name.lowercase() }) ?: error(cannotReadFolderMsg) } }
            catch (_: Exception) { localFiles = emptyList(); error = headsetFolderUnreadableMsg }
        }
    }
    val visibleEntries = remember(entries, sortByDate) {
        if (sortByDate) entries.sortedWith(compareByDescending<SftpEntry> { it.directory }.thenByDescending { it.modified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        else entries.sortedWith(compareByDescending<SftpEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    val connectionName = session?.profile?.name ?: profiles.find { it.id == selectedId }?.name
    val titleText = connectionName.orEmpty()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(titleText, style = MaterialTheme.typography.headlineMedium, color = Color(0xFFF1F0F4), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.close_files), modifier = Modifier.size(22.dp), tint = Color(0xFFF1F0F4))
            }
        }
        if (session != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TruncatedPathText(
                    path = path,
                    cachedText = if (cachedView && operation == null) stringResource(R.string.sftp_cached_path, path) else null,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF1F0F4)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = ::back, enabled = operation == null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back_in_files), modifier = Modifier.size(20.dp), tint = if (operation == null) Color(0xFFF1F0F4) else Color(0xFF757575))
                    }
                    IconButton(onClick = ::root, enabled = operation == null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Home, stringResource(R.string.key_home), modifier = Modifier.size(20.dp), tint = if (operation == null) Color(0xFFF1F0F4) else Color(0xFF757575))
                    }
                    IconButton(onClick = { work(refreshingLabel) { refresh(it) } }, enabled = operation == null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh), modifier = Modifier.size(20.dp), tint = if (operation == null) Color(0xFFF1F0F4) else Color(0xFF757575))
                    }
                    IconButton(onClick = { edit = newFolderLabel to null; editName = "" }, enabled = operation == null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.CreateNewFolder, newFolderLabel, modifier = Modifier.size(20.dp), tint = if (operation == null) Color(0xFFF1F0F4) else Color(0xFF757575))
                    }
                    IconButton(onClick = { localFolder = Environment.getExternalStorageDirectory() }, enabled = operation == null, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Upload, stringResource(R.string.upload), modifier = Modifier.size(20.dp), tint = if (operation == null) Color(0xFFF1F0F4) else Color(0xFF757575))
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }, enabled = operation == null, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Sort, stringResource(if (sortByDate) R.string.sftp_sort_date else R.string.sftp_sort_name), modifier = Modifier.size(20.dp), tint = if (operation == null) Color(0xFFF1F0F4) else Color(0xFF757575))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.sftp_sort_name), color = Color(0xFFF1F0F4)) }, onClick = { sortByDate = false; sortMenu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sftp_sort_date), color = Color(0xFFF1F0F4)) }, onClick = { sortByDate = true; sortMenu = false })
                        }
                    }
                }
            }
        }
        if (operation != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f), color = Color(0xFFF1F0F4))
                TextButton(onClick = { operation?.cancel(); if (path.isEmpty()) session?.close() }) { Text(stringResource(R.string.cancel), color = Color(0xFFB9B7C0)) }
            }
            val p = progress
            if (p != null && p.second > 0) {
                LinearProgressIndicator(progress = (p.first.toFloat() / p.second).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Text(if (p.second < 1024L * 1024L) stringResource(R.string.sftp_transfer_bytes, p.first, p.second)
                    else stringResource(R.string.sftp_transfer_megabytes, p.first / 1024 / 1024, p.second / 1024 / 1024), color = Color(0xFFB9B7C0))
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { message -> Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF292A2E))) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            val isDisconnected = session == null || session?.isConnected == false
            if (isDisconnected) {
                selectedId?.let { id -> TextButton(onClick = { connect(id) }, enabled = operation == null) { Text(stringResource(R.string.reconnect), color = Color(0xFFF1F0F4)) } }
            }
        } } }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        LazyColumn(Modifier.weight(1f), state = fileListState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedId == null) {
                if (profiles.isEmpty()) item { Text(stringResource(R.string.enable_sftp_help), color = Color(0xFFB9B7C0)) }
                items(profiles, key = { it.id }) { profile -> Card(
                    Modifier.fillMaxWidth().clickable(enabled = operation == null) { connect(profile.id) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF292A2E)),
                    border = BorderStroke(1.dp, Color(0xFF393A3F))
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFFE4E1EA))
                        Spacer(Modifier.width(12.dp))
                        Text(profile.name, color = Color(0xFFF1F0F4), style = MaterialTheme.typography.titleMedium)
                    }
                } }
            } else {
                if (entries.isEmpty() && operation == null && error == null) item { Text(stringResource(R.string.empty_folder), color = Color(0xFFB9B7C0)) }
                items(visibleEntries, key = { it.path }) { entry -> Card(
                    Modifier.fillMaxWidth().alpha(if (entry.unavailable) 0.45f else 1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF292A2E)),
                    border = BorderStroke(1.dp, Color(0xFF393A3F))
                ) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !entry.unavailable && operation == null) {
                        if (entry.directory) open(entry.path) else requestDownload(entry)
                    }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(54.dp), shape = RoundedCornerShape(11.dp), color = Color(0xFF303136), border = BorderStroke(1.dp, Color(0xFF45464C))) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = Color(0xFFE4E1EA), modifier = Modifier.size(28.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                            Text(entry.name, color = Color(0xFFF1F0F4), style = MaterialTheme.typography.titleMedium)
                            entry.link?.let { Text(stringResource(R.string.sftp_link_target, it), color = Color(0xFFB9B7C0), style = MaterialTheme.typography.bodySmall) }
                            Text(if (entry.unavailable) stringResource(R.string.file_unavailable) else
                                stringResource(R.string.sftp_entry_details, if (entry.directory) stringResource(R.string.folder) else pluralStringResource(R.plurals.sftp_file_bytes, entry.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), entry.size), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modified))), color = Color(0xFFB9B7C0), style = MaterialTheme.typography.bodySmall)
                        }
                        Box { IconButton(onClick = { menu = entry }, enabled = operation == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.sftp_entry_actions, entry.name), modifier = Modifier.size(20.dp), tint = Color(0xFFE4E1EA)) }
                            DropdownMenu(expanded = menu == entry, onDismissRequest = { menu = null }) {
                                if (entry.directory && entry.link == null && !entry.unavailable) DropdownMenuItem(text = { Text(stringResource(R.string.download_folder), color = Color(0xFFF1F0F4)) }, onClick = { menu = null; pendingFolderDownload = entry; folderDestinationPicker.launch(null) })
                                if (!entry.directory && !entry.unavailable) DropdownMenuItem(text = { Text(stringResource(R.string.download), color = Color(0xFFF1F0F4)) }, onClick = { menu = null; requestDownload(entry) })
                                DropdownMenuItem(text = { Text(renameLabel, color = Color(0xFFF1F0F4)) }, onClick = { menu = null; edit = renameLabel to entry; editName = entry.name })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = Color(0xFFF1F0F4)) }, onClick = { menu = null; confirm = (if (entry.directory) sftpConfirmDeleteFolderFmt else sftpConfirmDeleteFileFmt).format(entry.name) to {
                                    work(deletingLabel) { op -> withContext(Dispatchers.IO) { checkNotNull(session).delete(entry, op) }; listingCache.keys.removeAll { it == entry.path || it.startsWith(entry.path.trimEnd('/') + "/") }; refresh(op) }
                                } })
                            }
                        }
                    }
                } }
            }
        }
        if (selectedId != null) SftpListScrollbar(fileListState, Modifier.fillMaxHeight())
        }
    }
    edit?.let { action -> AlertDialog(onDismissRequest = { edit = null },
        title = { Text(action.first, color = Color(0xFFF1F0F4)) },
        text = {
            OutlinedTextField(
                value = editName,
                onValueChange = { editName = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFF1F0F4),
                    unfocusedTextColor = Color(0xFFF1F0F4),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF606166),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color(0xFFB9B7C0)
                )
            )
        },
        confirmButton = { TextButton(onClick = {
            val name = editName; edit = null
            work(action.first) { op ->
                val target = remoteChild(path, name)
                withContext(Dispatchers.IO) { val current = checkNotNull(session); if (action.second == null) current.mkdir(target, op) else current.rename(action.second!!.path, target, op) }; action.second?.let { old -> listingCache.keys.removeAll { it == old.path || it.startsWith(old.path.trimEnd('/') + "/") } }
                refresh(op)
            }
        }) { Text(stringResource(R.string.save), color = Color(0xFFF1F0F4)) } },
        dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.cancel), color = Color(0xFFB9B7C0)) } }
    ) }
    confirm?.let { choice -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(stringResource(R.string.confirm_file_operation), color = Color(0xFFF1F0F4)) },
        text = { Text(choice.first, color = Color(0xFFF1F0F4)) },
        confirmButton = { TextButton(onClick = { confirm = null; choice.second() }) { Text(stringResource(R.string.continue_action), color = Color(0xFFF1F0F4)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel), color = Color(0xFFB9B7C0)) } }
    ) }
    localFolder?.let { folder -> AlertDialog(onDismissRequest = { localFolder = null },
        title = { Text(stringResource(R.string.upload_from_headset), color = Color(0xFFF1F0F4)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(folder.absolutePath, color = Color(0xFFB9B7C0))
                if (folder != Environment.getExternalStorageDirectory()) TextButton(onClick = { localFolder = folder.parentFile }) { Text(stringResource(R.string.up), color = Color(0xFFF1F0F4)) }
                LazyColumn { items(localFiles, key = { it.file.absolutePath }) { item -> val file = item.file; TextButton(onClick = {
                    if (item.directory) localFolder = file else {
                        localFolder = null
                        work(checkingDestinationLabel) { op ->
                            val exists = withContext(Dispatchers.IO) { checkNotNull(session).exists(remoteChild(path, file.name), op) }
                            confirm = (if (exists) sftpReplaceUploadFmt.format(file.name) else sftpConfirmUploadFmt.format(file.name)) to { upload(file, exists) }
                        }
                    }
                }) { Text(if (item.directory) stringResource(R.string.sftp_folder_name, file.name) else file.name, color = Color(0xFFF1F0F4)) } } }
            }
        },
        confirmButton = { TextButton(onClick = { localFolder = null }) { Text(stringResource(R.string.cancel), color = Color(0xFFB9B7C0)) } }
    ) }
}
