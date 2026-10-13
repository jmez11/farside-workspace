package com.farside.workspace.sftp

import android.content.*
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
internal fun SftpFilesEntry(onClose: () -> Unit, profileId: String? = null) {
    val context = LocalContext.current
    var security by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        security = withContext(Dispatchers.IO) { ProfileManager(context).let { it.usesSystemPin() to it.getAppPinVerifier() } }
    }
    BackHandler { onClose() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val auth = security
        when {
            auth == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !unlocked && auth.first -> SystemCredentialDialog(title = "Unlock Files", message = "Confirm your headset passcode to browse SSH files.", onUnlocked = { unlocked = true }, onCancel = onClose)
            !unlocked && auth.second != null -> CredentialPinDialog(title = "Unlock Files", verifier = auth.second, onUnlocked = { unlocked = true }, onCancel = onClose)
            else -> SftpFilesPanel(onClose, profileId)
        }
    }
}

@Composable
private fun SftpFilesPanel(onClose: () -> Unit, profileId: String?) {
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
    LaunchedEffect(Unit) { profiles = withContext(Dispatchers.IO) { ProfileManager(context).getProfiles().filter { it.type == ConnectionType.SSH && it.enableSftp } } }
    fun work(label: String, task: suspend (SftpOperation) -> Unit) {
        if (operation != null) return
        val op = SftpOperation()
        operation = op; status = label; error = null; progress = null
        scope.launch {
            try { task(op); op.check(); status = "Done" }
            catch (e: Exception) { error = try { op.check(); sftpError(e) } catch (_: java.util.concurrent.CancellationException) { "Cancelled." } }
            finally { operation = null; progress = null }
        }
    }
    suspend fun refresh(op: SftpOperation, folder: String = path) {
        val current = checkNotNull(session)
        val result = withContext(Dispatchers.IO) { current.list(folder, op) { found, done ->
            val text = if (done == 0) "Loading… (Found $found items)" else "Loading… (Found $found items, read details $done/$found)"
            scope.launch { if (operation === op) status = text }
        } }
        op.check(); entries = result; path = folder; cachedView = false; listingCache[folder] = result
    }
    /** Shows a cached listing instantly when available; Refresh always re-reads from the server. */
    fun open(folder: String) {
        val hit = listingCache[folder]
        if (operation == null && hit != null) { entries = hit; path = folder; cachedView = true; error = null; status = "" }
        else work("Loading…") { refresh(it, folder) }
    }
    fun connect(id: String) {
        work("Connecting…") { op ->
            session?.close(); session = null; entries = emptyList(); path = ""; selectedId = id; listingCache.clear(); cachedView = false
            val profile = withContext(Dispatchers.IO) {
                check(com.farside.workspace.hasStoragePermission(context)) { "Storage permission required" }
                val pm = ProfileManager(context)
                val p = pm.getProfiles().single { it.id == id && it.type == ConnectionType.SSH && it.enableSftp }
                check(p.credentialId == null || pm.getCredentials().any { it.id == p.credentialId }) { "Saved credential removed" }
                p
            }
            val next = SftpSession(context.applicationContext, profile)
            session = next
            try {
                path = withContext(Dispatchers.IO) { next.connect(
                    onStatus = { text -> scope.launch { status = text.trim().removeSurrounding("[", "]") } },
                    onExit = { scope.launch { error = "Cloudflare tunnel exited. Reconnect to continue." } }, operation = op) }
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
    fun download(entry: SftpEntry, overwrite: Boolean) { work("Downloading ${entry.name}…") { op ->
        withContext(Dispatchers.IO) { SftpDownloads(context).download(checkNotNull(session), entry, overwrite, op, report) }
        status = "Saved to Downloads"
    } }
    fun requestDownload(entry: SftpEntry) { work("Checking Downloads…") { _ ->
        val exists = withContext(Dispatchers.IO) { SftpDownloads(context).existing(entry.name) != null }
        if (exists) confirm = "Replace ${entry.name} in Downloads?" to { download(entry, true) }
        else confirm = "Download ${entry.name} to Downloads?" to { download(entry, false) }
    } }
    fun upload(file: File, overwrite: Boolean) { work("Uploading ${file.name}…") { op ->
        val target = remoteChild(path, file.name)
        withContext(Dispatchers.IO) { checkNotNull(session).upload(file, target, overwrite, op, report) }
        refresh(op)
    } }
    LaunchedEffect(localFolder) {
        localFolder?.let { folder ->
            try { localFiles = withContext(Dispatchers.IO) { folder.listFiles()?.filter { it.canRead() && it.canonicalFile.toPath().startsWith(Environment.getExternalStorageDirectory().canonicalFile.toPath()) }?.map { HeadsetFile(it, it.isDirectory) }?.sortedWith(compareByDescending<HeadsetFile> { it.directory }.thenBy { it.file.name.lowercase() }) ?: error("Cannot read folder") } }
            catch (_: Exception) { localFiles = emptyList(); error = "This headset folder cannot be read." }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, "Back in Files") }
            Text("Farside Files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close Files") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::root, enabled = operation == null) { Text("Farside SSH") }
            Text(session?.profile?.name.orEmpty(), Modifier.weight(1f))
            if (session != null) {
                TextButton(onClick = { work("Refreshing…") { refresh(it) } }, enabled = operation == null) { Text("Refresh") }
                TextButton(onClick = { edit = "New folder" to null; editName = "" }, enabled = operation == null) { Text("New folder") }
                TextButton(onClick = { localFolder = Environment.getExternalStorageDirectory() }, enabled = operation == null) { Text("Upload") }
            }
        }
        if (path.isNotEmpty()) Text(if (cachedView && operation == null) "$path · cached (Refresh for latest)" else path, style = MaterialTheme.typography.bodyMedium)
        if (operation != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f)); TextButton(onClick = { operation?.cancel(); if (path.isEmpty()) session?.close() }) { Text("Cancel") }
            }
            val p = progress
            if (p != null && p.second > 0) {
                LinearProgressIndicator(progress = (p.first.toFloat() / p.second).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Text("${p.first / 1024 / 1024} / ${p.second / 1024 / 1024} MB")
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { message -> Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            selectedId?.let { id -> TextButton(onClick = { connect(id) }, enabled = operation == null) { Text("Reconnect") } }
        } } }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        LazyColumn(Modifier.weight(1f), state = fileListState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedId == null) {
                if (profiles.isEmpty()) item { Text("Enable SFTP file access in an SSH connection’s settings to see it here.") }
                items(profiles, key = { it.id }) { profile -> Card(Modifier.fillMaxWidth().clickable(enabled = operation == null) { connect(profile.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(12.dp)); Text(profile.name) }
                } }
            } else {
                if (entries.isEmpty() && operation == null && error == null) item { Text("This folder is empty.") }
                items(entries, key = { it.path }) { entry -> Card(Modifier.fillMaxWidth().alpha(if (entry.unavailable) 0.45f else 1f)) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !entry.unavailable && operation == null) {
                        if (entry.directory) open(entry.path) else requestDownload(entry)
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(entry.name)
                            entry.link?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall) }
                            Text(if (entry.unavailable) "Unavailable or permission denied" else
                                "${if (entry.directory) "Folder" else "${entry.size} bytes"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modified))}", style = MaterialTheme.typography.bodySmall)
                        }
                        Box { IconButton(onClick = { menu = entry }, enabled = operation == null) { Icon(Icons.Default.MoreVert, "Actions for ${entry.name}") }
                            DropdownMenu(expanded = menu == entry, onDismissRequest = { menu = null }) {
                                if (!entry.directory && !entry.unavailable) DropdownMenuItem(text = { Text("Download") }, onClick = { menu = null; requestDownload(entry) })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = null; edit = "Rename" to entry; editName = entry.name })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = null; confirm = "Delete ${entry.name}? Folders must be empty." to {
                                    work("Deleting…") { op -> withContext(Dispatchers.IO) { checkNotNull(session).delete(entry, op) }; listingCache.keys.removeAll { it == entry.path || it.startsWith(entry.path.trimEnd('/') + "/") }; refresh(op) }
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
        OutlinedTextField(editName, { editName = it }, label = { Text("Name") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = {
        val name = editName; edit = null
        work(action.first) { op ->
            val target = remoteChild(path, name)
            withContext(Dispatchers.IO) { val current = checkNotNull(session); if (action.second == null) current.mkdir(target, op) else current.rename(action.second!!.path, target, op) }; action.second?.let { old -> listingCache.keys.removeAll { it == old.path || it.startsWith(old.path.trimEnd('/') + "/") } }
            refresh(op)
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } }) }
    confirm?.let { choice -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm file operation") }, text = { Text(choice.first) },
        confirmButton = { TextButton(onClick = { confirm = null; choice.second() }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
    localFolder?.let { folder -> AlertDialog(onDismissRequest = { localFolder = null }, title = { Text("Upload from headset") }, text = {
        Column(Modifier.heightIn(max = 420.dp)) {
            Text(folder.absolutePath)
            if (folder != Environment.getExternalStorageDirectory()) TextButton(onClick = { localFolder = folder.parentFile }) { Text("Up") }
            LazyColumn { items(localFiles, key = { it.file.absolutePath }) { item -> val file = item.file; TextButton(onClick = {
                if (item.directory) localFolder = file else {
                    localFolder = null
                    work("Checking destination…") { op ->
                        val exists = withContext(Dispatchers.IO) { checkNotNull(session).exists(remoteChild(path, file.name), op) }
                        confirm = (if (exists) "Replace ${file.name} on the server?" else "Upload ${file.name} to this folder?") to { upload(file, exists) }
                    }
                }
            }) { Text(if (item.directory) "${file.name}/" else file.name) } } }
        }
    }, confirmButton = { TextButton(onClick = { localFolder = null }) { Text("Cancel") } }) }
}
