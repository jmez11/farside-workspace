package com.farside.workspace

import androidx.activity.compose.setContent
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.farside.workspace.data.*
import com.farside.workspace.tunnel.CloudflaredTunnel
import com.farside.workspace.utils.NetworkScanner
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking

/** Actual LibVNCClient/JNI/surface on Quest. Fixture protocol is test-only, never a client. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal class VncVerification(private val test: Instrumentation) {
    private val results = File(test.targetContext.cacheDir, "vnc-results.txt")
    private fun record(text: String) { results.appendText("$text\n") }
    // macOS short username for the Mac VNC/SSH tests: setup-specific, never hardcoded.
    private fun macSshUser(): String = checkNotNull(test.arguments?.getString("macSshUser")) {
        "Missing instrumentation arg: pass -e macSshUser <macOS short username>"
    }
    private fun main(block: () -> Unit) = test.runOnMainSync(block)
    private fun await(reason: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!check()) { check(SystemClock.uptimeMillis() < end) { reason }; Thread.sleep(50) }
    }
    private fun texture(v: View): TextureView? = if (v is TextureView) v else (v as? ViewGroup)?.let { group ->
        (0 until group.childCount).firstNotNullOfOrNull { texture(group.getChildAt(it)) }
    }
    private fun capture(activity: SessionActivity, name: String): Bitmap {
        lateinit var image: Bitmap
        main { image = checkNotNull(texture(activity.window.decorView)?.bitmap) }
        File(test.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
    private inline fun <T> withVncEndpoint(profile: ConnectionProfile, block: (String, Int) -> T): T {
        val tunnel = if (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED) {
            CloudflaredTunnel(test.targetContext)
        } else null
        var host = profile.host
        var port = profile.port
        if (tunnel != null) {
            port = tunnel.start(profile.host, profile.cloudflaredParameters, {}, {}, accessSubcommand = "tcp")
            host = "127.0.0.1"
        }
        return try {
            block(host, port)
        } finally {
            tunnel?.close()
        }
    }
    fun run(mode: String, profileId: String?, password: String? = null): String {
        results.writeText("")
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        val clipboard = test.targetContext.getSystemService(android.content.ClipboardManager::class.java)
        val originalClip = clipboard.primaryClip
        try {
            if (mode == "proxyLive") {
                proxyLiveMac()
            } else if (mode == "proxy") {
                proxyFixture()
            } else if (mode == "macPairSetup") {
                val pm = ProfileManager(test.targetContext)
                val mac = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.name == "James Kasyan Mac" }
                pm.updateCredential(credential.copy(user = macSshUser()))
                val ssh = pm.getProfiles().firstOrNull { it.name == "Test MacBook Pro SSH" && it.type == ConnectionType.SSH }
                    ?: ConnectionProfile(java.util.UUID.randomUUID().toString(), "Test MacBook Pro SSH", ConnectionType.SSH,
                        mac.host, 22, "", "", credentialId = credential.id, enableSftp = false)
                pm.addProfile(ssh.copy(host = mac.host, port = 22, credentialId = credential.id, enableSftp = false))
                pm.updateProfile(mac.copy(credentialId = credential.id, vncAuthMode = VncAuthMode.MAC_USER,
                    sftpPairProfileId = ssh.id))
                check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
                check(pm.getProfiles().let { profiles ->
                    val vnc = profiles.single { it.id == mac.id }
                    val paired = profiles.single { it.id == ssh.id }
                    vnc.sftpPairProfileId == paired.id && vnc.vncAuthMode == VncAuthMode.MAC_USER &&
                        paired.user == macSshUser() && paired.pass.isNotEmpty() && !paired.enableSftp
                })
                record("PASS: saved credential now uses SSH short name; Mac VNC and SSH profiles share it, with explicit Files pairing")
            } else if (mode == "macSetup") {
                val pm = ProfileManager(test.targetContext)
                val profile = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.name == "James Kasyan Mac" }
                check(credential.user.isNotBlank() && credential.pass.isNotEmpty())
                pm.updateProfile(profile.copy(credentialId = credential.id, vncAuthMode = VncAuthMode.MAC_USER))
                check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
                check(pm.getProfiles().single { it.id == profile.id }.let {
                    it.credentialId == credential.id && it.vncAuthMode == VncAuthMode.MAC_USER
                })
                record("PASS: saved Mac profile linked to named credential in Apple account mode")
            } else if (mode == "macHevcSetup") {
                val pm = ProfileManager(test.targetContext)
                val original = pm.getProfiles().firstOrNull { it.type == ConnectionType.VNC && it.name.trim() == "Jeff Mac" }
                    ?: pm.getProfiles().first { it.type == ConnectionType.VNC }
                val existing = pm.getProfiles().firstOrNull { it.name == "Jeff Mac (HEVC)" }
                if (existing != null) {
                    pm.updateProfile(existing.copy(expMacHevc = true, host = original.host, port = original.port,
                        credentialId = original.credentialId, proxyType = original.proxyType, vncAuthMode = original.vncAuthMode))
                } else {
                    val clone = original.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Jeff Mac (HEVC)",
                        expMacHevc = true
                    )
                    pm.addProfile(clone)
                }
                check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
                record("PASS: configured Jeff Mac (HEVC) profile with expMacHevc=true; original Jeff Mac preserved unchanged")
            } else if (mode == "macWrong") {
                val pm = ProfileManager(test.targetContext)
                val profile = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.name == "James Kasyan Mac" }
                val texture = android.graphics.SurfaceTexture(0)
                val surface = android.view.Surface(texture)
                val started = SystemClock.uptimeMillis()
                try {
                    val bridge = VncBridge()
                    val error = runCatching { bridge.connect(profile.host, profile.port, credential.pass + "wrong",
                        surface, credential.user, VncAuthMode.MAC_USER) }.exceptionOrNull()
                    check(error is VncAuthenticationException) { "Expected Apple SecurityResult failure, got ${error?.javaClass?.simpleName}" }
                    check(SystemClock.uptimeMillis() - started < 15000) { "Authentication hung" }
                    record("PASS: Apple server rejected wrong password with SecurityResult; no native session or hang")
                } finally { surface.release(); texture.release() }
                val wrong = SavedCredential(name = "Temporary Mac wrong password", user = credential.user,
                    pass = credential.pass + "wrong")
                pm.addCredential(wrong)
                try {
                    val done = CountDownLatch(1)
                    var reason: String? = null
                    val owner = VncSessionConnection(test.targetContext, profile.copy(credentialId = wrong.id,
                        vncAuthMode = VncAuthMode.MAC_USER), VncBridge(),
                        onDisconnected = { text, _ -> reason = text; done.countDown() }, onConnected = {})
                    val uiTexture = android.graphics.SurfaceTexture(0)
                    try {
                        owner.attach(android.view.Surface(uiTexture))
                        check(done.await(15, TimeUnit.SECONDS)) { "Localized authentication error hung" }
                        check(reason == test.targetContext.getString(R.string.vnc_mac_auth_failed)) {
                            "Wrong localized error: $reason"
                        }
                        record("PASS: session callback emitted localized Mac authentication failure")
                    } finally { owner.close(); uiTexture.release() }
                } finally { pm.deleteCredential(wrong.id) }
            } else if (mode == "macShortUser") {
                val pm = ProfileManager(test.targetContext)
                val profile = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.name == "James Kasyan Mac" }
                val texture = android.graphics.SurfaceTexture(0)
                val surface = android.view.Surface(texture)
                val bridge = VncBridge()
                try {
                    val handle = bridge.connect(profile.host, profile.port, credential.pass, surface,
                        macSshUser(), VncAuthMode.MAC_USER)
                    check(handle != 0L) { "Mac rejected short account name" }
                    bridge.disconnect(handle)
                    record("PASS: Apple VNC accepted the Mac SSH short username with the saved password")
                } finally { surface.release(); texture.release() }
            } else if (mode == "macHevcPhase1") {
                val pm = ProfileManager(test.targetContext)
                val profile = pm.getProfiles().firstOrNull {
                    it.type == ConnectionType.VNC && (profileId == null || it.id == profileId.trim() || it.name.trim() == profileId.trim())
                } ?: pm.getProfiles().first { it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.id == profile.credentialId }
                withVncEndpoint(profile) { host, port ->
                    MacHevcSession(host, port, credential.user, credential.pass) { logLine ->
                        record(logLine)
                    }.use { session ->
                        val firstRecord = session.start()
                        check(session.stage == "S8") { "Did not reach S8: ${session.stage}" }
                        check(session.recordLayer != null) { "Record layer null" }
                        check(firstRecord.isNotEmpty()) { "Empty first record" }
                        val recType = firstRecord[0].toInt() and 0xff
                        record("PASS: Mac HEVC Phase 1 Gate - reached S8 (record active), decrypted control record type=0x${Integer.toHexString(recType)} len=${firstRecord.size}, recvSeq=${session.recordLayer!!.recvSeq}")

                        // Negative gate: any integrity failure MUST close session, never continue
                        val layer = session.recordLayer!!
                        val dummyOuter = layer.encryptRecord(byteArrayOf(0x14, 0, 0, 0))
                        val corruptedCiphertext = dummyOuter.copyOfRange(2, dummyOuter.size)
                        corruptedCiphertext[corruptedCiphertext.size - 5] = (corruptedCiphertext[corruptedCiphertext.size - 5].toInt() xor 0x01).toByte()
                        var integrityCaught = false
                        try {
                            layer.decryptRecord(corruptedCiphertext)
                        } catch (e: MacHevcIntegrityException) {
                            integrityCaught = true
                        }
                        check(integrityCaught) { "Negative gate failed: integrity mismatch did not throw MacHevcIntegrityException" }
                        record("PASS: Mac HEVC Phase 1 Negative Gate - integrity mismatch throws MacHevcIntegrityException and halts processing")
                    }
                }
            } else if (mode == "macHevcPhase2") {
                val pm = ProfileManager(test.targetContext)
                val profile = pm.getProfiles().firstOrNull {
                    it.type == ConnectionType.VNC && (profileId == null || it.id == profileId.trim() || it.name.trim() == profileId.trim())
                } ?: pm.getProfiles().first { it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.id == profile.credentialId }
                withVncEndpoint(profile) { host, port ->
                    MacHevcSession(host, port, credential.user, credential.pass) { logLine ->
                        record(logLine)
                    }.use { session ->
                        val firstRecord = session.start()
                        check(session.stage == "S8") { "Did not reach S8: ${session.stage}" }

                        // Initialize streaming record layer and transition to S9
                        session.initStreamingLayer(firstRecord)
                        check(session.stage == "S9") { "Did not reach S9: ${session.stage}" }

                        // Read first framebuffer update (reassembling multi-record zlib stream)
                        val update1 = session.readNextFramebufferUpdate()
                        check(update1.messageType == 0) { "Expected FramebufferUpdate (0), got ${update1.messageType}" }
                        check(update1.decompressedBytes > 0) { "No pixels decompressed" }
                        check(update1.zlibRectCount > 0) { "No zlib rects decompressed" }
                        record("PASS: S9 first update: ${update1.decompressedBytes} bytes (${update1.width}x${update1.height}), encodings=${update1.encodingsSeen}")

                        // Send pointer motion over encrypted record layer
                        session.sendPointer(100, 100, 0)
                        session.sendPointer(120, 120, 0)

                        // Request and receive next update
                        session.requestUpdate(incremental = true)
                        val update2 = session.readNextFramebufferUpdate()
                        record("PASS: S9 steady-state update 2: ${update2.decompressedBytes} bytes, encodings=${update2.encodingsSeen}")

                        record("PASS: Mac HEVC Phase 2 Gate - reached steady state (S9) on framebuffer path over encrypted record layer, decoded desktop frames, handled vendor encodings")
                    }
                }
            } else if (mode == "macHevcPhase3") {
                val pm = ProfileManager(test.targetContext)
                val profile = pm.getProfiles().firstOrNull {
                    it.type == ConnectionType.VNC && (profileId == null || it.id == profileId.trim() || it.name.trim() == profileId.trim())
                } ?: pm.getProfiles().first { it.type == ConnectionType.VNC }
                val credential = pm.getCredentials().single { it.id == profile.credentialId }
                withVncEndpoint(profile) { host, port ->
                    MacHevcSession(host, port, credential.user, credential.pass) { logLine ->
                        record(logLine)
                    }.use { session ->
                        val firstRecord = session.start()
                        check(session.stage == "S8") { "Did not reach S8: ${session.stage}" }
                        session.initStreamingLayer(firstRecord)
                        check(session.stage == "S9") { "Did not reach S9: ${session.stage}" }

                        val update1 = session.readNextFramebufferUpdate()
                        record("S9 Framebuffer baseline: ${update1.decompressedBytes} bytes, encodings=${update1.encodingsSeen}")

                        // Step 1: Probe 0x1d SetDisplayConfiguration (primary display)
                        try {
                            session.sendDisplayConfiguration(session.width, session.height, displayType = 0)
                            session.requestUpdate(incremental = true)
                            val resp1d = session.readNextFramebufferUpdate()
                            record("S9 0x1d (displayType=0) result: msgType=${resp1d.messageType}, encodings=${resp1d.encodingsSeen}")
                        } catch (e: Exception) {
                            record("S9 0x1d (displayType=0) error: ${e.javaClass.simpleName}: ${e.message}")
                        }

                        // Step 2: Probe 0x1d SetDisplayConfiguration (virtual display: displayType = 4)
                        try {
                            session.sendDisplayConfiguration(1920, 1080, displayType = 4)
                            session.requestUpdate(incremental = true)
                            val respVirt = session.readNextFramebufferUpdate()
                            record("S9 0x1d (displayType=4 virtual) result: msgType=${respVirt.messageType}, encodings=${respVirt.encodingsSeen}")
                        } catch (e: Exception) {
                            record("S9 0x1d (displayType=4 virtual) error: ${e.javaClass.simpleName}: ${e.message}")
                        }

                        // Step 3: Probe 0x1c MediaStreamOptions offer
                        try {
                            session.sendMediaStreamOffer()
                            val resp1c = session.readNextFramebufferUpdate()
                            record("S9 0x1c Media response: msgType=${resp1c.messageType}, encodings=${resp1c.encodingsSeen}")
                        } catch (e: Exception) {
                            record("S9 0x1c Media exchange error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            } else if (mode == "danglingPair") {
                val pm = ProfileManager(test.targetContext)
                val original = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
                val orphan = original.copy(id = java.util.UUID.randomUUID().toString(),
                    sftpPairProfileId = "deleted-ssh-profile")
                pm.addProfile(orphan)
                check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
                try {
                    session(orphan, null, expectedFiles = false)
                    record("PASS: VNC opened with deleted SSH pair; Files action absent and no crash")
                } finally {
                    pm.deleteProfile(orphan.id)
                    check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
                }
            } else if (mode == "create") {
                require(!password.isNullOrEmpty()) { "VNC password missing" }
                val found = runBlocking { NetworkScanner.scanLocalNetworkForVnc(test.targetContext) }
                require(found.size == 1) { "Expected one Mac advertisement, found: ${found.joinToString { it.name }}" }
                val host = found.single()
                val pm = ProfileManager(test.targetContext)
                val credential = SavedCredential(name = "${host.name} VNC", user = "", pass = password)
                pm.addCredential(credential)
                val profile = ConnectionProfile(java.util.UUID.randomUUID().toString(), "${host.name} VNC", ConnectionType.VNC,
                    host.ip, host.port, "", "", credentialId = credential.id)
                pm.addProfile(profile)
                test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit()
                record("Created saved VNC profile: ${profile.name} ${host.ip}:${host.port}; password stored only in encrypted SavedCredential")
                session(profile, null)
            } else if (mode == "live" || mode == "macPicker" || mode == "pointer" || mode == "login" || mode == "macLogin") {
                var profile = ProfileManager(test.targetContext).getProfiles().firstOrNull {
                    it.type == ConnectionType.VNC && (profileId == null || it.id == profileId.trim() || it.name.trim() == profileId.trim())
                } ?: error("Save a VNC Mac profile and pass -e vncProfile <name/id>.")
                if (mode == "macPicker") {
                    val found = runBlocking { NetworkScanner.scanLocalNetworkForVnc(test.targetContext) }
                    val host = found.single { it.ip == profile.host && it.port == profile.port }
                    profile = chooseService(profile.copy(host = "", port = 5900), host.name)
                    check(profile.host == host.name && profile.port == host.port)
                    record("PASS: real Mac ${host.name} selected in actual VNC picker; Bonjour name/port saved with existing encrypted credential link")
                }
                val loginPassword = if (mode == "macLogin") ProfileManager(test.targetContext).getCredentials()
                    .single { it.id == profile.credentialId }.pass else if (mode == "login") requireNotNull(password) else null
                session(profile, null, probePointer = mode == "pointer", loginPassword = loginPassword,
                    loginAccountX = if (mode == "macLogin") 280 else 400, verifyFinder = mode != "macLogin")
                record(if (loginPassword != null) "LIVE LOGIN PASS: Mac desktop rendered after saved account login; clean close. Physical gestures/keys and real Mac clipboard remain pending."
                    else "LIVE PASS: saved VNC credential connect, nonblank actual surface, pointer event, clean close. Mac-visible input/clipboard and physical gestures require wearer checks.")
            } else if (mode == "discovery") {
                val found = runBlocking { NetworkScanner.scanLocalNetworkForVnc(test.targetContext) }
                record("Real _rfb._tcp browse: ${found.joinToString { "${it.name} ${it.ip}:${it.port}" }}")
            } else {
                val pm = ProfileManager(test.targetContext)
                val credential = SavedCredential("vnc-verification-credential", "VNC verification", "", "testpass")
                pm.addCredential(credential)
                try {
                    for ((encoding, cursor) in if (mode == "picker") emptyList() else listOf(16, 7, 8, 5, 2, 0).map { it to true } + (0 to false)) Fixture(encoding, sendCursor = cursor).use { server ->
                        val profile = ConnectionProfile("vnc-verification-profile", "VNC verification", ConnectionType.VNC,
                            "127.0.0.1", server.port, "", "", credentialId = credential.id)
                        check(!profile.toJson().has("pass") && !profile.toJson().has("passEncrypted"))
                        check(!credential.toJson().toString().contains("testpass"))
                        pm.addProfile(profile)
                        try { session(profile, server) } finally { pm.deleteProfile(profile.id) }
                    }
                    if (mode != "picker") authenticationFailures()
                    discoveryFixture(credential.id)
                    record("PASS: VNC profile uses credential link only; Keystore credential ciphertext; normal API enforcement.")
                } finally {
                    pm.deleteProfile("vnc-verification-profile"); pm.deleteCredential(credential.id)
                    test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit()
                }
            }
            return results.readText()
        } finally {
            if (originalClip != null) clipboard.setPrimaryClip(originalClip) else clipboard.clearPrimaryClip()
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }
    private fun session(profile: ConnectionProfile, server: Fixture?, probePointer: Boolean = false,
        loginPassword: String? = null, loginAccountX: Int = 400, verifyFinder: Boolean = true,
        expectedFiles: Boolean? = null) {
        val activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java)
            .putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
        var instance = 0L
        var bridge: VncBridge? = null
        try {
            if (server?.encoding == 16 && QuestWindowManager.isAvailable(test.targetContext)) {
                val info = test.uiAutomation.serviceInfo
                info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                test.uiAutomation.serviceInfo = info
                fun toolbarNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                    test.uiAutomation.clearCache()
                    fun descend(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
                        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descend).orEmpty() }
                    val displays = test.uiAutomation.windowsOnAllDisplays
                    return (0 until displays.size()).flatMap { i -> displays.valueAt(i).flatMap { window ->
                        window.root?.takeIf { it.packageName == test.targetContext.packageName }?.let(::descend).orEmpty()
                    } }
                }
                await("VNC toolbar Pin missing") { toolbarNodes().any { it.contentDescription?.toString() == "Keep toolbar visible" } }
                var pin = toolbarNodes().first { it.contentDescription?.toString() == "Keep toolbar visible" }
                while (!pin.isClickable) pin = checkNotNull(pin.parent)
                check(pin.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await("VNC toolbar Window Manager missing") {
                    toolbarNodes().any { it.contentDescription?.toString() == "Window Manager" }
                }
                val pinned = toolbarNodes().first { it.contentDescription?.toString() == "Unpin toolbar" }
                val manager = toolbarNodes().first { it.contentDescription?.toString() == "Window Manager" }
                val pinBounds = android.graphics.Rect().also(pinned::getBoundsInScreen)
                val managerBounds = android.graphics.Rect().also(manager::getBoundsInScreen)
                check(pinBounds.right <= managerBounds.left && pinBounds.centerY() == managerBounds.centerY()) {
                    "VNC Window Manager button is not next to Pin"
                }
            }
            await("VNC did not connect") {
                server?.failure?.let { throw AssertionError("Fixture handshake failed (${server.stage})", it) }
                if (server?.disconnected?.count == 0L && activity.vncInstance == 0L) error("Fixture disconnected: ${server.stage}")
                instance = activity.vncInstance; bridge = activity.vncBridge; instance != 0L }
            Thread.sleep(800)
            await("No nonblank framebuffer") {
                runCatching {
                    var pixel = 0
                    main { texture(activity.window.decorView)?.bitmap?.let { image ->
                        pixel = image.getPixel(image.width / 4, image.height / 2); image.recycle()
                    } }
                    pixel and 0x00ffffff != 0
                }.getOrDefault(false)
            }
            capture(activity, "vnc-frame-${server?.encoding ?: "live"}.png").recycle()
            if (expectedFiles != null) {
                val info = test.uiAutomation.serviceInfo
                info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                test.uiAutomation.serviceInfo = info
                test.uiAutomation.clearCache()
                fun descend(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
                    listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descend).orEmpty() }
                val windows = test.uiAutomation.windowsOnAllDisplays.let { all ->
                    (0 until all.size()).flatMap { all.valueAt(it) }
                }
                val hasFiles = windows.filter { it.root?.packageName == test.targetContext.packageName }
                    .flatMap { it.root?.let(::descend).orEmpty() }
                    .any { it.contentDescription?.toString() == test.targetContext.getString(R.string.browse_files) }
                check(hasFiles == expectedFiles) { "Unexpected paired Files toolbar action: $hasFiles" }
            }
            check(bridge!!.getDesktopWidth(instance) > 0 && bridge!!.getDesktopHeight(instance) > 0)
            // Opt-in diagnostic for this Mac's known login screen. The account password
            // is a transient instrumentation argument, never saved or included in results.
            if (loginPassword != null) {
                check(bridge!!.getDesktopWidth(instance) == 1280 && bridge!!.getDesktopHeight(instance) == 800)
                fun click(x: Int, y: Int) {
                    bridge!!.sendPointerEvent(instance, x, y, 0); Thread.sleep(100)
                    bridge!!.sendPointerEvent(instance, x, y, 1); Thread.sleep(100)
                    bridge!!.sendPointerEvent(instance, x, y, 0); Thread.sleep(800)
                }
                click(loginAccountX, 280); click(640, 400)
                loginPassword.forEach { c ->
                    bridge!!.sendUnicodeKeyEvent(instance, c.code, true)
                    bridge!!.sendUnicodeKeyEvent(instance, c.code, false)
                }
                bridge!!.sendUnicodeKeyEvent(instance, 13, true); bridge!!.sendUnicodeKeyEvent(instance, 13, false)
                repeat(10) { index ->
                    Thread.sleep(2000); capture(activity, "vnc-login-$index.png").recycle()
                    record("Login transition frame $index: ${bridge!!.getDesktopWidth(instance)}x${bridge!!.getDesktopHeight(instance)}")
                }
                val desktop = capture(activity, "vnc-login-desktop.png")
                val points = (10 until desktop.height step 20).flatMap { y ->
                    (10 until desktop.width step 20).map { x -> x to y }
                }
                check(points.count { (x, y) -> desktop.getPixel(x, y) and 0x00ffffff != 0 } > points.size * 0.1) {
                    "Mac desktop remains black after login reset"
                }
                if (verifyFinder) {
                fun command(scancode: Int) {
                    bridge!!.sendKeyEvent(instance, 0x15b, true)
                    bridge!!.sendKeyEvent(instance, scancode, true); bridge!!.sendKeyEvent(instance, scancode, false)
                    bridge!!.sendKeyEvent(instance, 0x15b, false)
                }
                command(0x31) // Finder Command+N: a new window, without changing files.
                Thread.sleep(2000)
                val window = capture(activity, "vnc-login-finder.png")
                check(points.count { (x, y) -> desktop.getPixel(x, y) != window.getPixel(x, y) } > points.size / 10) {
                    "Mac window updates did not change the rendered desktop"
                }
                command(0x11) // Close the window created by the diagnostic.
                await("Finder window did not close/redraw") {
                    var restored = false
                    main { texture(activity.window.decorView)?.bitmap?.let { image ->
                        restored = points.count { (x, y) -> desktop.getPixel(x, y) != image.getPixel(x, y) } < points.size / 20
                        image.recycle()
                    } }
                    restored
                }
                capture(activity, "vnc-login-restored.png").recycle()
                window.recycle()
                record("PASS: actual Mac login reset renders desktop; Command+N opens a visibly updated Finder window and Command+W closes it")
                } else record("PASS: saved macOS account logged in and rendered the Mac desktop wallpaper")
                desktop.recycle()
            }
            if (probePointer) {
                val decor = activity.window.decorView
                val width = bridge!!.getDesktopWidth(instance)
                val height = bridge!!.getDesktopHeight(instance)
                fun touch(action: Int, downTime: Long) = main {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        decor.width * 400f / width, decor.height * 280f / height, 0)
                    decor.dispatchTouchEvent(event); event.recycle()
                }
                val down = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, down); Thread.sleep(100); touch(MotionEvent.ACTION_UP, down)
                Thread.sleep(800); capture(activity, "vnc-pointer-shared-tap.png").recycle()
                bridge!!.sendPointerEvent(instance, 400, 280, 0); Thread.sleep(100)
                bridge!!.sendPointerEvent(instance, 400, 280, 1); Thread.sleep(100)
                bridge!!.sendPointerEvent(instance, 400, 280, 0); Thread.sleep(800)
                capture(activity, "vnc-pointer-paced-tap.png").recycle()
                val fieldDown = SystemClock.uptimeMillis()
                main {
                    val event = MotionEvent.obtain(fieldDown, fieldDown, MotionEvent.ACTION_DOWN,
                        decor.width * 640f / width, decor.height * 400f / height, 0)
                    decor.dispatchTouchEvent(event); event.recycle()
                }
                Thread.sleep(100)
                main {
                    val event = MotionEvent.obtain(fieldDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        decor.width * 640f / width, decor.height * 400f / height, 0)
                    decor.dispatchTouchEvent(event); event.recycle()
                    decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_X))
                    decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_X))
                }
                Thread.sleep(600); capture(activity, "vnc-pointer-field-typed.png").recycle()
                main {
                    decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                    decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
                }
                Thread.sleep(600); capture(activity, "vnc-pointer-field-cleared.png").recycle()
                main {
                    val hover = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE,
                        decor.width * 0.75f, decor.height * 0.65f, 0).apply { source = android.view.InputDevice.SOURCE_MOUSE }
                    decor.dispatchGenericMotionEvent(hover); hover.recycle()
                }
                Thread.sleep(600); capture(activity, "vnc-pointer-hover.png").recycle()
                record("Pointer diagnostic: shared tap and separately paced native tap at 400,280; inspect frames for Mac UI response")
            }
            if (server != null) check(server.encodings.take(3) == listOf(16, 7, 8)) { "Wrong encoding preference: ${server.encodings}" }
            // Actual shared unscaled overlay receives a drag at local screen coordinates.
            val decor = activity.window.decorView
            val start = SystemClock.uptimeMillis()
            main {
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                    val e = MotionEvent.obtain(start, start + i * 60, action,
                        decor.width * (0.3f + i * 0.1f), decor.height * 0.65f, 0)
                    decor.dispatchTouchEvent(e); e.recycle()
                }
            }
            if (server != null) {
                await("Shared pointer drag did not reach VNC") { server.pointers.any { it.third == 1 } && server.pointers.lastOrNull()?.third == 0 }
                val last = server.pointers.last()
                check(last.first in 30..33 && last.second in 30..32) { "Pointer map drift: $last" }
                fun cursorPixel(x: Int, y: Int): Int {
                    var pixel = 0
                    main { texture(decor)?.bitmap?.let { image ->
                        pixel = image.getPixel(((x + 0.5f) * image.width / 64).toInt(), ((y + 0.5f) * image.height / 48).toInt()); image.recycle()
                    } }
                    return pixel and 0x00ffffff
                }
                val cursorDx = if (server.sendCursor) 0 else 1
                val cursorDy = if (server.sendCursor) 0 else 3
                await("Cursor did not follow shared drag") { cursorPixel(last.first + cursorDx, last.second + cursorDy) == 0xffffff }
                main {
                    val hover = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE,
                        decor.width * 0.75f, decor.height * 0.5f, 0).apply { source = android.view.InputDevice.SOURCE_MOUSE }
                    decor.dispatchGenericMotionEvent(hover); hover.recycle()
                }
                await("Shared hover did not move VNC cursor") { server.pointers.last().first == 48 && server.pointers.last().second == 24 && cursorPixel(48 + cursorDx, 24 + cursorDy) == 0xffffff }
                check(cursorPixel(last.first + cursorDx, last.second + cursorDy) != 0xffffff) { "Cursor left trails on the desktop" }
                server.cursorUpdate(0, 0, hide = false)
                await("Server cursor position/hotspot clipping missing") { cursorPixel(cursorDx, cursorDy) == 0xffffff }
                server.cursorUpdate(0, 0, hide = true)
                await("Hidden cursor remains visible") { cursorPixel(cursorDx, cursorDy) != 0xffffff }
                record("PASS: cursor ${if (server.sendCursor) "shape/mask/hotspot" else "fallback without server shape"}, shared drag/hover movement, server position, clipping, hide and restoration without a new desktop frame")
                for (flags in listOf(0x278, 0x388, 0x478, 0x588)) bridge!!.sendMouseEvent(instance, 32, 31, flags)
                await("VNC wheel directions/release missing") {
                    listOf(8, 16, 32, 64).all { mask -> server.pointers.any { it.third == mask } } && server.pointers.last().third == 0
                }
                val clipboard = test.targetContext.getSystemService(android.content.ClipboardManager::class.java)
                main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VNC verification", "Quest clipboard é")) }
                await("Android clipboard did not reach VNC") { server.clipboard.contains("Quest clipboard é") }
                server.sendClipboard("VNC server é")
                await("Server clipboard did not reach Android") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "VNC server é" }
                // Real shared Compose physical-key path, plus XT modifiers and supplementary Unicode.
                main {
                    decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A))
                    decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_A))
                    decor.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_C, 0, android.view.KeyEvent.META_CTRL_ON))
                    decor.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_C, 0, android.view.KeyEvent.META_CTRL_ON))
                }
                bridge!!.sendKeyEvent(instance, 0x15b, true); bridge!!.sendKeyEvent(instance, 0x15b, false)
                bridge!!.sendUnicodeKeyEvent(instance, 0x1f600, true); bridge!!.sendUnicodeKeyEvent(instance, 0x1f600, false)
                await("VNC keys missing") { server.keys.contains(0xffeb to true) && server.keys.contains(0x0101f600 to false) && server.keys.contains('a'.code to true) && server.keys.contains('c'.code to false) }
                if (server.encoding == 16) {
                    fun pixelAt(x: Int, y: Int, width: Int, height: Int): Int {
                        var pixel = 0
                        main { texture(decor)?.bitmap?.let { image ->
                            pixel = image.getPixel(((x + 0.5f) * image.width / width).toInt(), ((y + 0.5f) * image.height / height).toInt()); image.recycle()
                        } }
                        return pixel and 0x00ffffff
                    }
                    for ((width, height) in listOf(64 to 48, 80 to 56)) {
                        val previous = server.resetFrames
                        server.resetDesktop(width, height)
                        await("Reset did not negotiate and render a replacement ZRLE frame") {
                            server.failure?.let { throw AssertionError("Reset failed", it) }
                            server.resetFrames > previous && bridge!!.getDesktopWidth(instance) == width &&
                                bridge!!.getDesktopHeight(instance) == height && pixelAt(20, 20, width, height) == 0x285adc
                        }
                        server.partialFrame()
                        await("Partial ZRLE update after reset missing") { pixelAt(54, 34, width, height) == 0x14dc50 }
                        check(pixelAt(20, 20, width, height) == 0x285adc) { "Partial frame damaged unchanged desktop" }
                        capture(activity, "vnc-reset-${width}x$height.png").recycle()
                    }
                    record("PASS: same-size and growing framebuffer resets reassert pixel format/ordered encodings before full requests; replacement and partial ZRLE frames render using the persistent zlib stream")
                }
            }
            main { activity.finish() }
            await("Native handle was not released") { bridge!!.getDesktopWidth(instance) == 0 }
            if (server != null) {
                await("VNC socket survived disconnect") { server.disconnected.count == 0L }
                server.failure?.let { throw AssertionError("Fixture failed", it) }
            }
            record(if (server != null) "PASS: ${server.encoding} nonblank ANativeWindow frame, shared drag/mapping, key/clipboard callbacks and stop/join/socket teardown"
                else if (loginPassword != null) "PASS: logged-in Mac framebuffer, pointer dispatch and clean native disconnect"
                else "PASS: live nonblank ANativeWindow frame, shared pointer dispatch and clean native disconnect; Mac-visible input/clipboard not asserted")
        } finally { main { if (!activity.isFinishing) activity.finish() }; Thread.sleep(400) }
    }
    private fun authenticationFailures() {
        val image = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(64, 48) }
        val surface = android.view.Surface(image)
        try {
            for ((scheme, password) in listOf(1 to "testpass", 30 to "testpass", 2 to "badpass")) Fixture(0, scheme).use { server ->
                val bridge = VncBridge()
                check(bridge.connect("127.0.0.1", server.port, password, surface) == 0L) { "Accepted unauthenticated, ARD or wrong password server" }
            }
            record("PASS: None/ARD-only security and wrong VNC password rejected")
        } finally { surface.release(); image.release() }
    }
    private fun proxyFixture() {
        val image = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(64, 48) }
        val surface = android.view.Surface(image)
        try {
            Fixture(0).use { server -> Socks5Fixture(server.port).use { proxy ->
                val profile = ConnectionProfile("vnc-socks-test", "VNC SOCKS5 test", ConnectionType.VNC,
                    "127.0.0.1", server.port, "", "", proxyType = ProxyType.SOCKS5,
                    proxyHost = "127.0.0.1", proxyPort = proxy.port,
                    proxyUser = "proxy-fixture", proxyPass = "secret")
                val bridge = VncBridge()
                val instance = bridge.connect(profile.host, profile.port, "testpass", surface,
                    httpProxy = profile)
                try {
                    check(instance != 0L && bridge.getDesktopWidth(instance) == 64)
                    check(proxy.authenticated && proxy.destination == "127.0.0.1:${server.port}")
                    record("PASS: standard VNC framebuffer through authenticated SOCKS5")
                } finally { bridge.disconnect(instance) }
            } }
            Fixture(0).use { server -> Socks5Fixture(server.port).use { proxy ->
                val profile = ConnectionProfile("vnc-socks-mac-test", "VNC SOCKS5 Mac test", ConnectionType.VNC,
                    "127.0.0.1", server.port, "", "", proxyType = ProxyType.SOCKS5,
                    proxyHost = "127.0.0.1", proxyPort = proxy.port,
                    proxyUser = "proxy-fixture", proxyPass = "secret")
                check(runCatching { VncBridge().connect(profile.host, profile.port, "testpass", surface,
                    "fixture", VncAuthMode.MAC_USER, profile) }.exceptionOrNull() is VncAuthenticationException)
                check(proxy.authenticated && proxy.destination == "127.0.0.1:${server.port}")
                record("PASS: Mac VNC preauthentication reached server through SOCKS5")
            } }
            Fixture(0).use { server -> HttpConnectFixture(server.port).use { proxy ->
                val firstImage = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(64, 48) }
                val firstSurface = android.view.Surface(firstImage)
                val pm = ProfileManager(test.targetContext)
                val credential = SavedCredential("vnc-http-credential", "VNC HTTP verification", "", "testpass")
                pm.addCredential(credential)
                val profile = ConnectionProfile("vnc-http-test", "VNC HTTP test", ConnectionType.VNC,
                    "127.0.0.1", server.port, "", "", proxyType = ProxyType.HTTP,
                    proxyHost = "127.0.0.1", proxyPort = proxy.port, credentialId = credential.id,
                    proxyUser = "proxy-fixture", proxyPass = "secret")
                val bridge = VncBridge()
                val done = CountDownLatch(1)
                var instance = -1L
                var reason = ""
                val connection = VncSessionConnection(test.targetContext, profile, bridge,
                    onDisconnected = { message, _ -> reason = message }, onConnected = { instance = it; done.countDown() })
                try {
                    connection.attach(firstSurface)
                    check(done.await(15, TimeUnit.SECONDS)) { "VNC HTTP connection timed out: $reason" }
                    check(instance != 0L && bridge.getDesktopWidth(instance) == 64)
                    check(proxy.request?.startsWith("CONNECT 127.0.0.1:${server.port} HTTP/1.1\r\n") == true)
                    check(proxy.request?.contains("Proxy-Authorization: Basic cHJveHktZml4dHVyZTpzZWNyZXQ=\r\n") == true)
                    record("PASS: standard VNC authentication and native framebuffer through HTTP CONNECT")
                } finally { connection.close(); Thread.sleep(200); firstImage.release(); pm.deleteCredential(credential.id) }
            } }
            Fixture(0).use { server -> HttpConnectFixture(server.port).use { proxy ->
                val profile = ConnectionProfile("vnc-http-mac-test", "VNC HTTP Mac test", ConnectionType.VNC,
                    "127.0.0.1", server.port, "", "", proxyType = ProxyType.HTTP,
                    proxyHost = "127.0.0.1", proxyPort = proxy.port)
                check(runCatching { VncBridge().connect(profile.host, profile.port, "testpass", surface,
                    "fixture", VncAuthMode.MAC_USER, profile) }.exceptionOrNull() is VncAuthenticationException)
                check(proxy.request?.startsWith("CONNECT 127.0.0.1:${server.port} HTTP/1.1\r\n") == true)
                record("PASS: Mac VNC preauthentication reached its server through HTTP CONNECT")
            } }
        } finally { surface.release(); image.release() }
    }
    private fun proxyLiveMac() {
        val pm = ProfileManager(test.targetContext)
        val profile = pm.getProfiles().single { it.name == "Test MacBook Pro" && it.type == ConnectionType.VNC }
        val credential = pm.getCredentials().single { it.id == profile.credentialId }
        val image = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(image)
        try {
            HttpConnectFixture(profile.port, profile.host).use { proxy ->
                val throughProxy = profile.copy(proxyType = ProxyType.HTTP, proxyHost = "127.0.0.1", proxyPort = proxy.port)
                val bridge = VncBridge()
                val instance = bridge.connect(profile.host, profile.port, credential.pass, surface,
                    credential.user, VncAuthMode.MAC_USER, throughProxy)
                try {
                    check(instance != 0L && bridge.getDesktopWidth(instance) > 0 && bridge.getDesktopHeight(instance) > 0)
                    check(proxy.request?.startsWith("CONNECT ${profile.host}:${profile.port} HTTP/1.1\r\n") == true)
                    record("PASS: live Mac account VNC authenticated and initialized through HTTP CONNECT; no input sent")
                } finally { bridge.disconnect(instance) }
            }
        } finally { surface.release(); image.release() }
    }
    private fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        val out = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
            out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        val windows = test.uiAutomation.windowsOnAllDisplays
        for (i in 0 until windows.size()) windows.valueAt(i).forEach { it.root?.let(::walk) }
        return out
    }
    private fun click(label: String) {
        var target: android.view.accessibility.AccessibilityNodeInfo? = null
        var lastScroll = 0L
        try { await("Missing UI control $label") {
            val visible = nodes()
            target = visible.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (target == null && visible.none { it.text?.startsWith("Finding local") == true } && SystemClock.uptimeMillis() - lastScroll > 500) {
                visible.firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                lastScroll = SystemClock.uptimeMillis()
            }
            target != null
        } } catch (error: Throwable) {
            record("UI control failure ($label): ${nodes().mapNotNull { it.text?.toString() }.distinct()}")
            test.uiAutomation.takeScreenshot()?.let { image ->
                File(test.targetContext.cacheDir, "vnc-picker-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            throw error
        }
        var n = checkNotNull(target)
        while (!n.isClickable && n.parent != null) n = n.parent
        check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }
    private fun chooseService(initial: ConnectionProfile, serviceName: String): ConnectionProfile {
        val pm = ProfileManager(test.targetContext)
        val accessibility = test.uiAutomation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = accessibility
        val launcher = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val saved = java.util.concurrent.atomic.AtomicReference<ConnectionProfile?>()
        try {
            main { launcher.setContent {
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    com.farside.workspace.ui.AddProfileForm(initialProfile = initial, onCancel = {}, profileManager = pm,
                        onSave = { saved.set(it); pm.addProfile(it) })
                }
            } }
            Thread.sleep(800)
            click("macOS user")
            click("Click here to find local Macs")
            await("Mac scan progress did not say Macs") { nodes().any { it.text?.toString() == "Finding local Macs..." } }
            click("Select Screen Sharing server")
            click(serviceName)
            click("VNC password")
            click("SAVE")
            val profile = checkNotNull(saved.get())
            check(profile.type == ConnectionType.VNC && profile.credentialId == initial.credentialId)
            check(profile.pass.isEmpty() && profile.user.isEmpty())
            return profile
        } finally { main { launcher.finish() }; Thread.sleep(500) }
    }
    private fun discoveryFixture(credentialId: String) {
        val nsd = test.targetContext.getSystemService(android.net.nsd.NsdManager::class.java)
        val registered = CountDownLatch(1)
        val unregistered = CountDownLatch(1)
        val server = Fixture(0)
        var serviceName = ""
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) { serviceName = info.serviceName; registered.countDown() }
            override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) { registered.countDown() }
            override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) { unregistered.countDown() }
            override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(android.net.nsd.NsdServiceInfo().apply {
            this.serviceName = "Farside VNC fixture"; serviceType = "_rfb._tcp."; port = server.port
        }, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            check(registered.await(5, TimeUnit.SECONDS) && serviceName.isNotBlank())
            val found = runBlocking { NetworkScanner.scanLocalNetworkForVnc(test.targetContext) }
            check(found.any { it.name == serviceName && it.port == server.port && it.ip.isNotBlank() }) { "NSD service did not resolve: $found" }
            val profile = chooseService(ConnectionProfile("vnc-verification-profile", "", ConnectionType.VNC,
                "", 5900, "", "", credentialId = credentialId), serviceName)
            check(profile.name == serviceName && profile.host == serviceName && profile.port == server.port)
            check(ConnectionProfile.fromJson(profile.toJson()).host == serviceName)
            val endpoint = runBlocking { NetworkScanner.resolveVncHost(test.targetContext, profile.host, profile.port) }
            check(endpoint == found.single { it.name == serviceName }.ip)
            session(profile, server)
            record("PASS: real NSD fixture selected in actual profile picker; saved Bonjour name resolves again and produces working VNC session (not a Mac)")
        } finally { nsd.unregisterService(listener); server.close() }
        check(unregistered.await(5, TimeUnit.SECONDS))
        val absent = runBlocking { NetworkScanner.scanLocalNetworkForVnc(test.targetContext) }
        check(absent.none { it.name == serviceName }) { "Withdrawn fixture still advertised" }
        record("PASS: withdrawn _rfb._tcp fixture absent on next bounded browse; real Screen-Sharing-off Mac check remains pending")
    }

    private class Fixture(val encoding: Int, private val auth: Int = 2, val sendCursor: Boolean = true) : AutoCloseable {
        private val listener = ServerSocket(0)
        val port = listener.localPort
        val pointers = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        val keys = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val clipboard = CopyOnWriteArrayList<String>()
        val encodings = CopyOnWriteArrayList<Int>()
        val disconnected = CountDownLatch(1)
        @Volatile var stage = "waiting"
        @Volatile var failure: Throwable? = null
        @Volatile private var socket: Socket? = null
        private var output: DataOutputStream? = null
        private val compressor = Deflater()
        private val hexCompressor = Deflater()
        private var formatCount = 0
        private var encodingCount = 0
        private var resetFormatCount = 0
        private var resetEncodingCount = 0
        @Volatile private var resetSize: Pair<Int, Int>? = null
        @Volatile var resetFrames = 0
            private set
        private val worker = Thread({ run() }, "vnc-fixture").apply { start() }
        private fun packed(data: ByteArray, stream: Deflater = compressor): ByteArray {
            stream.setInput(data)
            val out = ByteArray(4096)
            val count = stream.deflate(out, 0, out.size, Deflater.SYNC_FLUSH)
            return out.copyOf(count)
        }
        @Synchronized fun sendClipboard(text: String) {
            output?.apply { writeByte(3); write(byteArrayOf(0, 0, 0)); val data = text.toByteArray(Charsets.ISO_8859_1); writeInt(data.size); write(data); flush() }
        }
        @Synchronized fun cursorUpdate(x: Int, y: Int, hide: Boolean) {
            output!!.apply {
                writeByte(0); writeByte(0); writeShort(1)
                writeShort(x); writeShort(y); writeShort(0); writeShort(0); writeInt(if (hide) -239 else -232); flush()
            }
        }
        @Synchronized fun resetDesktop(width: Int, height: Int) {
            resetFormatCount = formatCount; resetEncodingCount = encodingCount
            resetSize = width to height
            output!!.apply {
                writeByte(0); writeByte(0); writeShort(1)
                writeShort(0); writeShort(0); writeShort(width); writeShort(height); writeInt(-223); flush()
            }
        }
        @Synchronized private fun solidFrame(x: Int, y: Int, width: Int, height: Int, r: Int, g: Int, b: Int) {
            val tiles = ByteArrayOutputStream().apply {
                for (row in 0 until height step 64) for (column in 0 until width step 64) {
                    write(1); write(r); write(g); write(b)
                }
            }.toByteArray()
            val data = packed(tiles)
            output!!.apply {
                writeByte(0); writeByte(0); writeShort(1)
                writeShort(x); writeShort(y); writeShort(width); writeShort(height); writeInt(16)
                writeInt(data.size); write(data); flush()
            }
        }
        fun partialFrame() = solidFrame(50, 30, 12, 12, 20, 220, 80)
        @Synchronized private fun frame() {
            output!!.apply {
                writeByte(0); writeByte(0); writeShort(if (sendCursor) 3 else 1)
                writeShort(0); writeShort(0); writeShort(64); writeShort(48); writeInt(encoding)
                when (encoding) {
                    16 -> {
                        val raw = ByteArrayOutputStream().apply { write(0); repeat(64 * 48) { i -> write(if (i % 64 < 32) 230 else 20); write(70); write(130) } }.toByteArray()
                        val z = packed(raw); writeInt(z.size); write(z)
                    }
                    7 -> { writeByte(128); write(byteArrayOf(230.toByte(), 70, 130.toByte())) }
                    8 -> repeat(12) { tile ->
                        if (tile % 2 == 0) {
                            val raw = ByteArray(16 * 16 * 4) { index -> when (index % 4) { 0 -> 230.toByte(); 1 -> 70; 2 -> 130.toByte(); else -> 0 } }
                            val z = packed(raw); writeByte(32); writeShort(z.size); write(z)
                        } else {
                            // Compressed Hextile body uses its own persistent stream.
                            val z = packed(byteArrayOf(230.toByte(), 70, 130.toByte(), 0, 20, 220.toByte(), 80, 0, 1, 0x44, 0x77), hexCompressor)
                            writeByte(64 or 2 or 4 or 8); writeShort(z.size); write(z)
                        }
                    }
                    5 -> repeat(12) { writeByte(2); write(byteArrayOf(230.toByte(), 70, 130.toByte(), 0)) }
                    2 -> { writeInt(0); write(byteArrayOf(230.toByte(), 70, 130.toByte(), 0)) }
                    0 -> repeat(64 * 48) { write(byteArrayOf(230.toByte(), 70, 130.toByte(), 0)) }
                }
                if (sendCursor) {
                    writeShort(1); writeShort(1); writeShort(3); writeShort(3); writeInt(-239)
                    repeat(9) { write(byteArrayOf(-1, -1, -1, 0)) }; write(byteArrayOf(0x40, 0xe0.toByte(), 0x40))
                    writeShort(8); writeShort(8); writeShort(0); writeShort(0); writeInt(-232)
                }
                flush()
            }
        }
        private fun run() {
            try {
                socket = listener.accept().apply { soTimeout = 20000 }
                val input = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())
                val out = output!!
                out.write("RFB 003.008\n".toByteArray()); out.flush()
                input.readFully(ByteArray(12)); stage = "protocol read"
                out.writeByte(1); out.writeByte(auth); out.flush()
                if (auth != 2) return
                check(input.readUnsignedByte() == 2); stage = "security selected"
                val challenge = ByteArray(16) { it.toByte() }
                out.write(challenge); out.flush()
                val answer = ByteArray(16); input.readFully(answer)
                val reversed = "testpass".toByteArray().map { (Integer.reverse(it.toInt() and 255) ushr 24).toByte() }.toByteArray()
                val cipher = Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(reversed, "DES")) }
                val expected = cipher.doFinal(challenge)
                val okay = answer.contentEquals(expected)
                stage = "fixture auth matches=$okay"
                out.writeInt(if (okay) 0 else 1)
                if (!okay) { out.writeInt(12); out.write("Bad password".toByteArray()); out.flush(); return }
                out.flush(); input.readUnsignedByte()
                out.writeShort(64); out.writeShort(48)
                out.write(byteArrayOf(32, 24, 0, 1)); repeat(3) { out.writeShort(255) }
                out.write(byteArrayOf(0, 8, 16, 0, 0, 0)); out.writeInt(11); out.write("VNC fixture".toByteArray()); out.flush()
                var sentFrame = false
                while (true) when (input.readUnsignedByte()) {
                    0 -> { input.readFully(ByteArray(19)); formatCount++ }
                    2 -> { input.readUnsignedByte(); val count = input.readUnsignedShort(); val offered = List(count) { input.readInt() }; encodings.addAll(offered); check(offered.take(3) == listOf(16, 7, 8)); encodingCount++ }
                    3 -> {
                        val incremental = input.readUnsignedByte() != 0
                        val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                        val width = input.readUnsignedShort(); val height = input.readUnsignedShort()
                        if (!sentFrame) { sentFrame = true; check(encoding in encodings); frame() }
                        else resetSize?.let { size -> if (!incremental) {
                            check(formatCount > resetFormatCount && encodingCount > resetEncodingCount) { "Framebuffer reset omitted format/encoding negotiation" }
                            check(x == 0 && y == 0 && width == size.first && height == size.second)
                            solidFrame(0, 0, width, height, 40, 90, 220)
                            resetSize = null; resetFrames++
                        } }
                    }
                    4 -> { val down = input.readUnsignedByte() != 0; input.readUnsignedShort(); keys.add(input.readInt() to down) }
                    5 -> { val mask = input.readUnsignedByte(); pointers.add(Triple(input.readUnsignedShort(), input.readUnsignedShort(), mask)) }
                    6 -> { input.readFully(ByteArray(3)); val n = input.readInt(); check(n in 0..1048576); val data = ByteArray(n); input.readFully(data); clipboard.add(data.toString(Charsets.ISO_8859_1)) }
                    else -> error("Unexpected RFB message")
                }
            } catch (_: EOFException) { } catch (_: java.net.SocketException) { }
            catch (error: Throwable) { failure = error }
            finally { socket?.close(); disconnected.countDown() }
        }
        override fun close() { listener.close(); socket?.close(); worker.join(3000); compressor.end(); hexCompressor.end() }
    }
}
