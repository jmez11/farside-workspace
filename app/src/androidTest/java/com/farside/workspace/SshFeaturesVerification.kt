package com.farside.workspace

import android.app.Instrumentation
import androidx.activity.compose.setContent
import android.content.Intent
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.*
import com.termux.terminal.SshTerminalSession
import com.termux.view.TerminalView
import net.schmizz.sshj.SSHClient
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import java.net.*
import java.io.DataInputStream
import java.security.KeyPairGenerator
import kotlin.concurrent.thread

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class SshFeaturesVerification(private val test: Instrumentation, private val imeOnly: Boolean = false, private val keyAuth: Boolean = false) {
    private var fixtureKeyId: String? = null
    private val shellBytes = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val evidence = java.io.File(test.targetContext.cacheDir, "ssh-features-results.txt")
    private fun record(message: String) { evidence.appendText("$message\n"); android.util.Log.i("SshFeatures", message) }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return; Thread.sleep(100) }
        error(message)
    }
    private fun freePort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun capture(activity: android.app.Activity, name: String) {
        fun dialog(v: View): android.view.Window? = (v as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: (v as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { dialog(group.getChildAt(it)) } }
        lateinit var window: android.view.Window
        lateinit var bitmap: android.graphics.Bitmap
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = -1
        main {
            window = android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull { dialog(it) } ?: activity.window
            bitmap = android.graphics.Bitmap.createBitmap(window.decorView.width, window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
        java.io.File(test.targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    fun run() {
        evidence.writeText("")
        SshTerminalSession.ensureBouncyCastleProvider()
        // Android has no Java user.home; the fixture server's default key-path resolver requires one.
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { test.targetContext.cacheDir.toPath() }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pm = ProfileManager(test.targetContext)
        if (keyAuth) {
            val pem = "-----BEGIN PRIVATE KEY-----\n" + android.util.Base64.encodeToString(pair.private.encoded, android.util.Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END PRIVATE KEY-----\n"
            val key = SavedSshKey(name = "Transport verification", privateKey = pem)
            fixtureKeyId = key.id
            pm.addSshKey(key)
        }
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = org.apache.sshd.common.keyprovider.KeyPairProvider { listOf(pair) }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pass, _ -> user == "fixture" && pass == "fixture" }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ -> user == "fixture" && key.encoded.contentEquals(pair.public.encoded) }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            shellFactory = org.apache.sshd.server.shell.ShellFactory {
                object : org.apache.sshd.server.command.Command {
                    private lateinit var input: java.io.InputStream
                    private lateinit var output: java.io.OutputStream
                    private lateinit var callback: org.apache.sshd.server.ExitCallback
                    override fun setInputStream(stream: java.io.InputStream) { input = stream }
                    override fun setOutputStream(stream: java.io.OutputStream) { output = stream }
                    override fun setErrorStream(stream: java.io.OutputStream) { }
                    override fun setExitCallback(exit: org.apache.sshd.server.ExitCallback) { callback = exit }
                    override fun start(channel: org.apache.sshd.server.channel.ChannelSession, environment: org.apache.sshd.server.Environment) {
                        thread(isDaemon = true) {
                            try {
                                output.write("\r\nFixture SSH shell ready\r\n".toByteArray()); output.flush()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    for (index in 0 until count) shellBytes.add(buffer[index].toInt() and 255)
                                    output.write(buffer, 0, count); output.flush()
                                }
                            } catch (_: Exception) { } finally { callback.onExit(0) }
                        }
                    }
                    override fun destroy(channel: org.apache.sshd.server.channel.ChannelSession) { runCatching { input.close() } }
                }
            }
        }
        server.start()
        val echo = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                try {
                    val socket = echo.accept()
                    thread(isDaemon = true) { socket.use { it.getInputStream().copyTo(it.getOutputStream()) } }
                } catch (_: Exception) { }
            }
        }
        val client = SSHClient()
        client.addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
        try {
            client.connect("127.0.0.1", server.port); client.authPassword("fixture", "fixture")
            forwarding(client, echo.localPort)
            terminalUi(server.port, echo.localPort)
        } finally { client.close(); echo.close(); server.stop(true); fixtureKeyId?.let(pm::deleteSshKey) }
        record("PASS: all SSH feature checks complete; fixture SSH server and profiles removed")
    }
    private fun forwarding(client: SSHClient, destination: Int) {
        val statuses = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
        val manager = SshForwarding { statuses.set(it) }
        val local = SshForward(sourcePort = freePort(), destinationHost = "127.0.0.1", destinationPort = destination, family = ForwardFamily.IPV4)
        val remote = local.copy(id = "remote", kind = ForwardKind.REMOTE, sourcePort = freePort())
        val dynamic = local.copy(id = "dynamic", kind = ForwardKind.DYNAMIC, sourcePort = freePort())
        val ipv6 = local.copy(id = "ipv6", sourcePort = freePort(), family = ForwardFamily.IPV6)
        val all = listOf(local, remote, dynamic, ipv6)
        try {
            manager.update(all); manager.attach(client)
            waitFor("Forward setup failed: ${statuses.get()}") { all.all { statuses.get()[it.id] == "Listening" } }
            fun echoThrough(rule: SshForward, socksType: Int = 0) {
                record("Checking ${rule.kind} ${rule.family} :${rule.sourcePort} SOCKS=$socksType")
                Socket(if (rule.family == ForwardFamily.IPV6) "::1" else "127.0.0.1", rule.sourcePort).use { socket ->
                    socket.soTimeout = 5000
                    val out = socket.getOutputStream(); val input = DataInputStream(socket.getInputStream())
                    if (socksType != 0) {
                        out.write(byteArrayOf(5, 1, 0)); check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
                        val address = when (socksType) {
                            1 -> byteArrayOf(127, 0, 0, 1)
                            else -> "localhost".toByteArray().let { byteArrayOf(it.size.toByte()) + it }
                        }
                        out.write(byteArrayOf(5, 1, 0, socksType.toByte()) + address + byteArrayOf((destination shr 8).toByte(), destination.toByte()))
                        val response = ByteArray(10).also { input.readFully(it) }; check(response[1].toInt() == 0)
                    }
                    val payload = ("Quest SSH tunnel ✓ $socksType\n".repeat(400)).toByteArray()
                    out.write(payload)
                    val received = ByteArray(payload.size).also { input.readFully(it) }
                    check(received.contentEquals(payload)) { "Tunnel corrupted traffic" }
                }
            }
            echoThrough(local); echoThrough(remote); echoThrough(dynamic, 1); echoThrough(dynamic, 3); echoThrough(ipv6)
            record("PASS: actual encrypted SSH traffic through local, remote, SOCKS5 IPv4/domain, and IPv6 listeners")
            val edited = local.copy(sourcePort = freePort())
            manager.update(listOf(edited, remote.copy(enabled = false)))
            waitFor("Live edit failed") { statuses.get()[local.id] == "Listening" && statuses.get()[remote.id] == "Disabled" && statuses.get().size == 2 }
            echoThrough(edited)
            listOf(local, remote, dynamic, ipv6).forEach { rule ->
                check(runCatching { Socket().use { it.connect(InetSocketAddress(if (rule.family == ForwardFamily.IPV6) "::1" else "127.0.0.1", rule.sourcePort), 500) } }.isFailure) { "Removed listener survived" }
            }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
                val bad = local.copy(id = "busy", sourcePort = occupied.localPort)
                manager.update(listOf(edited, bad))
                waitFor("Bind failure not surfaced") { statuses.get()[bad.id]?.startsWith("Failed:") == true }
                echoThrough(edited)
            }
            check(runCatching { SshForward.validateList(listOf(local, local.copy(id = "duplicate"))) }.isFailure)
            check(runCatching { local.copy(sourcePort = 0).validate() }.isFailure)
            record("PASS: live edit/disable/remove, released listeners, bind errors, unrelated-tunnel isolation, invalid/duplicate rejection")
        } finally { manager.close(); manager.close() }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::visit).orEmpty() }
        val windows = test.uiAutomation.windowsOnAllDisplays
        return (0 until windows.size()).flatMap { windows.valueAt(it).flatMap { w -> w.root?.let(::visit).orEmpty() } }
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(40) {
            nodes().firstOrNull { it.text?.toString()?.contains(label) == true || it.contentDescription?.toString()?.contains(label) == true || it.hintText?.toString()?.contains(label) == true }?.let { return it }
            Thread.sleep(100)
        }
        java.io.File(test.targetContext.cacheDir, "ssh-features-ui.txt").writeText(nodes().joinToString("\n") { "text=${it.text} description=${it.contentDescription} hint=${it.hintText} editable=${it.isEditable}" })
        error("Missing UI: $label")
    }
    private fun click(label: String) {
        var n = find(label)
        while (!n.isClickable && n.parent != null) n = n.parent
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(250)
    }
    private fun setText(label: String, text: String) {
        var n = find(label)
        while (!n.isEditable && n.parent != null) n = n.parent
        check(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        Thread.sleep(200)
    }
    private fun terminalUi(serverPort: Int, echoPort: Int) {
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val pm = ProfileManager(test.targetContext)
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        val clipboard = test.targetContext.getSystemService(android.content.ClipboardManager::class.java)
        val originalClip = clipboard.primaryClip
        val initialForward = SshForward(sourcePort = freePort(), destinationHost = "127.0.0.1", destinationPort = echoPort, family = ForwardFamily.IPV4)
        val profile = ConnectionProfile("ssh-features-fixture", "SSH features fixture", ConnectionType.SSH, "127.0.0.1", serverPort, "fixture", if (keyAuth) "" else "fixture", sshKeyId = fixtureKeyId, sshForwards = listOf(initialForward), terminalFontSize = 18, terminalOpacity = 0.7f, terminalScrollback = 500)
        pm.addProfile(profile)
        val intent = Intent(test.targetContext, SessionActivity::class.java).putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The native WindowSession captures permissions at creation, as in toolbarOnly.
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND", "android.permission.INTERNAL_SYSTEM_WINDOW")
        var activity = try { test.startActivitySync(intent) as SessionActivity }
        finally { test.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND") }
        fun terminal(v: View): TerminalView? = if (v is TerminalView) v else (v as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { terminal(group.getChildAt(it)) } }
        try {
            Thread.sleep(1200)
            click("Keep toolbar visible")
            val view = checkNotNull(terminal(activity.window.decorView))
            waitFor("Saved forwarding did not start with SSH login") { runCatching { Socket("127.0.0.1", initialForward.sourcePort).use { it.soTimeout = 1000; it.getOutputStream().write(65); it.getInputStream().read() == 65 } }.getOrDefault(false) }
            check(activity.cardConnected && activity.sshCaptureView === view)
            val cardLauncher = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                val cardCaptured = java.util.concurrent.CountDownLatch(1)
                var cardSaved = false
                main { SessionCardActions.capture(profile.id) { cardSaved = it; cardCaptured.countDown() } }
                check(cardCaptured.await(10, java.util.concurrent.TimeUnit.SECONDS) && cardSaved) { "Connected SSH terminal card capture failed" }
                val cardImage = java.io.File(test.targetContext.filesDir, "card-backgrounds/${pm.getProfiles().single { it.id == profile.id }.cardBackground}")
                check(cardImage.exists() && cardImage.length() > 1000L)
                cardImage.copyTo(java.io.File(test.targetContext.cacheDir, "ssh-card-capture.jpg"), overwrite = true)
            } finally { main { cardLauncher.finish() } }
            check(!activity.isDestroyed && activity.cardConnected)
            record("PASS: connected Termux view captured to card JPEG")
            record("PASS: saved forwarding starts automatically in actual SshTerminalSession with fixture shell")
            com.farside.workspace.ssh.SshConnectionManager.acquire(test.targetContext, profile.id).use { first ->
                val shared = first.connect(profile)
                com.farside.workspace.ssh.SshConnectionManager.acquire(test.targetContext, profile.id).use { second ->
                    check(second.connect(profile) === shared)
                }
                check(shared.isConnected && shared.isAuthenticated)
            }
            record("PASS: ${if (keyAuth) "saved-key" else "password"} terminal transport shares SSH identity; releasing other consumers preserves shell")
            if (imeOnly) {
                verifyNativeIme(activity, view)
                return
            }
            click("Zoom"); find("18sp"); click("Zoom")
            click("Transparency"); find("70%"); click("Transparency")
            // The session toolbar's Terminal settings button was retired; terminal settings are edited in the profile form (checked below).
            pm.updateProfile(pm.getProfiles().single { it.id == profile.id }.copy(terminalScrollback = 250))
            check(pm.getProfiles().single { it.id == profile.id }.terminalScrollback == 250)
            click("Port forwarding"); click("Remove tunnel"); click("Add tunnel")
            setText("Source port", "18080"); setText("Destination host", "example.test"); setText("Destination port", "443")
            click("Save tunnel"); find("Quest :18080 → example.test:443")
            click("Edit tunnel"); setText("Source port", "18081"); click("Save tunnel")
            find("Quest :18081 → example.test:443")
            capture(activity, "ssh-forwarding-dialog.png")
            val saved = pm.getProfiles().single { it.id == profile.id }
            check(saved.sshForwards.single().sourcePort == 18081)
            check(ConnectionProfile.fromJson(saved.toJson()) == saved)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            main {
                val canvas = android.graphics.Canvas(bitmap); view.draw(canvas)
            }
            java.io.File(test.targetContext.cacheDir, "ssh-terminal-features.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            click("Remove tunnel"); click("Done")
            check(pm.getProfiles().single { it.id == profile.id }.sshForwards.isEmpty())
            record("PASS: real Compose add/edit/remove dialog, font/opacity restoration, scrollback save, forwarding persistence/JSON roundtrip")
            main {
                val emulator = view.mEmulator
                emulator.setScrollbackRows(250)
                val text = "\r\n" + (0..599).joinToString("\r\n") { "history line $it" } + "\r\nDRAGCOPYTEST"
                val bytes = text.toByteArray(); emulator.append(bytes, bytes.size); view.onScreenUpdated()
                check(emulator.screen.activeTranscriptRows == 250)
                emulator.setScrollbackRows(1000)
                check(emulator.screen.activeTranscriptRows == 250)
                emulator.setScrollbackRows(100)
                check(emulator.screen.activeTranscriptRows == 100)
                check(emulator.screen.transcriptText.contains("DRAGCOPYTEST"))
                val before = emulator.cursorRow
                emulator.append("\u001b[?1049h".toByteArray(), 8)
                emulator.setScrollbackRows(300)
                emulator.append("\u001b[?1049l".toByteArray(), 8)
                check(emulator.cursorRow == before)
                view.onScreenUpdated()
                check(view.isHistoryScrollbarEnabled)
                val y = (emulator.cursorRow + 0.5f) * view.mRenderer.fontLineSpacing
                val start = android.os.SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                    val x = (if (i == 0) 0.2f else 6.2f) * view.mRenderer.fontWidth
                    val event = MotionEvent.obtain(start, start + i * 50, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    view.dispatchTouchEvent(event); event.recycle()
                }
                check(view.selectedText == "DRAGCOP") { "Wrong drag selection: ${view.selectedText}" }
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "DRAGCOP") { "Drag did not copy clipboard" }
                val controller = view.javaClass.getDeclaredField("mTextSelectionCursorController")
                    .apply { isAccessible = true }.get(view) as com.termux.view.textselection.TextSelectionCursorController
                val menu = checkNotNull(controller.actionMode).menu
                check(menu.findItem(controller.ACTION_COPY) != null && menu.findItem(controller.ACTION_PASTE) == null) { "Selection must offer only Copy" }
                check(menu.findItem(controller.ACTION_MORE) == null) { "SSH selection still offers More" }
            }
            record("PASS: scrollback grow/shrink preserves screen/cursor including alternate screen, right scrollbar, synthetic pointer drag copies exact selection")
            capture(activity, "ssh-terminal-features.png")
            fun tilt(value: Float): MotionEvent {
                val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_Y, value) }
                val now = android.os.SystemClock.uptimeMillis()
                return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, arrayOf(MotionEvent.PointerProperties().apply { id = 0 }), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
            }
            var rows = 0
            lateinit var controller: SshControllerScroll
            main { controller = SshControllerScroll { rows += it }; tilt(-0.3f).useEvent { controller.onMotion(it) } }
            Thread.sleep(1000)
            var gentle = 0
            main { gentle = -rows; controller.stop(); rows = 0; tilt(-1f).useEvent { controller.onMotion(it) } }
            Thread.sleep(1000)
            main {
                check(gentle in 1..10 && -rows > gentle * 5) { "Scroll curve: $gentle / $rows" }
                tilt(0f).useEvent { controller.onMotion(it) }; view.scrollHistory(-20); check(view.topRow < 0)
            }
            var stopped = 0; main { stopped = rows }; Thread.sleep(300); main { check(rows == stopped); controller.stop() }
            record("PASS: synthetic joystick slow/fast curve, neutral stop, local history movement; physical Horizon focus/joystick remains manual")
            fun motion(action: Int, source: Int, axis: Int, value: Float): MotionEvent {
                val now = android.os.SystemClock.uptimeMillis()
                return MotionEvent.obtain(now, now, action, 1, arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
                    arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(axis, value) }), 0, 0, 1f, 1f, 0, 0, source, 0)
            }
            main {
                view.scrollHistory(50000)
                // Exercise the actual Activity input route, including wheel input while Compose owns focus.
                repeat(4) { motion(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, MotionEvent.AXIS_VSCROLL, 0.1f).useEvent {
                    check(activity.dispatchGenericMotionEvent(it))
                } }
                check(view.topRow == -1) { "Fractional Horizon wheel input lost: ${view.topRow}" }
                motion(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL, 2f).useEvent { check(activity.dispatchGenericMotionEvent(it)) }
                check(view.topRow == -7)
                motion(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_JOYSTICK, MotionEvent.AXIS_RY, -1f).useEvent { check(activity.dispatchGenericMotionEvent(it)) }
            }
            Thread.sleep(600)
            main {
                check(view.topRow < -20) { "Quest right-stick RY did not scroll history" }
                motion(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_JOYSTICK, MotionEvent.AXIS_RY, 0f).useEvent { activity.dispatchGenericMotionEvent(it) }
                activity.onWindowFocusChanged(false)
                val x = view.width - 2f
                val now = android.os.SystemClock.uptimeMillis()
                for ((action, y) in listOf(MotionEvent.ACTION_DOWN to 2f, MotionEvent.ACTION_MOVE to 0f, MotionEvent.ACTION_UP to 0f)) {
                    MotionEvent.obtain(now, now, action, x, y, 0).useEvent { view.dispatchTouchEvent(it) }
                }
                check(view.topRow == -view.mEmulator.screen.activeTranscriptRows) { "Scrollbar cannot reach oldest history" }
                check(view.selectedText == "DRAGCOP") { "Scrollbar drag incorrectly selected text" }
            }
            capture(activity, "ssh-scrollback-oldest.png")
            main {
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    MotionEvent.obtain(now, now, action, view.width - 2f, view.height.toFloat(), 0).useEvent { view.dispatchTouchEvent(it) }
                }
                check(view.topRow == 0 && !view.mEmulator.isAutoScrollDisabled) { "Scrollbar cannot return to live output" }
                check(!view.isFocusable && !view.isFocusableInTouchMode) { "Terminal can steal the Compose InputConnection" }
            }
            record("PASS: Activity-routed fractional Horizon wheel/rotary input, Quest right-stick RY, scrollbar drag oldest/live, no clipboard selection or native focus theft")
            verifyPointerPaste(view, clipboard)
            verifyNativeIme(activity, view)
            main { activity.setZoom?.invoke(1.5f) }
            waitFor("Font size did not save: ${pm.getProfiles().single { it.id == profile.id }.terminalFontSize}") {
                pm.getProfiles().single { it.id == profile.id }.terminalFontSize == 21
            }
            record("PASS: floating font-size adjustment persists to connection")
            click("Transparency")
            val slider = nodes().first { it.rangeInfo?.current?.let { value -> kotlin.math.abs(value - 0.7f) < 0.01f } == true }
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0.45f) }))
            waitFor("Opacity did not save") { kotlin.math.abs(pm.getProfiles().single { it.id == profile.id }.terminalOpacity - 0.45f) < 0.01f }
            main { activity.finish() }; Thread.sleep(600)
            activity = test.startActivitySync(intent) as SessionActivity
            Thread.sleep(1000); click("Keep toolbar visible")
            click("Zoom"); find("21sp"); click("Zoom")
            click("Transparency"); find("45%"); click("Transparency")
            check(pm.getProfiles().single { it.id == profile.id }.terminalScrollback == 500)
            record("PASS: changed opacity/font persist and restore on session reopening; session saves leave profile scrollback untouched")
            val current = pm.getProfiles().single { it.id == profile.id }
            val launcher = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var formResult: ConnectionProfile? = null
            try {
                main {
                    launcher.setContent {
                        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                            com.farside.workspace.ui.AddProfileForm(initialProfile = current,
                                onSave = { formResult = it }, onCancel = { }, profileManager = pm)
                        }
                    }
                }
                Thread.sleep(600)
                val control = "Port forwarding · 0 saved"
                var button: AccessibilityNodeInfo? = null
                repeat(10) {
                    button = nodes().firstOrNull { it.text?.toString() == control }
                    if (button == null) nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    Thread.sleep(150)
                }
                checkNotNull(button) { "Profile forwarding submenu missing" }
                click(control); click("Add tunnel")
                setText("Source port", "19090"); click("Dynamic SOCKS")
                capture(launcher, "ssh-forwarding-editor.png")
                click("Save tunnel"); click("Done"); click("SAVE")
                waitFor("Profile form did not save") { formResult != null }
                check(formResult!!.sshForwards.single().kind == ForwardKind.DYNAMIC)
                check(formResult!!.terminalScrollback == 500 && formResult!!.terminalFontSize == 21 && kotlin.math.abs(formResult!!.terminalOpacity - 0.45f) < 0.01f)
                record("PASS: profile setup submenu creates saved SOCKS rule and preserves session terminal preferences")
            } finally { main { launcher.finish() } }
        } finally {
            main { activity.finish() }; Thread.sleep(500)
            pm.deleteProfile(profile.id)
            test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit()
            if (originalClip != null) clipboard.setPrimaryClip(originalClip) else clipboard.clearPrimaryClip()
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }
    private fun verifyPointerPaste(view: TerminalView, clipboard: android.content.ClipboardManager) {
        fun pasteMode(): android.view.ActionMode? = view.javaClass.getDeclaredField("mPasteActionMode")
            .apply { isAccessible = true }.get(view) as? android.view.ActionMode
        fun event(action: Int, buttons: Int = 0): MotionEvent {
            val now = android.os.SystemClock.uptimeMillis()
            return MotionEvent.obtain(now, now, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
                arrayOf(MotionEvent.PointerCoords().apply { x = 80f; y = 40f }),
                0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        }
        main {
            // Context clicks while selecting must not expose Paste or lose the selection.
            check(view.isSelectingText)
            event(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY).useEvent { view.dispatchTouchEvent(it) }
            check(pasteMode() == null && view.selectedText == "DRAGCOP")
            view.stopTextSelectionMode()
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "pointer-paste"))
            shellBytes.clear()
            event(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY).useEvent { view.dispatchTouchEvent(it) }
            val mode = checkNotNull(pasteMode())
            check(mode.menu.size() == 1 && mode.menu.getItem(0).title.toString() == "Paste")
            check(mode.menu.performIdentifierAction(mode.menu.getItem(0).itemId, 0))
        }
        waitFor("Context paste did not reach SSH") { shellBytes.size >= "pointer-paste".length }
        check(shellBytes.toList() == "pointer-paste".map { it.code })
        main {
            event(MotionEvent.ACTION_DOWN).useEvent { view.dispatchTouchEvent(it) }
        }
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 250)
        main {
            check(pasteMode() != null && !view.isSelectingText) { "Pointer hold did not offer Paste" }
            event(MotionEvent.ACTION_UP).useEvent { view.dispatchTouchEvent(it) }
            pasteMode()?.finish()
            clipboard.clearPrimaryClip()
            event(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY).useEvent { view.dispatchTouchEvent(it) }
            val mode = checkNotNull(pasteMode())
            check(!mode.menu.getItem(0).isEnabled) { "Empty clipboard Paste should be disabled" }
            mode.finish()
        }
        record("PASS: synthetic right-click and timed pointer hold offer only Paste without selection; selected text remains Copy-only; exact clipboard bytes reach encrypted SSH; empty clipboard disabled")
    }

    private fun verifyNativeIme(activity: SessionActivity, terminal: TerminalView) {
        click("Keyboard")
        // Unworn Home can briefly show then dismiss the keyboard. Keep visibility synthetic,
        // while exercising the real Compose editor and its actual Android InputConnection.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val visibilityPump = object : Runnable {
            override fun run() {
                val decor = activity.window.decorView
                decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                    .setVisible(android.view.WindowInsets.Type.ime(), true).build())
                handler.postDelayed(this, 100)
            }
        }
        val syntheticVisibility = !activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        if (syntheticVisibility) handler.post(visibilityPump)
        try {
        Thread.sleep(600)
        lateinit var editor: View
        lateinit var connection: android.view.inputmethod.InputConnection
        fun editorEnd(): Int = checkNotNull(connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)).text.length
        fun replaceLastCharacter() {
            val end = editorEnd()
            check(connection.replaceText(end - 1, end, "/", 1, null))
        }
        main {
            editor = checkNotNull(activity.keyboardEditorView)
            check(activity.keyboardEditorHasFocus) { "Compose SSH editor did not focus" }
            check(!terminal.hasFocus()) { "Terminal stole native focus from Compose" }
            connection = checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())) { "Missing real Compose InputConnection" }
            shellBytes.clear()
            check(connection.commitText("b", 1))
        }
        waitFor("Real SSH shell did not receive primary b") { shellBytes.contains('b'.code) }
        Thread.sleep(150)
        main {
            replaceLastCharacter()
        }
        try {
            waitFor("Real SSH shell did not receive alternative replacement") { shellBytes.contains(127) && shellBytes.contains('/'.code) }
            check(shellBytes.toList() == listOf('b'.code, 127, '/'.code)) { "Primary/replacement input reordered: ${shellBytes.toList()}" }
        } catch (error: Throwable) {
            record("IME replacement diagnostic: bytes=${shellBytes.toList()} focus=${activity.keyboardEditorHasFocus} actions=${activity.keyboardEditorActions?.focusRequests}/${activity.keyboardEditorActions?.restarts}/${activity.keyboardEditorActions?.focusResets}")
            throw error
        }
        main {
            val now = android.os.SystemClock.uptimeMillis()
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 20f, MotionEvent.ACTION_MOVE to 130f, MotionEvent.ACTION_UP to 130f)) {
                MotionEvent.obtain(now, now, action, x, terminal.height / 2f, 0).useEvent { terminal.dispatchTouchEvent(it) }
            }
            check(activity.keyboardEditorHasFocus && !terminal.hasFocus()) { "Drag-to-copy displaced the real IME editor" }
        }
        Thread.sleep(150)
        main {
            shellBytes.clear()
            check(connection.commitText("b", 1))
        }
        waitFor("Primary input failed after drag") { shellBytes.contains('b'.code) }
        Thread.sleep(150)
        main { replaceLastCharacter() }
        waitFor("Alternative input failed after drag") { shellBytes.contains(127) && shellBytes.contains('/'.code) }
        check(shellBytes.toList() == listOf('b'.code, 127, '/'.code)) { "Replacement after drag reordered: ${shellBytes.toList()}" }
        Thread.sleep(150)
        val burst = "0123456789".repeat(16)
        main {
            shellBytes.clear()
            // Consecutive writes reuse Termux's input buffer; every byte must reach SSH in order.
            check(connection.commitText(burst, 1))
        }
        val expected = burst.map { it.code }
        waitFor("Rapid IME writes lost bytes") { shellBytes.size >= expected.size }
        check(shellBytes.toList() == expected) { "Rapid SSH input corrupted or reordered: ${shellBytes.toList()}" }
        Thread.sleep(150)
        main {
            shellBytes.clear()
            check(connection.setComposingText("b", 1))
        }
        waitFor("Composing b did not reach SSH before Return") { shellBytes.contains('b'.code) }
        Thread.sleep(900)
        main { replaceLastCharacter() }
        waitFor("Composing alternative did not reach actual SSH shell") { shellBytes.isNotEmpty() }
        Thread.sleep(150)
        check(shellBytes.toList() == listOf('b'.code, 127, '/'.code)) { "Composing replacement left b or duplicated it: ${shellBytes.toList()}" }
        main {
            shellBytes.clear()
            check(connection.setComposingText("b", 1))
        }
        Thread.sleep(150)
        check(shellBytes.toList() == listOf('b'.code)) { "Composing primary waited for Return" }
        main { check(connection.finishComposingText()) }
        waitFor("Finished composing primary did not reach actual SSH shell") { shellBytes.isNotEmpty() }
        check(shellBytes.toList() == listOf('b'.code)) { "Finished primary duplicated: ${shellBytes.toList()}" }
        Thread.sleep(150)
        main {
            shellBytes.clear()
            check(connection.commitText("b", 1))
        }
        waitFor("Cached-range primary did not reach SSH") { shellBytes.contains('b'.code) }
        Thread.sleep(150)
        // Retained editor text keeps ExtractedText ranges valid after commit.
        main { replaceLastCharacter() }
        waitFor("Cached-range replacement did not reach SSH") { shellBytes.contains('/'.code) }
        check(shellBytes.toList() == listOf('b'.code, 127, '/'.code)) { "Cached-range alternative left b intact: ${shellBytes.toList()}" }
        Thread.sleep(150)
        main { shellBytes.clear() }
        var wordStart = 0
        main { wordStart = editorEnd() }
        for (prefix in listOf("w", "wo", "wor", "word")) {
            main {
                // The running Horizon IME may finalize composition between synthetic calls.
                check(connection.setComposingRegion(wordStart, editorEnd()))
                check(connection.setComposingText(prefix, 1))
            }
            waitFor("Composing $prefix waited for Return or duplicated a prefix") { shellBytes.size >= prefix.length }
            check(shellBytes.toList() == prefix.map { it.code }) { "Composing word duplicated text: ${shellBytes.toList()}" }
            Thread.sleep(150)
        }
        main { check(connection.finishComposingText()) }
        Thread.sleep(150)
        check(shellBytes.toList() == "word".map { it.code }) { "Finish resent composing word" }
        main {
            shellBytes.clear()
            check(connection.setSelection(0, 1))
            check(connection.finishComposingText())
        }
        Thread.sleep(250)
        check(shellBytes.isEmpty()) { "Keyboard dismissal callbacks sent keys: ${shellBytes.toList()}" }
        main { check(connection.setSelection(editorEnd(), editorEnd())) }
        Thread.sleep(150)
        check(shellBytes.isEmpty()) { "Selection-only callback sent sentinel: ${shellBytes.toList()}" }
        record("PASS: dismissal finish/selection callbacks on actual Compose InputConnection send zero SSH bytes")
        for (keyEventEnter in listOf(false, true)) {
            main {
                shellBytes.clear()
                check(connection.setComposingText("word", 1))
            }
            waitFor("Buffered word was not streamed") { shellBytes.size >= 4 }
            Thread.sleep(250)
            main {
                shellBytes.clear()
                if (keyEventEnter) {
                    check(connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)))
                    check(connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)))
                } else check(connection.commitText("word\n", 1))
            }
            waitFor("Buffered Return did not reach SSH") { shellBytes.isNotEmpty() }
            Thread.sleep(150)
            check(shellBytes.toList() == listOf(13)) { "Return replayed buffer or sent editing keys: ${shellBytes.toList()}" }
            main {
                val extracted = checkNotNull(connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0))
                check(extracted.text.toString() == " ") { "Return did not clear editor buffer: ${extracted.text}" }
                shellBytes.clear()
                check(connection.setComposingText("x", 1))
            }
            waitFor("Typing after Return did not reach SSH") { shellBytes.isNotEmpty() }
            Thread.sleep(150)
            check(shellBytes.toList() == listOf('x'.code)) { "Stale buffer changed typing after Return: ${shellBytes.toList()}" }
            main { check(connection.finishComposingText()) }
        }
        record("PASS: buffered newline commit and Enter key event send only SSH Return, clear actual Compose buffer, and preserve next typing")
        if (imeOnly) {
            main { shellBytes.clear() }
            click("Keyboard")
            Thread.sleep(800)
            check(shellBytes.isEmpty()) { "Closing keyboard sent buffered SSH input: ${shellBytes.toList()}" }
            record("PASS: toolbar keyboard close sends zero SSH bytes with retained editor text")
            return
        }
        record("PASS: composing characters and whole-word increments reach SSH before Return; finish does not duplicate; composing alternative erases primary")
        record("PASS: ExtractedText replacement range erases a committed primary without resetting the buffer")
        record("PASS: real Compose InputConnection commitText(b)/replaceText(/) reaches actual SSH shell in order before/after terminal drag; 160-character burst intact; physical Horizon popup remains manual")
        waitFor("Paged companion editor was not prepared") { activity.keyboardPanelEditorView != null }
        main {
            val bank = checkNotNull(activity.keyboardPanelEditorView)
            connection = checkNotNull(bank.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            shellBytes.clear()
            check(connection.setComposingText("b", 1))
        }
        waitFor("Bank composing b waited for Return") { shellBytes.toList() == listOf('b'.code) }
        main { replaceLastCharacter() }
        waitFor("Bank alternative left the primary") { shellBytes.toList() == listOf('b'.code, 127, '/'.code) }
        main {
            val strip = checkNotNull(activity.keyboardPanelEditorView)
            connection = checkNotNull(strip.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            shellBytes.clear()
            check(connection.commitText("x", 1))
        }
        waitFor("Strip input waited for Return after closing bank") { shellBytes.toList() == listOf('x'.code) }
        record("PASS: paged companion real InputConnection immediate composing SSH input and alternative replacement are checked on the always-full companion (retired 123/ABC paging removed); native focus handoff remains covered by toolbarOnly")
        click("Keyboard")
        } finally {
            handler.removeCallbacks(visibilityPump)
            if (syntheticVisibility) main {
                val decor = activity.window.decorView
                decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                    .setVisible(android.view.WindowInsets.Type.ime(), false).build())
            }
        }
    }
    private fun MotionEvent.useEvent(block: (MotionEvent) -> Unit) { try { block(this) } finally { recycle() } }
}
