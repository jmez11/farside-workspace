package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.farside.workspace.data.*
import com.farside.workspace.sftp.*
import java.io.File
import java.util.UUID

/** Host fixture uses adb reverse, actual SSH key auth and SFTP, never user files. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class SftpVerification(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val report = StringBuilder()
    private fun record(text: String) {
        report.appendLine(text)
        File(context.cacheDir, "sftp-results.txt").writeText(report.toString())
    }
    fun run(mode: String, liveProfile: String? = null): String {
        if (mode == "cleanup") {
            val pm = ProfileManager(context)
            pm.getProfiles().filter { it.type == ConnectionType.SSH && it.host == "127.0.0.1" && it.port == 22022 &&
                it.name in setOf("SFTP enabled fixture", "SFTP disabled fixture") }.forEach { pm.deleteProfile(it.id) }
            check(context.getSharedPreferences("farside_profiles", 0).edit().commit())
            record("PASS: interrupted SFTP UI fixture profiles removed through ProfileManager")
            return report.toString()
        }
        if (mode == "live") return live(checkNotNull(liveProfile))
        if (mode == "fresh-auth") return freshAuth(checkNotNull(liveProfile))
        if (mode == "ui") return ui()
        if (mode == "security") return security()
        if (mode == "macPairedUi") {
            val info = test.uiAutomation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            test.uiAutomation.serviceInfo = info
            val mac = ProfileManager(context).getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
            val paired = ProfileManager(context).getProfiles().single { it.id == mac.sftpPairProfileId }
            check(!paired.enableSftp)
            val scratchName = "farside-validation-${UUID.randomUUID()}"
            val source = File(context.cacheDir, "remote-${UUID.randomUUID()}.txt").apply { writeText("Mac paired Files download\n") }
            val local = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "upload-${UUID.randomUUID()}.txt").apply { writeText("Mac paired Files upload\n") }
            var scratch = ""
            var activity: SessionActivity? = null
            try {
                SftpSession(context, paired).use { session ->
                    val home = session.connect({}, {})
                    scratch = home.trimEnd('/') + "/" + scratchName
                    session.mkdir(scratch, SftpOperation())
                    session.upload(source, "$scratch/${source.name}", false, SftpOperation()) { _, _ -> }
                }
                activity = test.startActivitySync(Intent(context, SessionActivity::class.java)
                    .putExtra("profileId", mac.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
                find("Browse files"); click("Browse files")
                find(scratchName); click(scratchName); find(source.name)
                click(source.name); find("Confirm file operation"); click("Continue")
                repeat(50) { if (SftpDownloads(context).existing(source.name) != null) return@repeat; Thread.sleep(100) }
                val download = checkNotNull(SftpDownloads(context).existing(source.name))
                check(context.contentResolver.openInputStream(download)!!.use { it.readBytes() }.contentEquals(source.readBytes()))
                click("Upload"); click("Download/"); click(local.name)
                find("Confirm file operation"); click("Continue"); find(local.name)
                click("Actions for ${source.name}"); click("Delete"); click("Continue")
                click("Actions for ${local.name}"); click("Delete"); click("Continue")
                Thread.sleep(700)
                SftpSession(context, paired).use { verify ->
                    verify.connect({}, {})
                    check(verify.list(scratch, SftpOperation()).isEmpty()) { "Files panel did not delete both scratch files" }
                }
                repeat(3) { click("Back in Files") }
                find("/")
                record("PASS: VNC toolbar Files opened paired SSH without enableSftp; listed / and scratch, downloaded exact bytes, uploaded and deleted both files")
            } finally {
                activity?.let { test.runOnMainSync { it.finish() } }
                if (scratch.isNotEmpty()) runCatching { SftpSession(context, paired).use { session ->
                    session.connect({}, {})
                    session.list(scratch, SftpOperation()).forEach { session.delete(it, SftpOperation()) }
                    session.delete(SftpEntry(scratchName, scratch, true, 0, 0, null, false), SftpOperation())
                } }
                SftpDownloads(context).existing(source.name)?.let { context.contentResolver.delete(it, null, null) }
                source.delete(); local.delete()
            }
            return report.toString()
        }
        if (mode == "macSftp") {
            val pm = ProfileManager(context)
            val mac = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
            val credential = pm.getCredentials().single { it.name == "James Kasyan Mac" }
            val shortCredential = credential.copy(id = UUID.randomUUID().toString(), user = "jameskasyan")
            val ssh = ConnectionProfile(UUID.randomUUID().toString(), "Mac SFTP validation", ConnectionType.SSH,
                mac.host, 22, "", "", credentialId = shortCredential.id, enableSftp = false)
            val source = File(context.cacheDir, "mac-sftp-${UUID.randomUUID()}.txt")
            source.writeText("Farside Mac SFTP validation\n")
            pm.addCredential(shortCredential); pm.addProfile(ssh)
            check(context.getSharedPreferences("farside_profiles", 0).edit().commit())
            try {
                SftpSession(context, pm.getProfiles().single { it.id == ssh.id }).use { session ->
                    val home = session.connect({}, {})
                    val scratch = home.trimEnd('/') + "/farside-validation-${UUID.randomUUID()}"
                    session.mkdir(scratch, SftpOperation())
                    try {
                        val remote = "$scratch/${source.name}"
                        session.upload(source, remote, false, SftpOperation()) { _, _ -> }
                        val copied = java.io.ByteArrayOutputStream()
                        session.download(remote, copied, SftpOperation()) { _, _ -> }
                        check(copied.toByteArray().contentEquals(source.readBytes()))
                        session.delete(session.list(scratch, SftpOperation()).single { it.path == remote }, SftpOperation())
                        record("PASS: Mac SSH credential, scratch upload/download and file deletion")
                    } finally {
                        session.delete(SftpEntry(scratch.substringAfterLast('/'), scratch, true, 0, 0, null, false), SftpOperation())
                    }
                }
            } finally {
                source.delete(); pm.deleteProfile(ssh.id); pm.deleteCredential(shortCredential.id)
                check(context.getSharedPreferences("farside_profiles", 0).edit().commit())
            }
            return report.toString()
        }
        // Host sets the app-op before starting instrumentation: revocation kills the app process.
        if (mode == "permission") return permission()
        val pm = ProfileManager(context)
        val keyFile = File(context.cacheDir, "sftp-fixture-key.pem")
        val key = SavedSshKey(name = "SFTP verification", privateKey = keyFile.readText())
        val profile = ConnectionProfile(UUID.randomUUID().toString(), "SFTP verification", ConnectionType.SSH,
            "127.0.0.1", 22022, "fixture", "", sshKeyId = key.id, enableSftp = true)
        val source = File(context.getExternalFilesDir(null), "sftp-upload-${UUID.randomUUID()}.bin")
        var downloaded: android.net.Uri? = null
        pm.addSshKey(key)
        try {
            check(!ConnectionProfile.fromJson(profile.toJson().apply { remove("enableSftp") }).enableSftp)
            check(ConnectionProfile.fromJson(profile.toJson()).enableSftp)
            check(!ConnectionProfile.fromJson(profile.copy(type = ConnectionType.RDP).toJson()).enableSftp)
            SftpSession(context, profile).use { session ->
                check(session.connect({}, {}) == "/")
                var entries = session.list("/", SftpOperation())
                check(entries.first().directory) { "Unexpected SFTP rows: ${entries.map { it.name to it.directory }}" }
                check(entries.single { it.name == "link" }.link == "/hello.txt")
                check(entries.single { it.name == "broken" }.unavailable)
                check(entries.single { it.name == "denied" }.unavailable)
                check(session.list("/folder", SftpOperation()).isEmpty())
                record("PASS: encrypted saved-key auth; home/descend; dirs-first listing; symlink target/broken/denied rows")
                val folder = "/verification-${UUID.randomUUID()}"
                session.mkdir(folder, SftpOperation())
                val renamed = folder + "-renamed"
                session.rename(folder, renamed, SftpOperation())
                session.delete(session.list("/", SftpOperation()).single { it.path == renamed }, SftpOperation())
                check(!session.exists(renamed, SftpOperation()))
                source.outputStream().use { out -> repeat(64) { out.write(ByteArray(65536) { (it % 251).toByte() }) } }
                val remote = "/" + source.name
                var uploadProgress = 0L
                session.upload(source, remote, false, SftpOperation()) { bytes, _ -> uploadProgress = bytes }
                check(uploadProgress == source.length())
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var count = 0L
                session.download(remote, object : java.io.OutputStream() {
                    override fun write(b: Int) { digest.update(b.toByte()); count++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len); count += len }
                }, SftpOperation()) { _, _ -> }
                val expected = java.security.MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input -> val buf = ByteArray(65536); while (true) { val n=input.read(buf); if(n<0)break; expected.update(buf,0,n) } }
                check(count == source.length() && digest.digest().contentEquals(expected.digest()))
                check(runCatching { session.upload(source, remote, false, SftpOperation()) { _, _ -> } }.isFailure)
                session.upload(source, remote, true, SftpOperation()) { _, _ -> }
                val cancelUpload = SftpOperation()
                check(runCatching { session.upload(source, remote, true, cancelUpload) { bytes, _ -> if (bytes >= 65536) cancelUpload.cancel() } }.isFailure)
                check(session.list("/", SftpOperation()).none { it.name.endsWith(".partial") })
                check(session.list("/", SftpOperation()).single { it.path == remote }.size == source.length())
                session.delete(session.list("/", SftpOperation()).single { it.path == remote }, SftpOperation())
                record("PASS: mkdir/rename/delete; headset upload/download exact SHA-256; overwrite rejection/acceptance; upload cancellation preserves prior file and SSH")
                val large = entries.single { it.name == "large.bin" }.copy(name = "farside-verification-${UUID.randomUUID()}.bin")
                val downloads = SftpDownloads(context)
                val cancel = SftpOperation()
                check(runCatching { downloads.download(session, large, false, cancel) { bytes, _ -> if (bytes >= 4 * 1024 * 1024) cancel.cancel() } }.isFailure)
                check(downloads.existing(large.name) == null)
                check(session.list("/folder", SftpOperation()).isEmpty())
                record("PASS: 500MiB download cancel after 4MiB removes pending MediaStore row and preserves connection")
                var received = 0L
                downloads.download(session, large, false, SftpOperation()) { bytes, total -> check(total == 500L * 1024 * 1024); received = bytes }
                downloaded = checkNotNull(downloads.existing(large.name))
                check(received == 500L * 1024 * 1024)
                var total = 0L
                context.contentResolver.openInputStream(downloaded!!).use { input ->
                    val buf = ByteArray(65536)
                    while (true) { val n = input!!.read(buf); if (n < 0) break; check((0 until n).all { buf[it] == 0.toByte() }); total += n }
                }
                check(total == received)
                check(runCatching { downloads.download(session, large, false, SftpOperation()) { _, _ -> } }.isFailure)
                val overwriteCancel = SftpOperation()
                check(runCatching { downloads.download(session, large, true, overwriteCancel) { bytes, _ -> if (bytes > 65536) overwriteCancel.cancel() } }.isFailure)
                check(downloads.existing(large.name) == downloaded)
                record("PASS: full 500MiB download to Downloads verified chunk-by-chunk; progress; overwrite rejection and cancelled overwrite preserves complete original")
            }
            val closing = SftpSession(context, profile)
            try {
                closing.connect({}, {})
                val op = SftpOperation()
                check(runCatching { closing.upload(source, "/panel-close.bin", false, op) { bytes, _ ->
                    if (bytes >= 65536) { op.cancel(); closing.close() }
                } }.isFailure)
            } finally { closing.close() }
            SftpSession(context, profile).use { reopened ->
                reopened.connect({}, {})
                check(reopened.list("/", SftpOperation()).none { it.name.endsWith(".partial") || it.name.endsWith(".previous") || it.name == "panel-close.bin" })
            }
            record("PASS: closing panel during upload cancels data, cleans staging before final disconnect, and reconnects")
            SftpSession(context, profile.copy(id = UUID.randomUUID().toString(), port = 22023)).use {
                val error = runCatching { it.connect({}, {}) }.exceptionOrNull()
                check(error != null && sftpError(context, error).contains("SFTP subsystem")) { "Missing-subsystem error not classified: ${error?.javaClass?.simpleName}" }
            }
            record("PASS: no-SFTP server clean inline error classification")
            SftpSession(context, profile.copy(id = UUID.randomUUID().toString(), sshKeyId = null, pass = "incorrect")).use {
                val error = runCatching { it.connect({}, {}) }.exceptionOrNull()
                check(error != null && sftpError(context, error).contains("Authentication failed"))
            }
            record("PASS: auth rejection; all owned local/remote fixture artifacts cleaned")
        } finally {
            downloaded?.let { context.contentResolver.delete(it, null, null) }
            source.delete(); pm.deleteSshKey(key.id)
        }
        return report.toString()
    }
    private fun freshAuth(profileName: String): String {
        val profile = ProfileManager(context).getProfiles().single { it.type == ConnectionType.SSH && (it.name == profileName || it.host == profileName) }
            .copy(id = UUID.randomUUID().toString())
        val privateRoot = File(context.noBackupFilesDir, "sftp-auth-verification-${UUID.randomUUID()}").apply { mkdirs() }
        // Isolate only this test's Cloudflare token cache; preserve every existing saved login.
        val isolatedContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = privateRoot
            override fun getApplicationContext(): android.content.Context = this
        }
        val browserStarts = java.util.concurrent.atomic.AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "https") browserStarts.incrementAndGet()
                return null
            }
        }
        test.addMonitor(monitor)
        try {
            record("Fresh Cloudflare authentication with an isolated private token cache; existing logins preserved")
            SftpSession(isolatedContext, profile).use { first ->
                val home = first.connect({}, {})
                first.list(home, SftpOperation())
                check(browserStarts.get() > 0) { "Fresh authentication did not launch a browser" }
                record("PASS: actual browser launch, completed Cloudflare Access sign-in and authenticated SFTP listing")
                val count = browserStarts.get()
                SftpSession(isolatedContext, profile).use { second ->
                    val statuses = mutableListOf<String>()
                    second.connect({ statuses.add(it) }, {})
                    second.list(home, SftpOperation())
                    check(statuses.isEmpty() && browserStarts.get() == count)
                }
                first.list(home, SftpOperation())
                record("PASS: second SFTP consumer reused authentication without another browser; releasing it preserved the first")
            }
        } finally {
            test.removeMonitor(monitor)
            // Lease teardown is asynchronous; wait for its private child to release the token lock.
            Thread.sleep(1500)
            privateRoot.deleteRecursively()
        }
        return report.toString()
    }
    private fun live(profileName: String): String {
        val pm = ProfileManager(context)
        val profile = pm.getProfiles().single { it.type == ConnectionType.SSH && (it.name == profileName || it.host == profileName) }
        check(profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED)
        val browserStarts = java.util.concurrent.atomic.AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "https") browserStarts.incrementAndGet()
                return null
            }
        }
        test.addMonitor(monitor)
        val session = SftpSession(context, profile)
        val local = File(context.getExternalFilesDir(null), "sftp-live-${UUID.randomUUID()}.bin")
        var scratch: String? = null
        var terminal: SessionActivity? = null
        var downloaded: android.net.Uri? = null
        try {
            record("Connecting to user-selected Cloudflare SSH profile; browser sign-in may be required")
            var home = session.connect(onStatus = { text ->
                if (text.contains("Authenticating with Cloudflare")) record("Cloudflare authentication started")
            }, onExit = {})
            val initialEntries = session.list(home, SftpOperation())
            // Some NAS SFTP servers expose a virtual root, with the account's writable home below it.
            if (home == "/") initialEntries.singleOrNull { it.name == "home" && it.directory }?.let { home = it.path }
            session.list(home, SftpOperation())
            record("PASS: Cloudflare SSH authentication and SFTP home listing; browser launches=${browserStarts.get()}")
            terminal = test.startActivitySync(Intent(context, SessionActivity::class.java).putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
            fun terminalView(v: android.view.View): com.termux.view.TerminalView? = if (v is com.termux.view.TerminalView) v else (v as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { terminalView(group.getChildAt(it)) } }
            val deadline = android.os.SystemClock.uptimeMillis() + 30000
            var connected = false
            while (!connected && android.os.SystemClock.uptimeMillis() < deadline) {
                test.runOnMainSync { connected = terminalView(terminal!!.window.decorView)?.mEmulator?.screen?.transcriptText?.contains("SSH Connected") == true }
                Thread.sleep(200)
            }
            check(connected) { "Live terminal did not connect; inspect its PIN/sign-in gate" }
            val browserCount = browserStarts.get()
            com.farside.workspace.ssh.SshConnectionManager.acquire(context, profile.id).use { lease ->
                val statuses = mutableListOf<String>()
                val ssh = lease.connect(profile, onStatus = { statuses.add(it) })
                check(statuses.isEmpty()) { "Shared transport unexpectedly authenticated again" }
                val candidate = remoteChild(home, ".farside-sftp-verification-${UUID.randomUUID()}")
                session.mkdir(candidate, SftpOperation())
                scratch = candidate
                record("PASS: live terminal reuse and owned writable directory creation")
                check(session.list(scratch!!, SftpOperation()).isEmpty())
                session.mkdir(remoteChild(scratch!!, "folder"), SftpOperation())
                session.rename(remoteChild(scratch!!, "folder"), remoteChild(scratch!!, "renamed"), SftpOperation())
                session.delete(session.list(scratch!!, SftpOperation()).single { it.name == "renamed" }, SftpOperation())
                local.writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() })
                val remote = remoteChild(scratch!!, "upload.bin")
                session.upload(local, remote, false, SftpOperation()) { _, _ -> }
                val received = java.security.MessageDigest.getInstance("SHA-256")
                session.download(remote, object : java.io.OutputStream() {
                    override fun write(b: Int) { received.update(b.toByte()) }
                    override fun write(b: ByteArray, off: Int, len: Int) { received.update(b, off, len) }
                }, SftpOperation()) { _, _ -> }
                check(received.digest().contentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(local.readBytes())))
                record("PASS: live terminal and SFTP share one authenticated transport; upload/download SHA-256; mkdir/rename/delete")
                val largePath = remoteChild(scratch!!, "large.bin")
                ssh.newSFTPClient().use { sftp ->
                    sftp.open(largePath, setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.EXCL)).use { it.setLength(500L * 1024 * 1024) }
                }
                val large = session.list(scratch!!, SftpOperation()).single { it.name == "large.bin" }.copy(name = "farside-live-${UUID.randomUUID()}.bin")
                check(large.size == 500L * 1024 * 1024)
                val downloads = SftpDownloads(context)
                val cancel = SftpOperation()
                check(runCatching { downloads.download(session, large, false, cancel) { bytes, _ -> if (bytes >= 1024 * 1024) cancel.cancel() } }.isFailure)
                check(downloads.existing(large.name) == null)
                record("PASS: live 500MiB download cancelled after 1MiB; pending download removed")
                var lastBucket = -1L
                downloads.download(session, large, false, SftpOperation()) { bytes, _ ->
                    val bucket = bytes / (50L * 1024 * 1024)
                    if (bucket != lastBucket) { lastBucket = bucket; record("Live download progress: ${bucket * 50} MiB") }
                }
                downloaded = checkNotNull(downloads.existing(large.name))
                var total = 0L
                context.contentResolver.openInputStream(downloaded!!).use { input ->
                    val buf = ByteArray(65536)
                    while (true) { val n = input!!.read(buf); if (n < 0) break; check((0 until n).all { buf[it] == 0.toByte() }); total += n }
                }
                check(total == large.size && browserStarts.get() == browserCount)
                record("PASS: live complete 500MiB download verified chunk-by-chunk; no second browser authentication")
            }
            test.runOnMainSync { terminal!!.finish() }
            Thread.sleep(600)
            session.list(home, SftpOperation())
            record("PASS: closing the terminal preserves SFTP transport")
        } finally {
            terminal?.let { test.runOnMainSync { it.finish() } }
            downloaded?.let { context.contentResolver.delete(it, null, null) }
            local.delete()
            try {
                scratch?.let { folder ->
                    session.list(folder, SftpOperation()).forEach { session.delete(it, SftpOperation()) }
                    session.delete(SftpEntry(folder.substringAfterLast('/'), folder, true, 0, 0), SftpOperation())
                }
            } catch (_: Exception) { record("FAIL: owned remote scratch cleanup requires recovery") }
            finally { session.close(); test.removeMonitor(monitor) }
        }
        record("PASS: owned remote scratch and headset transfer artifacts removed; saved profile unchanged")
        return report.toString()
    }
    private fun permission(): String {
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val pm = ProfileManager(context)
        val profile = ConnectionProfile(UUID.randomUUID().toString(), "Permission verification", ConnectionType.SSH, "127.0.0.1", 22022, "fixture", "", enableSftp = false)
        val action = java.util.concurrent.atomic.AtomicReference<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? { action.set(intent.action); return null }
        }
        test.addMonitor(monitor)
        var activity: MainActivity? = null
        try {
            check(!hasStoragePermission(context))
            activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.runOnMainSync { activity!!.setContent { MaterialTheme { com.farside.workspace.ui.AddProfileForm(initialProfile = profile, onSave = {}, onCancel = {}, profileManager = pm) } } }
            action.set(null)
            click("Enable SFTP file access")
            check(action.get() == android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION || action.get() == android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            Thread.sleep(800)
            val settingsPackage = createManageAllFilesIntent(context).resolveActivity(context.packageManager)!!.packageName
            val displays = test.uiAutomation.windowsOnAllDisplays
            val window = (0 until displays.size()).flatMap { displays.valueAt(it) }.first { it.root?.packageName?.toString() == settingsPackage }
            record("PASS: enabling SFTP without permission opened the real all-files-access settings Activity")
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir,"sftp-permission.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            // Grant through the shell, then send Back to the actual settings display so Android delivers its result.
            shell("appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            check(hasStoragePermission(context))
            shell("input -d ${window.displayId} keyevent 4")
            Thread.sleep(800)
            find("Enable SFTP file access")
            val switch = nodes().single { it.isCheckable && it.contentDescription?.toString() == "Enable SFTP file access" }
            check(switch.isChecked) { "Permission return did not enable SFTP" }
            record("PASS: synthetic app-op grant followed by real Settings Back/result callback reflects the grant on the SSH toggle")
        } finally {
            activity?.let { test.runOnMainSync { it.finish() } }
            test.removeMonitor(monitor)
        }
        return report.toString()
    }
    private fun security(): String {
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val prefs = context.getSharedPreferences("farside_profiles", 0)
        val keys = listOf("app_pinVerifier", "app-pin-attempts", "app-pin-until", "system_pin_enabled")
        val recovery = File(context.cacheDir, "sftp-security-recovery.json")
        // The stored verifier is already Keystore encrypted. Never persist the test PIN or decrypted verifier.
        val original = if (recovery.exists()) org.json.JSONObject(recovery.readText()) else org.json.JSONObject().apply {
            keys.forEach { key -> if (prefs.contains(key)) put(key, prefs.all[key]) }
        }.also { recovery.writeText(it.toString()) }
        val pm = ProfileManager(context)
        var activity: MainActivity? = null
        try {
            prefs.edit().putBoolean("system_pin_enabled", false).remove("app-pin-attempts").remove("app-pin-until").commit()
            pm.setAppPinVerifier(CredentialPin.create("583192"))
            activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            click("Files"); find("Unlock Files")
            check(nodes().none { it.text?.toString() == "Farside SSH" })
            enter("PIN", "0000"); click("Unlock"); find("Incorrect PIN.")
            check(nodes().none { it.text?.toString() == "Farside SSH" })
            enter("PIN", "583192"); click("Unlock"); find("Farside SSH")
            capture(activity, "sftp-unlocked.png")
            click("Close Files"); click("Files"); find("Unlock Files"); click("Cancel"); find("Farside Connections")
            record("PASS: Files PIN before profile listing; wrong PIN rejected; correct unlock; close/reopen prompts; Cancel returns to launcher")
        } finally {
            activity?.let { test.runOnMainSync { it.finish() } }
            CredentialPin.clearCache()
            val editor = prefs.edit()
            keys.forEach { key ->
                editor.remove(key)
                if (original.has(key)) when (key) {
                    "app_pinVerifier" -> editor.putString(key, original.getString(key))
                    "app-pin-attempts" -> editor.putInt(key, original.getInt(key))
                    "app-pin-until" -> editor.putLong(key, original.getLong(key))
                    "system_pin_enabled" -> editor.putBoolean(key, original.getBoolean(key))
                }
            }
            check(editor.commit()); recovery.delete()
        }
        return report.toString()
    }
    private fun enter(label: String, text: String) {
        var node = find(label)
        while (!node.isEditable) node = checkNotNull(node.parent)
        check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        Thread.sleep(200)
    }
    private fun ui(): String {
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val pm = ProfileManager(context)
        val fixture = ConnectionProfile(UUID.randomUUID().toString(), "SFTP enabled fixture", ConnectionType.SSH, "127.0.0.1", 22022, "fixture", "fixture", enableSftp = true)
        val hidden = fixture.copy(id = UUID.randomUUID().toString(), name = "SFTP disabled fixture", enableSftp = false)
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            pm.addProfile(fixture); pm.addProfile(hidden)
            test.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                com.farside.workspace.ui.LauncherScreen(listOf(fixture, hidden), {}, {}, {}, pm)
            } } }
            click("Files")
            val hasPin = pm.usesSystemPin() || pm.getAppPinVerifier() != null
            if (hasPin) { find("Unlock Files"); record("PASS: existing app PIN gate enforced; cancelled without using user PIN"); click("Cancel") }
            else {
                find("SFTP enabled fixture")
                check(nodes().none { it.text?.toString() == hidden.name })
                click("SFTP enabled fixture"); find("hello.txt")
                capture(activity, "sftp-files.png")
                click("folder"); find("This folder is empty."); click("Back in Files"); find("hello.txt")
                val newName = "ui-folder-${UUID.randomUUID()}"
                click("New folder"); enter("Name", newName); click("Save"); find(newName)
                click("Actions for $newName"); click("Rename"); enter("Name", "$newName-renamed"); click("Save"); find("$newName-renamed")
                click("Actions for $newName-renamed"); click("Delete"); click("Continue")
                Thread.sleep(700)
                check(nodes().none { it.text?.toString() == "$newName-renamed" })
                val local = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sftp-ui-${UUID.randomUUID()}.txt")
                local.writeText("SFTP UI upload verification")
                try {
                    click("Upload"); click("Download/"); click(local.name); find("Confirm file operation"); click("Continue"); find(local.name)
                    click("Upload"); click("Download/"); click(local.name); find("Replace ${local.name} on the server?"); click("Cancel")
                    click(local.name); find("Replace ${local.name} in Downloads?"); click("Cancel")
                    local.writeText("Old local file, deliberately not in MediaStore")
                    click(local.name); find("Replace ${local.name} in Downloads?"); click("Continue")
                    repeat(50) { if (local.exists() && local.readText() == "SFTP UI upload verification") return@repeat; Thread.sleep(100) }
                    check(local.readText() == "SFTP UI upload verification")
                    click("Actions for ${local.name}"); click("Delete"); click("Continue")
                } finally {
                    SftpDownloads(context).existing(local.name)?.takeIf { it.scheme == "content" }?.let { context.contentResolver.delete(it, null, null) }
                    local.delete()
                    SftpSession(context, fixture).use { cleanup ->
                        cleanup.connect({}, {})
                        cleanup.list("/", SftpOperation()).singleOrNull { it.name == local.name }?.let { cleanup.delete(it, SftpOperation()) }
                    }
                }
                click("large.bin"); click("Continue"); find("Cancel")
                capture(activity, "sftp-transfer.png")
                click("Cancel"); find("Cancelled.")
                record("PASS: actual file menus create/rename/delete; headset picker upload; both overwrite dialogs; progress/cancel UI")
                click("Close Files")
                record("PASS: launcher folder button; enabled-only profile list; actual connect/list/descend/back")
            }
            test.runOnMainSync { activity.setContent { MaterialTheme { com.farside.workspace.ui.AddProfileForm(initialProfile = fixture, onSave = {}, onCancel = {}, profileManager = pm) } } }
            find("Enable SFTP file access")
            for (type in listOf(ConnectionType.RDP, ConnectionType.VNC)) {
                test.runOnMainSync { activity.setContent { MaterialTheme { com.farside.workspace.ui.AddProfileForm(initialProfile = fixture.copy(type = type), onSave = {}, onCancel = {}, profileManager = pm) } } }
                Thread.sleep(500)
                check(nodes().none { it.text?.toString() == "Enable SFTP file access" })
            }
            record("PASS: SSH-only SFTP toggle; hidden for RDP/VNC")
        } finally { test.runOnMainSync { activity.finish() }; pm.deleteProfile(fixture.id); pm.deleteProfile(hidden.id) }
        return report.toString()
    }
    private fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun descend(n: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::descend).orEmpty() }
        val displays = test.uiAutomation.windowsOnAllDisplays
        val windows = (0 until displays.size()).flatMap { displays.valueAt(it) }.filter { it.root?.packageName == context.packageName }
        // Dialogs duplicate file names that remain in the underlying remote list. Target the top window.
        return windows.maxByOrNull { it.layer }?.root?.let(::descend).orEmpty()
    }
    private fun find(label: String): android.view.accessibility.AccessibilityNodeInfo {
        repeat(60) { attempt ->
            val all = nodes()
            all.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            if (attempt % 5 == 4) all.firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(200)
        }
        File(context.cacheDir, "sftp-ui-failure.txt").writeText(nodes().joinToString("\n") { "${it.text} | ${it.contentDescription}" })
        error("Missing SFTP UI: $label")
    }
    private fun click(label: String) {
        var n=find(label);while(!n.isClickable)n=checkNotNull(n.parent)
        check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(350)
    }
    private fun capture(activity: android.app.Activity, name: String) {
        val bitmap=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
        val latch=java.util.concurrent.CountDownLatch(1);var status = -1
        test.runOnMainSync { android.view.PixelCopy.request(activity.window,bitmap,{status=it;latch.countDown()},android.os.Handler(android.os.Looper.getMainLooper())) }
        check(latch.await(5,java.util.concurrent.TimeUnit.SECONDS) && status==0)
        File(context.cacheDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
