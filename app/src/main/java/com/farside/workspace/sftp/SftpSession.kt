package com.farside.workspace.sftp

import com.farside.workspace.R
import com.farside.workspace.LocalizableException
import com.farside.workspace.resolve
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.ssh.SshConnectionManager
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.*
import net.schmizz.sshj.xfer.*
import java.io.*
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class SftpOperation {
    private val cancelled = AtomicBoolean()
    private var committing = false
    private val resources = mutableListOf<Closeable>()
    fun check() { if (cancelled.get()) throw CancellationException("Cancelled") }
    @Synchronized fun <T : Closeable> track(resource: T): T {
        if (cancelled.get()) { resource.close(); throw CancellationException("Cancelled") }
        resources.add(resource)
        return resource
    }
    fun cancel() {
        val toClose = synchronized(this) {
            // Once the completed file is being published, cancellation must not interrupt replacement.
            if (committing) return
            cancelled.set(true)
            resources.toList()
        }
        // Closing an SFTP channel may block; never close it on the UI thread.
        Thread({ toClose.asReversed().forEach { runCatching { it.close() } } }, "sftp-cancel").start()
    }
    fun <T> commit(block: () -> T): T {
        synchronized(this) { check(); committing = true }
        return block()
    }
}

internal data class SftpEntry(val name: String, val path: String, val directory: Boolean,
    val size: Long, val modified: Long, val link: String? = null, val unavailable: Boolean = false)

internal fun sftpName(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) { "invalid_sftp_name" }
    return name
}
internal fun remoteChild(parent: String, name: String) = parent.trimEnd('/') + "/" + sftpName(name)
internal fun sftpError(context: Context, error: Throwable): String = when {
    error is CancellationException -> context.getString(R.string.cancelled)
    error is net.schmizz.sshj.userauth.UserAuthException -> context.getString(R.string.sftp_auth_failed)
    error is SftpUnavailable -> context.getString(R.string.sftp_subsystem_unavailable)
    error is SFTPException && error.statusCode == Response.StatusCode.PERMISSION_DENIED -> context.getString(R.string.sftp_permission_denied)
    error is SFTPException && error.statusCode == Response.StatusCode.NO_SUCH_FILE -> context.getString(R.string.sftp_file_missing)
    error is FileNotFoundException -> context.getString(R.string.sftp_local_file_unavailable)
    error is LocalizableException -> error.resolve(context)
    error is IllegalArgumentException && error.message == "invalid_sftp_name" -> context.getString(R.string.sftp_file_name_help)
    error is IllegalArgumentException -> error.message ?: context.getString(R.string.sftp_invalid_file_name)
    error is IllegalStateException && error.message == context.getString(R.string.storage_permission_required) -> context.getString(R.string.sftp_storage_permission_help)
    error is IllegalStateException && error.message == context.getString(R.string.saved_credential_removed) -> context.getString(R.string.sftp_credential_removed_help)
    error is IllegalStateException && error.message?.contains("already exists") == true -> context.getString(R.string.sftp_file_exists)
    error is IllegalStateException && error.message?.contains("Cloudflare", true) == true -> context.getString(R.string.sftp_cloudflare_failed)
    else -> context.getString(R.string.sftp_operation_failed)
}
internal class SftpUnavailable(cause: Exception) : IOException("SFTP unavailable", cause)

/** One lease per open panel, one SFTP channel per operation; all methods except close run on IO. */
internal class SftpSession(private val context: Context, val profile: ConnectionProfile) : AutoCloseable {
    private val lease = SshConnectionManager.acquire(context, profile.id)
    private lateinit var ssh: SSHClient
    private val lifetime = Any()
    private var active = 0
    private var closing = false
    private val depth = ThreadLocal.withInitial { 0 }
    private fun <T> usingTransport(block: () -> T): T {
        synchronized(lifetime) {
            check(!closing || depth.get() > 0) { "Files panel closed" }
            active++
            depth.set(depth.get() + 1)
        }
        try { return block() }
        finally {
            val release = synchronized(lifetime) { depth.set(depth.get() - 1); active--; closing && active == 0 }
            if (release) lease.close()
        }
    }
    fun connect(onStatus: (String) -> Unit, onExit: () -> Unit, operation: SftpOperation = SftpOperation()): String {
        ssh = lease.connect(profile, onStatus = onStatus, onExit = onExit)
        return channel(operation) { it.canonicalize(".") }
    }
    private fun <T> channel(operation: SftpOperation, block: (SFTPClient) -> T): T = usingTransport {
        check(Looper.myLooper() != Looper.getMainLooper())
        operation.check()
        check(::ssh.isInitialized && ssh.isConnected) { "Connection lost" }
        val sftp = try { ssh.newSFTPClient() } catch (e: net.schmizz.sshj.connection.ConnectionException) { throw SftpUnavailable(e) }
        sftp.sftpEngine.timeoutMs = 30_000
        operation.track(sftp).use { operation.check(); block(it) }
    }
    fun list(path: String, operation: SftpOperation): List<SftpEntry> = channel(operation) { sftp ->
        val items = sftp.ls(path).filter { it.name != "." && it.name != ".." }
        items.map { item ->
            operation.check()
            var link: String? = null
            var unavailable = false
            val attributes = if (item.attributes.type != FileMode.Type.SYMLINK) item.attributes else try {
                link = sftp.readlink(item.path)
                sftp.stat(item.path)
            } catch (_: SFTPException) { unavailable = true; item.attributes }
            SftpEntry(item.name, item.path, attributes.type == FileMode.Type.DIRECTORY,
                attributes.size, attributes.mtime * 1000, link, unavailable)
        }.sortedWith(compareByDescending<SftpEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    fun exists(path: String, operation: SftpOperation) = channel(operation) { sftp ->
        try { sftp.lstat(path); true } catch (e: SFTPException) { if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) false else throw e }
    }
    fun mkdir(path: String, operation: SftpOperation) = channel(operation) { it.mkdir(path) }
    fun rename(path: String, target: String, operation: SftpOperation) = channel(operation) { sftp ->
        check(sftp.statExistence(target) == null) { "Destination already exists" }
        sftp.rename(path, target)
    }
    fun delete(entry: SftpEntry, operation: SftpOperation) = channel(operation) {
        // Never recursively delete or follow a symlink while deleting.
        if (entry.directory && entry.link == null) it.rmdir(entry.path) else it.rm(entry.path)
    }
    private fun progress(sftp: SFTPClient, op: SftpOperation, update: (Long, Long) -> Unit) {
        sftp.fileTransfer.transferListener = object : TransferListener {
            override fun directory(name: String): TransferListener = this
            override fun file(name: String, size: Long) = net.schmizz.sshj.common.StreamCopier.Listener { done -> op.check(); update(done, size) }
        }
    }
    fun download(path: String, output: OutputStream, operation: SftpOperation, update: (Long, Long) -> Unit) = channel(operation) { sftp ->
        progress(sftp, operation, update)
        val stream = operation.track(output)
        sftp.get(path, object : InMemoryDestFile() {
            override fun getLength() = 0L
            override fun getOutputStream() = stream
            override fun getOutputStream(append: Boolean) = stream
        })
        operation.check()
    }
    fun upload(source: File, target: String, overwrite: Boolean, operation: SftpOperation, update: (Long, Long) -> Unit) = usingTransport {
        val temp = remoteChild(target.substringBeforeLast('/'), ".farside-${UUID.randomUUID()}.partial")
        try {
            channel(operation) { sftp ->
                progress(sftp, operation, update)
                operation.track(source.inputStream()).use { stream ->
                    sftp.put(object : InMemorySourceFile() {
                        override fun getName() = source.name
                        override fun getLength() = source.length()
                        override fun getInputStream() = stream
                    }, temp)
                }
                operation.check()
                operation.commit {
                    val existing = try { sftp.lstat(target) } catch (e: SFTPException) {
                        if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
                    }
                    if (existing == null) sftp.rename(temp, target)
                    else {
                        check(overwrite && existing.type != FileMode.Type.DIRECTORY) { "Destination already exists" }
                        if (sftp.sftpEngine.supportsServerExtension("posix-rename", "openssh.com") || sftp.version() >= 5) {
                            sftp.rename(temp, target, setOf(RenameFlags.OVERWRITE))
                        } else {
                            // SFTP v3 without atomic replacement: keep the original until rename succeeds.
                            val backup = "$temp.previous"
                            sftp.rename(target, backup)
                            try { sftp.rename(temp, target) }
                            catch (e: Exception) { runCatching { sftp.rename(backup, target) }; throw e }
                            sftp.rm(backup)
                        }
                    }
                }
            }
        } finally {
            runCatching { channel(SftpOperation()) { it.rm(temp) } }
        }
    }
    override fun close() {
        // Cancelled uploads finish removing their staging file before the last transport release.
        val release = synchronized(lifetime) { closing = true; active == 0 }
        if (release) lease.close()
    }
}

/** Publish only complete downloads. Cancellation removes the pending row, preserving the old file. */
internal class SftpDownloads(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun existing(name: String): android.net.Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val indexed = resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(sftpName(name), Environment.DIRECTORY_DOWNLOADS + "/"), null)?.use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }
        if (indexed != null) return indexed
        // Files copied through ADB or another file manager may not have a MediaStore row yet.
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sftpName(name))
        if (!file.exists()) return null
        check(file.isFile) { "Download already exists" }
        return android.net.Uri.fromFile(file)
    }
    fun download(session: SftpSession, entry: SftpEntry, overwrite: Boolean, operation: SftpOperation, update: (Long, Long) -> Unit) {
        val name = sftpName(entry.name)
        val old = existing(name)
        check(old == null || overwrite) { "Download already exists" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, ".farside-${UUID.randomUUID()}.partial")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { session.download(entry.path, it, operation, update) }
            operation.check()
            operation.commit {
                if (old != null) check(if (old.scheme == "file") File(checkNotNull(old.path)).delete()
                    else resolver.delete(old, null, null) == 1) { "Could not replace download" }
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null) == 1)
            }
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    fun downloadFolder(session: SftpSession, entry: SftpEntry, tree: android.net.Uri, operation: SftpOperation,
        update: (Long, Long) -> Unit, onStatus: (String) -> Unit) {
        require(entry.directory && entry.link == null && !entry.unavailable)
        val resolver = context.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        fun findChild(parentId: String, name: String): android.net.Uri? = resolver.query(
            android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == name) {
                    return@use android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                }
            }
            null
        }
        fun create(parentId: String, name: String, mime: String): android.net.Uri = checkNotNull(
            android.provider.DocumentsContract.createDocument(resolver,
                android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mime, name))
        check(findChild(rootId, sftpName(entry.name)) == null) { "Destination already exists" }
        val target = create(rootId, sftpName(entry.name), "vnd.android.document/directory")
        val targetId = android.provider.DocumentsContract.getDocumentId(target)
        val targetMime = resolver.getType(target)
        require(targetMime == "vnd.android.document/directory") { "Destination already exists" }
        data class RemoteFile(val entry: SftpEntry, val parentRelative: String)
        val directories = mutableListOf<String>()
        val files = mutableListOf<RemoteFile>()
        fun scan(remote: String, relative: String) {
            operation.check()
            onStatus(context.getString(R.string.sftp_scanning_folder, relative.ifEmpty { entry.name }))
            val children = session.list(remote, operation)
            children.forEach { child ->
                operation.check()
                if (child.unavailable || child.link != null) return@forEach
                val name = sftpName(child.name)
                if (child.directory) {
                    val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                    directories += childRelative
                    scan(child.path, childRelative)
                } else {
                    files += RemoteFile(child, relative)
                }
            }
        }
        scan(entry.path, "")
        val directoryIds = mutableMapOf("" to targetId)
        directories.forEachIndexed { index, relative ->
            operation.check()
            onStatus(context.getString(R.string.sftp_creating_folders, index + 1, directories.size))
            val parentRelative = relative.substringBeforeLast('/', "")
            val name = relative.substringAfterLast('/')
            val parentId = checkNotNull(directoryIds[parentRelative])
            check(findChild(parentId, name) == null) { "Destination already exists" }
            val dir = create(parentId, name, "vnd.android.document/directory")
            check(resolver.getType(dir) == "vnd.android.document/directory") { "Could not create destination folder" }
            directoryIds[relative] = android.provider.DocumentsContract.getDocumentId(dir)
        }
        val totalBytes = files.sumOf { it.entry.size.coerceAtLeast(0L) }
        var completedBytes = 0L
        files.forEachIndexed { index, file ->
            operation.check()
            onStatus(context.getString(R.string.sftp_downloading_file, index + 1, files.size, file.entry.name))
            val parentId = checkNotNull(directoryIds[file.parentRelative])
            check(findChild(parentId, sftpName(file.entry.name)) == null) { "Destination already exists" }
            val created = create(parentId, sftpName(file.entry.name), "application/octet-stream")
            var fileBytes = 0L
            try {
                checkNotNull(resolver.openOutputStream(created, "w")).use { output ->
                    session.download(file.entry.path, output, operation) { done, _ ->
                        fileBytes = done
                        update((completedBytes + done).coerceAtMost(totalBytes), totalBytes)
                    }
                }
            } catch (e: Exception) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, created) }; throw e }
            completedBytes += fileBytes.coerceAtMost(file.entry.size.coerceAtLeast(0L))
            update(completedBytes.coerceAtMost(totalBytes), totalBytes)
        }
    }
}
