package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Exercises actual Compose navigation using Android accessibility actions; no Horizon gestures. */
class CredentialUiVerification(private val instrumentation: Instrumentation) {
    private val automation get() = instrumentation.uiAutomation

    fun verifyLiveDeletion() {
        val manager = com.farside.workspace.data.ProfileManager(instrumentation.targetContext)
        val original = manager.getProfiles().first { it.type == com.farside.workspace.data.ConnectionType.RDP }
        val credential = com.farside.workspace.data.SavedCredential(id = java.util.UUID.randomUUID().toString(),
            name = "Credential deletion verification", user = original.user, pass = original.pass)
        val profile = original.copy(id = java.util.UUID.randomUUID().toString(), name = "Credential deletion verification",
            credentialId = credential.id, enableCamera = false, redirectMicrophone = false)
        var activity: SessionActivity? = null
        try {
            manager.addCredential(credential); manager.addProfile(profile)
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", profile.id); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as SessionActivity
            val deadline = android.os.SystemClock.uptimeMillis()+25000
            while(activity.rdpInstance == 0L && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(200)
            check(activity.rdpInstance != 0L) { "No live session to invalidate" }
            manager.deleteCredential(credential.id)
            while(!activity.isDestroyed && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
            check(activity.isDestroyed) { "Deleting a credential left its live session open" }
            val invalid = manager.getProfiles().single { it.id == profile.id }
            check(invalid.credentialId == credential.id && invalid.user.isEmpty() && invalid.pass.isEmpty())
            val rejected = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", profile.id); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as SessionActivity
            Thread.sleep(1000)
            check(rejected.isFinishing || rejected.isDestroyed)
            java.io.File(instrumentation.targetContext.cacheDir,"credential-deletion-result.json")
                .writeText("{\"success\":true,\"liveSessionClosed\":true,\"reconnectRejected\":true}")
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            manager.deleteProfile(profile.id); manager.deleteCredential(credential.id)
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { index ->
            node.getChild(index)?.let(::descendants).orEmpty()
        }

    private fun find(label: String, editable: Boolean = false): AccessibilityNodeInfo {
        repeat(60) {
            automation.clearCache()
            val displays = automation.windowsOnAllDisplays
            val nodes = (0 until displays.size()).flatMap { index ->
                displays.valueAt(index).flatMap { it.root?.let(::descendants).orEmpty() }
            }
            val matches = nodes.filter { it.text?.toString() == label || it.hintText?.toString() == label || it.contentDescription?.toString() == label ||
                label == "Password" && it.isEditable && it.isPassword }
            if (!editable) matches.firstOrNull { it.isClickable }?.let { return it }
            for (match in matches) {
                if (!editable) return match
                var candidate: AccessibilityNodeInfo? = match
                while (candidate != null && !candidate.isEditable) candidate = candidate.parent
                candidate?.let { return it }
            }
            if (it > 0 && it % 5 == 0) {
                val action = if (it % 30 < 20) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                nodes.firstOrNull { it.isScrollable && it.packageName == instrumentation.targetContext.packageName }
                    ?.performAction(action)
            }
            Thread.sleep(200)
        }
        error("Missing UI control: $label")
    }

    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = checkNotNull(node.parent) { "Not clickable: $label" }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(400)
    }

    private fun enter(label: String, value: String) {
        val node = find(label, editable = true)
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        Thread.sleep(200)
    }

    private fun capture(activity: android.app.Activity, filename: String = "credential-edit.png") {
        var window = activity.window
        if (filename == "credential-pin-prompt.png") instrumentation.runOnMainSync {
            fun dialog(view: android.view.View): android.view.Window? {
                if (view is androidx.compose.ui.window.DialogWindowProvider) return view.window
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) dialog(view.getChildAt(i))?.let { return it }
                return null
            }
            window = checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::dialog))
        }
        val bitmap = android.graphics.Bitmap.createBitmap(window.decorView.width,
            window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
        val copied = java.util.concurrent.CountDownLatch(1)
        var result = -1
        instrumentation.runOnMainSync {
            android.view.PixelCopy.request(window, bitmap, { status -> result = status; copied.countDown() },
                android.os.Handler(android.os.Looper.getMainLooper()))
        }
        check(copied.await(5, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
        java.io.File(instrumentation.targetContext.cacheDir, filename).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun verifyEmptyPasswordInput(activity: android.app.Activity) {
        click("Password")
        var checked = false
        instrumentation.runOnMainSync {
            fun inspect(view: android.view.View) {
                if (view.onCheckIsTextEditor()) {
                    val info = android.view.inputmethod.EditorInfo()
                    val connection = view.onCreateInputConnection(info)
                    if (connection != null) {
                        check(info.inputType and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                        check(connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text.isNullOrEmpty())
                        checked = true
                    }
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
            }
            inspect(activity.window.decorView)
        }
        check(checked) { "Password InputConnection was not inspected" }
    }

    private fun verifyStorage(prefs: android.content.SharedPreferences) {
        for (key in listOf("credentials", "profiles")) {
            val array = org.json.JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                check(!json.has("pass") && !json.has("proxyPass"))
                if (key == "profiles" && !json.isNull("credentialId") && json.has("credentialId")) {
                    check(!json.has("user") && !json.has("passEncrypted")) { "Linked profile copied credential data" }
                } else check(json.has("passEncrypted"))
            }
        }
        // Flush pending writes before inspecting the actual preferences file, not just memory.
        check(prefs.edit().commit())
        val file = java.io.File(instrumentation.targetContext.applicationInfo.dataDir, "shared_prefs/farside_profiles.xml")
        val contents = file.readText()
        check(listOf("migration-password", "migration-proxy-password", "fixture-password", "updated-password").none { it in contents })
    }

    private fun verifyMigration(prefs: android.content.SharedPreferences) {
        val credential = org.json.JSONObject().put("id", "migration-test").put("name", "Migration")
            .put("user", "migration-user").put("pass", "migration-password")
        val profile = com.farside.workspace.data.ConnectionProfile("migration-profile", "Migration",
            com.farside.workspace.data.ConnectionType.RDP, "127.0.0.1", 3389, "migration-user", "migration-password").toJson()
        profile.remove("passEncrypted")
        profile.put("pass", "migration-password").put("proxyPass", "migration-proxy-password")
        check(prefs.edit().putString("credentials", org.json.JSONArray().put(credential).toString())
            .putString("profiles", org.json.JSONArray().put(profile).toString()).commit())
        val manager = com.farside.workspace.data.ProfileManager(instrumentation.targetContext)
        check(manager.getCredentials().single().pass == "migration-password")
        check(manager.getProfiles().single().let { it.pass == "migration-password" && it.proxyPass == "migration-proxy-password" })
        verifyStorage(prefs)
        val first = manager.getCredentials().single().toJson().getString("passEncrypted")
        val second = manager.getCredentials().single().toJson().getString("passEncrypted")
        check(first != second) { "Encryption must use a random IV" }
        val damaged = android.util.Base64.decode(first, android.util.Base64.NO_WRAP)
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        check(runCatching { CredentialManager.decrypt(damaged) }.isFailure) { "Tampered ciphertext accepted" }
        check(prefs.edit().putString("credentials", "[]").putString("profiles", "[]").commit())
    }

    private fun awaitCheck(check: () -> Boolean) {
        repeat(100) { if (check()) return; Thread.sleep(200) }
        error("Timed out waiting for credential operation")
    }

    private fun awaitDialogDismissed() {
        awaitCheck {
            automation.clearCache()
            val displays = automation.windowsOnAllDisplays
            val nodes = (0 until displays.size()).flatMap { index ->
                displays.valueAt(index).flatMap { it.root?.let(::descendants).orEmpty() }
            }
            nodes.none { it.text?.toString() in listOf("Unlock credential", "Unlock connection", "Remove PIN requirement", "Remove App PIN", "Change App PIN") }
        }
    }

    private fun verifyPin(activity: android.app.Activity, manager: com.farside.workspace.data.ProfileManager,
                          credentialId: String, profileId: String) {
        click("Settings")
        click("Credentials")
        click("Edit")
        // 1. Toggling Require PIN without App PIN set should show an error
        click("Require PIN")
        find("Set an App PIN in Tunnels & Global settings first.")
        click("Cancel Edit")

        // 2. Go to Tunnels & Global and set App PIN
        click("Tunnels & Global")
        enter("New PIN (4–12 digits)", "246810")
        enter("Confirm PIN", "135790")
        click("Save PIN")
        find("PINs do not match.")
        enter("Confirm PIN", "246810")
        capture(activity, "credential-pin-setup.png")
        click("Save PIN")
        awaitCheck { manager.getAppPinVerifier() != null }
        val verifier = manager.getAppPinVerifier()!!
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "App PIN setup and confirmation passed.\n") })
        check(CredentialPin.matches("246810", verifier))
        check(!CredentialPin.matches("000000", verifier))
        val prefs = instrumentation.targetContext.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        check("246810" !in prefs.getString("app_pinVerifier", "")!!)
        find("App PIN active")

        // 3. Go to Credentials and enable Require PIN on the credential
        click("Credentials")
        click("Edit")
        click("Require PIN")
        click("Save Changes")
        awaitCheck { manager.getCredentials().single { it.id == credentialId }.requirePin }
        find("PIN required")

        // 4. Test SessionActivity gate: PIN required to connect
        click("Back")
        fun session() = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SessionActivity::class.java)
            .putExtra("profileId", profileId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
        var session = session()
        try {
            find("Unlock connection")
            check(session.rdpInstance == 0L)
            enter("PIN", "000000")
            click("Unlock")
            find("Incorrect PIN.")
            check(session.rdpInstance == 0L)
            click("Cancel")
            awaitCheck { session.isFinishing || session.isDestroyed }
            session = session()
            find("Unlock connection")
            capture(session, "credential-pin-prompt.png")
            enter("PIN", "246810")
            click("Unlock")
            find("Connection failed")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Session PIN gate, rejection, cancellation and unlock passed.\n") })
        } finally { instrumentation.runOnMainSync { session.finish() } }
        Thread.sleep(500)

        // 5. Backoff throttling on App PIN
        val now = System.currentTimeMillis()
        val currentVerifier = manager.getAppPinVerifier()!!
        repeat(5) { check(CredentialPin.unlock(instrumentation.targetContext, currentVerifier, "000000", now) != null) }
        check(CredentialPin.unlock(instrumentation.targetContext, currentVerifier, "246810", now + 1) != null)
        check(CredentialPin.unlock(instrumentation.targetContext, currentVerifier, "246810", now + 30_001) == null)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Persistent backoff passed.\n") })

        // 6. Disable Require PIN on the credential (prompts for App PIN)
        click("Settings")
        click("Credentials")
        click("Edit")
        click("Require PIN")
        find("Remove PIN requirement")
        enter("PIN", "000000")
        click("Unlock")
        find("Incorrect PIN.")
        click("Cancel")
        check(manager.getCredentials().single { it.id == credentialId }.requirePin)
        click("Require PIN")
        find("Remove PIN requirement")
        enter("PIN", "246810")
        click("Unlock")
        awaitDialogDismissed()
        click("Save Changes")
        awaitCheck { !manager.getCredentials().single { it.id == credentialId }.requirePin }
        click("Back")

        // 7. Verify session connects without PIN when requirePin is false
        session = session()
        try {
            find("Connection failed")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Unprotected credential connects without PIN prompt.\n") })
        } finally { instrumentation.runOnMainSync { session.finish() } }
        capture(activity)
    }

    fun run() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val prefs = instrumentation.targetContext.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        val original = prefs.getString("credentials", null)
        val originalProfiles = prefs.getString("profiles", null)
        val originalPinKeys = prefs.all.keys.filter { it.contains("pin-") || it.contains("Pin") }.toSet()
        val originalAppPin = prefs.getString("app_pinVerifier", null)
        val placementPrefs = instrumentation.targetContext.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
        val originalPlacementKeys = placementPrefs.all.keys.toSet()
        check(prefs.edit().remove("app_pinVerifier").putString("credentials", "[]").putString("profiles", "[]").commit())
        var activity: android.app.Activity? = null
        try {
            verifyMigration(prefs)
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("Add")
            click("Select Saved Credential")
            click("+ Add New Credential")
            enter("Account Display Name", "Credential regression")
            enter("Username (optional for VNC)", "regression-user")
            enter("Password", "fixture-password")
            click("Save Credential")
            check(com.farside.workspace.data.ProfileManager(instrumentation.targetContext).getCredentials()
                .single().name == "Credential regression")
            click("Back")
            click("Select Saved Credential")
            click("Credential regression")
            find("Credential regression")
            // The form stays alive across Settings; its selected credential must survive another return.
            click("Credential regression")
            click("+ Add New Credential")
            val manager = com.farside.workspace.data.ProfileManager(instrumentation.targetContext)
            val credential = manager.getCredentials().single()
            val legacy = com.farside.workspace.data.ConnectionProfile(
                id = "legacy-credential-test", name = "Legacy", type = com.farside.workspace.data.ConnectionType.RDP,
                host = "127.0.0.1", port = 3389, user = credential.user, pass = credential.pass)
            manager.saveProfiles(listOf(legacy, legacy.copy(id = "linked-credential-test", credentialId = credential.id),
                legacy.copy(id = "unrelated-test", user = "different-user")))
            click("Edit")
            verifyEmptyPasswordInput(activity)
            enter("Credential regression", "Renamed credential")
            enter("regression-user", "updated-user")
            enter("Password", "updated-password")
            capture(activity)
            click("Save Changes")
            val edited = manager.getCredentials().single()
            check(edited.id == credential.id && edited.name == "Renamed credential")
            val reloaded = com.farside.workspace.data.ProfileManager(instrumentation.targetContext).getProfiles()
            for (id in listOf("legacy-credential-test", "linked-credential-test")) {
                val profile = reloaded.singleOrNull { it.id == id } ?: error("Missing edited fixture profile: $id")
                check(profile.credentialId == credential.id && profile.user == "updated-user" && profile.pass == "updated-password")
            }
            check(reloaded.single { it.id == "unrelated-test" }.user == "different-user")
            verifyStorage(prefs)
            // Rename-only edits preserve the password without ever putting it in the editor.
            click("Edit")
            enter("Renamed credential", "Rename without password")
            click("Save Changes")
            check(manager.getCredentials().single().pass == edited.pass)
            click("Edit")
            enter("Rename without password", "Renamed credential")
            click("Save Changes")
            // Cancelling an edit leaves the credential intact.
            click("Edit")
            enter("Renamed credential", "Discarded rename")
            click("Cancel Edit")
            check(manager.getCredentials().single() == edited)
            click("Back")
            find("Renamed credential")
            enter("Hostname or IP address", "127.0.0.1")
            enter("Port (RDP 3389 / SSH 22 / VNC 5900)", "3389")
            click("SAVE")
            awaitCheck { manager.getProfiles().any { it.id !in listOf("legacy-credential-test", "linked-credential-test", "unrelated-test") } }
            val saved = manager.getProfiles().single { it.id !in listOf("legacy-credential-test", "linked-credential-test", "unrelated-test") }
            check(saved.credentialId == credential.id && saved.user == edited.user && saved.pass == edited.pass)
            // Two credentials can share identical login data; an explicit ID must remain authoritative.
            manager.addCredential(edited.copy(id = "duplicate-login-test", name = "Duplicate"))
            manager.updateCredential(edited.copy(name = "Second rename", user = "final-user", pass = "final-password"))
            check(manager.getProfiles().single { it.id == saved.id }.let {
                it.credentialId == credential.id && it.user == "final-user" && it.pass == "final-password"
            })
            verifyPin(activity, manager, credential.id, saved.id)
            manager.deleteCredential(credential.id)
            val invalidated = manager.getProfiles().filter { it.credentialId == credential.id }
            check(invalidated.size == 3 && invalidated.all { it.user.isEmpty() && it.pass.isEmpty() })
            // Saving a stale in-memory profile cannot resurrect a deleted credential.
            manager.updateProfile(saved)
            check(manager.getProfiles().single { it.id == saved.id }.let { it.user.isEmpty() && it.pass.isEmpty() })
            check(manager.getProfiles().single { it.id == "unrelated-test" }.user == "different-user")
            verifyStorage(prefs)
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            val editor = prefs.edit()
            prefs.all.keys.filter { (it.contains("pin-") || it.contains("Pin")) && it !in originalPinKeys }.forEach { editor.remove(it) }
            if (originalAppPin == null) editor.remove("app_pinVerifier") else editor.putString("app_pinVerifier", originalAppPin)
            if (original == null) editor.remove("credentials") else editor.putString("credentials", original)
            if (originalProfiles == null) editor.remove("profiles") else editor.putString("profiles", originalProfiles)
            check(editor.commit())
            val placementEditor = placementPrefs.edit()
            placementPrefs.all.keys.filter { it !in originalPlacementKeys }.forEach { placementEditor.remove(it) }
            check(placementEditor.commit())
            // Reencrypt any legacy data restored by the fixture.
            com.farside.workspace.data.ProfileManager(instrumentation.targetContext)
        }
    }
}
