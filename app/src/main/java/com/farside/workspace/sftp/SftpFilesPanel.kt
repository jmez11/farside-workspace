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
    Surface(Modifier.fillMaxSize(), color = Color(0xFF202124)) {
        val auth = security
        when {
            auth == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !unlocked && auth.first -> SystemCredentialDialog(title = stringResource(R.string.unlock_files), message = stringResource(R.string.confirm_headset_files), onUnlocked = { unlocked = true }, onCancel = onClose)
            !unlocked && auth.second != null -> CredentialPinDialog(title = stringResource(R.string.unlock_files), verifier = auth.second, onUnlocked = { unlocked = true }, onCancel = onClose)
            else -> SftpFilesPanel(onClose, profileId, allowDisabledPairedProfile)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SftpFilesPanel(onClose: () -> Unit, profileId: String?, allowDisabledPairedProfile: Boolean) {
    val context = LocalContext.current
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
            try { task(op); op.check(); status = context.getString(R.string.done) }
            catch (e: Exception) {
                Log.e("SftpFiles", "Operation failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}", e)
                error = try { op.check(); sftpError(context, e) } catch (_: java.util.concurrent.CancellationException) { context.getString(R.string.cancelled) }
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
        else work(context.getString(R.string.loading)) { refresh(it, folder) }
    }
    fun connect(id: String) {
        work(context.getString(R.string.connecting)) { op ->
            session?.close(); session = null; entries = emptyList(); path = ""; selectedId = id; listingCache.clear(); cachedView = false
            val profile = withContext(Dispatchers.IO) {
                check(com.farside.workspace.hasStoragePermission(context)) { context.getString(R.string.storage_permission_required) }
                val pm = ProfileManager(context)
                val p = pm.getProfiles().single { it.id == id && it.type == ConnectionType.SSH &&
                    (it.enableSftp || (allowDisabledPairedProfile && id == profileId)) }
                check(p.credentialId == null || pm.getCredentials().any { it.id == p.credentialId }) { context.getString(R.string.saved_credential_removed) }
                p
            }
            val next = SftpSession(context.applicationContext, profile)
            session = next
            try {
                path = withContext(Dispatchers.IO) { next.connect(
                    onStatus = { text -> scope.launch { status = text.trim().removeSurrounding("[", "]") } },
                    onExit = { scope.launch { error = context.getString(R.string.cloudflare_tunnel_exited) } }, operation = op) }
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
            work(context.getString(R.string.sftp_downloading, entry.name)) { op ->
                withContext(Dispatchers.IO) { SftpDownloads(context).downloadFolder(checkNotNull(session), entry, uri, op, report) { text -> scope.launch { if (operation === op) status = text } } }
                status = context.getString(R.string.sftp_folder_downloaded, entry.name)
            }
        }
    }
    fun download(entry: SftpEntry, overwrite: Boolean) { work(context.getString(R.string.sftp_downloading, entry.name)) { op ->
        withContext(Dispatchers.IO) { SftpDownloads(context).download(checkNotNull(session), entry, overwrite, op, report) }
        status = context.getString(R.string.saved_to_downloads)
    } }
    fun requestDownload(entry: SftpEntry) { work(context.getString(R.string.checking_downloads)) { _ ->
        val exists = withContext(Dispatchers.IO) { SftpDownloads(context).existing(entry.name) != null }
        if (exists) confirm = context.getString(R.string.sftp_replace_download, entry.name) to { download(entry, true) }
        else confirm = context.getString(R.string.sftp_confirm_download, entry.name) to { download(entry, false) }
    } }
    fun upload(file: File, overwrite: Boolean) { work(context.getString(R.string.sftp_uploading, file.name)) { op ->
        val target = remoteChild(path, file.name)
        withContext(Dispatchers.IO) { checkNotNull(session).upload(file, target, overwrite, op, report) }
        refresh(op)
    } }
    LaunchedEffect(localFolder) {
        localFolder?.let { folder ->
            try { localFiles = withContext(Dispatchers.IO) { folder.listFiles()?.filter { it.canRead() && it.canonicalFile.toPath().startsWith(Environment.getExternalStorageDirectory().canonicalFile.toPath()) }?.map { HeadsetFile(it, it.isDirectory) }?.sortedWith(compareByDescending<HeadsetFile> { it.directory }.thenBy { it.file.name.lowercase() }) ?: error(context.getString(R.string.cannot_read_folder)) } }
            catch (_: Exception) { localFiles = emptyList(); error = context.getString(R.string.headset_folder_unreadable) }
        }
    }
    val visibleEntries = remember(entries, sortByDate) {
        if (sortByDate) entries.sortedWith(compareByDescending<SftpEntry> { it.directory }.thenByDescending { it.modified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        else entries.sortedWith(compareByDescending<SftpEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back_in_files)) }
            Text(stringResource(R.string.files_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close_files)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::root, enabled = operation == null) { Text(stringResource(R.string.ssh_files_root)) }
            Text(session?.profile?.name.orEmpty(), Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (session != null) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box {
                    TextButton(onClick = { sortMenu = true }, enabled = operation == null) {
                        Icon(Icons.Default.Sort, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (sortByDate) R.string.sftp_sort_date else R.string.sftp_sort_name))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sftp_sort_name)) }, onClick = { sortByDate = false; sortMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sftp_sort_date)) }, onClick = { sortByDate = true; sortMenu = false })
                    }
                }
                TextButton(onClick = { work(context.getString(R.string.refreshing)) { refresh(it) } }, enabled = operation == null) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = { edit = context.getString(R.string.new_folder) to null; editName = "" }, enabled = operation == null) { Text(context.getString(R.string.new_folder)) }
                TextButton(onClick = { localFolder = Environment.getExternalStorageDirectory() }, enabled = operation == null) { Text(stringResource(R.string.upload)) }
            }
        }
        if (path.isNotEmpty()) Text(if (cachedView && operation == null) stringResource(R.string.sftp_cached_path, path) else path,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (operation != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f)); TextButton(onClick = { operation?.cancel(); if (path.isEmpty()) session?.close() }) { Text(stringResource(R.string.cancel)) }
            }
            val p = progress
            if (p != null && p.second > 0) {
                LinearProgressIndicator(progress = (p.first.toFloat() / p.second).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Text(if (p.second < 1024L * 1024L) stringResource(R.string.sftp_transfer_bytes, p.first, p.second)
                    else stringResource(R.string.sftp_transfer_megabytes, p.first / 1024 / 1024, p.second / 1024 / 1024))
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { message -> Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            selectedId?.let { id -> TextButton(onClick = { connect(id) }, enabled = operation == null) { Text(stringResource(R.string.reconnect)) } }
        } } }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        LazyColumn(Modifier.weight(1f), state = fileListState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedId == null) {
                if (profiles.isEmpty()) item { Text(stringResource(R.string.enable_sftp_help)) }
                items(profiles, key = { it.id }) { profile -> Card(Modifier.fillMaxWidth().clickable(enabled = operation == null) { connect(profile.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(12.dp)); Text(profile.name) }
                } }
            } else {
                if (entries.isEmpty() && operation == null && error == null) item { Text(stringResource(R.string.empty_folder)) }
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
                        Box { IconButton(onClick = { menu = entry }, enabled = operation == null) { Icon(Icons.Default.MoreVert, stringResource(R.string.sftp_entry_actions, entry.name)) }
                            DropdownMenu(expanded = menu == entry, onDismissRequest = { menu = null }) {
                                if (entry.directory && entry.link == null && !entry.unavailable) DropdownMenuItem(text = { Text(stringResource(R.string.download_folder)) }, onClick = { menu = null; pendingFolderDownload = entry; folderDestinationPicker.launch(null) })
                                if (!entry.directory && !entry.unavailable) DropdownMenuItem(text = { Text(stringResource(R.string.download)) }, onClick = { menu = null; requestDownload(entry) })
                                DropdownMenuItem(text = { Text(context.getString(R.string.rename)) }, onClick = { menu = null; edit = context.getString(R.string.rename) to entry; editName = entry.name })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = null; confirm = context.getString(if (entry.directory) R.string.sftp_confirm_delete_folder else R.string.sftp_confirm_delete_file, entry.name) to {
                                    work(context.getString(R.string.deleting)) { op -> withContext(Dispatchers.IO) { checkNotNull(session).delete(entry, op) }; listingCache.keys.removeAll { it == entry.path || it.startsWith(entry.path.trimEnd('/') + "/") }; refresh(op) }
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
    edit?.let { action -> AlertDialog(onDismissRequest = { edit = null }, title = { Text(action.first) }, text = {
        OutlinedTextField(editName, { editName = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
    }, confirmButton = { TextButton(onClick = {
        val name = editName; edit = null
        work(action.first) { op ->
            val target = remoteChild(path, name)
            withContext(Dispatchers.IO) { val current = checkNotNull(session); if (action.second == null) current.mkdir(target, op) else current.rename(action.second!!.path, target, op) }; action.second?.let { old -> listingCache.keys.removeAll { it == old.path || it.startsWith(old.path.trimEnd('/') + "/") } }
            refresh(op)
        }
    }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.cancel)) } }) }
    confirm?.let { choice -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(stringResource(R.string.confirm_file_operation)) }, text = { Text(choice.first) },
        confirmButton = { TextButton(onClick = { confirm = null; choice.second() }) { Text(stringResource(R.string.continue_action)) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } }) }
    localFolder?.let { folder -> AlertDialog(onDismissRequest = { localFolder = null }, title = { Text(stringResource(R.string.upload_from_headset)) }, text = {
        Column(Modifier.heightIn(max = 420.dp)) {
            Text(folder.absolutePath)
            if (folder != Environment.getExternalStorageDirectory()) TextButton(onClick = { localFolder = folder.parentFile }) { Text(stringResource(R.string.up)) }
            LazyColumn { items(localFiles, key = { it.file.absolutePath }) { item -> val file = item.file; TextButton(onClick = {
                if (item.directory) localFolder = file else {
                    localFolder = null
                    work(context.getString(R.string.checking_destination)) { op ->
                        val exists = withContext(Dispatchers.IO) { checkNotNull(session).exists(remoteChild(path, file.name), op) }
                        confirm = (if (exists) context.getString(R.string.sftp_replace_upload, file.name) else context.getString(R.string.sftp_confirm_upload, file.name)) to { upload(file, exists) }
                    }
                }
            }) { Text(if (item.directory) stringResource(R.string.sftp_folder_name, file.name) else file.name) } } }
        }
    }, confirmButton = { TextButton(onClick = { localFolder = null }) { Text(stringResource(R.string.cancel)) } }) }
}
