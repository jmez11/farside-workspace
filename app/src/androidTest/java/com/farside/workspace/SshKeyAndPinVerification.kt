package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.SavedSshKey
import java.io.File

class SshKeyAndPinVerification(private val instrumentation: Instrumentation) {
    private val automation get() = instrumentation.uiAutomation

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { index ->
            node.getChild(index)?.let(::descendants).orEmpty()
        }

    private fun find(label: String, editable: Boolean = false, retries: Int = 40): AccessibilityNodeInfo {
        val appPackage = instrumentation.targetContext.packageName
        repeat(retries) {
            automation.clearCache()
            val displays = automation.windowsOnAllDisplays
            val nodes = (0 until displays.size()).flatMap { index ->
                displays.valueAt(index).flatMap { it.root?.let(::descendants).orEmpty() }
            }
            val appNodes = nodes.filter { it.packageName == appPackage }
            val matches = appNodes.filter {
                it.text?.toString().equals(label, ignoreCase = true) ||
                it.hintText?.toString().equals(label, ignoreCase = true) ||
                it.contentDescription?.toString().equals(label, ignoreCase = true) ||
                (label == "Private Key" && it.isEditable) ||
                (label == "Passphrase (Optional)" && it.isEditable && it.isPassword) ||
                (label == "PIN" && it.isEditable && it.isPassword)
            }
            if (!editable) matches.firstOrNull { it.isClickable }?.let { return it }
            for (match in matches) {
                if (!editable) return match
                var candidate: AccessibilityNodeInfo? = match
                while (candidate != null && !candidate.isEditable) candidate = candidate.parent
                candidate?.let { return it }
            }
            if (it > 0 && it % 5 == 0) {
                val action = if (it % 20 < 10) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                appNodes.firstOrNull { it.isScrollable }?.performAction(action)
            }
            Thread.sleep(200)
        }
        error("Missing UI control: $label")
    }

    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = checkNotNull(node.parent) { "Not clickable: $label" }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(300)
    }

    private fun enter(label: String, value: String) {
        val node = find(label, editable = true)
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        Thread.sleep(200)
    }

    private fun awaitCheck(check: () -> Boolean) {
        repeat(50) { if (check()) return; Thread.sleep(200) }
        error("Timed out waiting for condition")
    }

    fun run() {
        Log.i("SshKeyAndPinVerification", "Starting verification")
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrumentation.targetContext
        val manager = ProfileManager(context)

        // Clean up any stale test fixtures from prior runs
        manager.deleteSshKey("test-key-id-123")
        manager.deleteProfile("test-ssh-profile-key")

        // 1. In-memory PIN Cache Security & Expiration Verification
        Log.i("SshKeyAndPinVerification", "Step 1: In-memory PIN Cache")
        CredentialPin.clearCache()
        val pin = "357924"
        val verifier = CredentialPin.create(pin)
        manager.setAppPinVerifier(verifier)

        check(!CredentialPin.hasValidCachedPin(verifier)) { "Initial cache should be empty" }
        CredentialPin.cachePin(pin, durationMs = 300_000L)
        check(CredentialPin.hasValidCachedPin(verifier)) { "Cache should be valid for correct verifier" }
        check(!CredentialPin.hasValidCachedPin("v1:invalid:verifier")) { "Cache should reject invalid verifier" }

        // Test expired cache
        CredentialPin.cachePin(pin, durationMs = -100L)
        check(!CredentialPin.hasValidCachedPin(verifier)) { "Expired cache must not be valid" }

        // Test clearCache
        CredentialPin.cachePin(pin, durationMs = 300_000L)
        CredentialPin.clearCache()
        check(!CredentialPin.hasValidCachedPin(verifier)) { "Cleared cache must not be valid" }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "In-memory PIN cache verification passed.\n") })

        // 2. SavedSshKey Data Layer & AES-GCM Keystore Encryption Verification
        Log.i("SshKeyAndPinVerification", "Step 2: SavedSshKey encryption")
        val originalKey = SavedSshKey(
            id = "test-key-id-123",
            name = "Production Server Key",
            privateKey = "-----BEGIN RSA PRIVATE KEY-----\nMIIEogIBAAKCAQEAzSecretData12345\n-----END RSA PRIVATE KEY-----",
            passphrase = "UltraSecretPassphrase99",
            requirePin = true
        )
        manager.addSshKey(originalKey)
        val loadedKey = checkNotNull(manager.getSshKeys().find { it.id == originalKey.id })
        check(loadedKey.name == originalKey.name)
        check(loadedKey.privateKey == originalKey.privateKey)
        check(loadedKey.passphrase == originalKey.passphrase)
        check(loadedKey.requirePin == originalKey.requirePin)

        // Verify storage on disk has no plaintext private key or passphrase
        val prefs = context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        val sshKeysJson = prefs.getString("ssh_keys", "[]")!!
        check("MIIEogIBAAKCAQEAzSecretData12345" !in sshKeysJson) { "Private key must not appear as plaintext in prefs" }
        check("UltraSecretPassphrase99" !in sshKeysJson) { "Passphrase must not appear as plaintext in prefs" }
        check("privateKeyEncrypted" in sshKeysJson) { "Private key must be encrypted" }
        check("passphraseEncrypted" in sshKeysJson) { "Passphrase must be encrypted" }

        check(prefs.edit().commit())
        val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/farside_profiles.xml")
        val fileContent = prefsFile.readText()
        check("MIIEogIBAAKCAQEAzSecretData12345" !in fileContent) { "Plaintext key found in XML file" }
        check("UltraSecretPassphrase99" !in fileContent) { "Plaintext passphrase found in XML file" }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SavedSshKey Keystore encryption verification passed.\n") })

        // 3. ConnectionProfile sshKeyId & Optional Credentials
        Log.i("SshKeyAndPinVerification", "Step 3: Profile sshKeyId and optional credentials")
        val sshProfile = ConnectionProfile(
            id = "test-ssh-profile-key",
            name = "My SSH Server",
            type = ConnectionType.SSH,
            host = "ssh.internal.net",
            port = 22,
            user = "", // No user required!
            pass = "", // No pass required!
            sshKeyId = originalKey.id
        )
        manager.addProfile(sshProfile)
        val loadedProfile = checkNotNull(manager.getProfiles().find { it.id == sshProfile.id })
        check(loadedProfile.sshKeyId == originalKey.id)
        check(loadedProfile.user == "")
        check(loadedProfile.pass == "")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "ConnectionProfile SSH key association and optional credentials passed.\n") })

        // 4. UI Verification inside MainActivity
        Log.i("SshKeyAndPinVerification", "Step 4: Settings SSH Keys panel & PIN lock UI")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            click("Settings")
            click("SSH Keys")
            find("Production Server Key")
            find("PIN required")

            // Edit key and toggle "Require PIN" off -> triggers PIN dialog
            click("Edit")
            click("Require PIN")
            find("Remove PIN requirement")

            // Verify "Don't reprompt for 5 minutes" checkbox exists
            val checkbox = find("Don't reprompt for 5 minutes")
            checkNotNull(checkbox) { "Missing 'Don't reprompt for 5 minutes' checkbox in dialog" }

            // Enter wrong PIN
            enter("PIN", "000000")
            click("Unlock")
            find("Incorrect PIN.")
            check(!CredentialPin.hasValidCachedPin(verifier))

            // Check "Don't reprompt for 5 minutes"
            click("Don't reprompt for 5 minutes")

            // Enter correct PIN
            enter("PIN", pin)
            click("Unlock")

            // Cache must now be populated for 5 minutes
            awaitCheck { CredentialPin.hasValidCachedPin(verifier) }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PIN unlock and 5-minute cache population passed.\n") })

            // Save key change
            click("Save Changes")
            awaitCheck { !manager.getSshKeys().first { it.id == originalKey.id }.requirePin }
            Thread.sleep(500)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SSH Key Require PIN toggle and update passed.\n") })

            // Test 5-minute cache bypass: re-enable Require PIN and toggle off again
            click("Edit")
            click("Require PIN")
            click("Save Changes")
            awaitCheck { manager.getSshKeys().first { it.id == originalKey.id }.requirePin }
            Thread.sleep(500)

            // With PIN cached, removing PIN requirement bypasses prompt immediately
            click("Edit")
            click("Require PIN")
            // Because PIN is cached in memory, CredentialPinDialog automatically unlocks!
            // Wait for Require PIN to be disabled without prompt
            Thread.sleep(800)
            click("Save Changes")
            awaitCheck { !manager.getSshKeys().first { it.id == originalKey.id }.requirePin }
            Thread.sleep(500)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "5-minute PIN cache bypass passed.\n") })

            // Test clearCache re-enables PIN prompt
            CredentialPin.clearCache()
            check(!CredentialPin.hasValidCachedPin(verifier))
            click("Edit")
            click("Require PIN")
            click("Save Changes")
            awaitCheck { manager.getSshKeys().first { it.id == originalKey.id }.requirePin }
            Thread.sleep(500)

            click("Edit")
            click("Require PIN")
            find("Remove PIN requirement")
            click("Cancel")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PIN cache invalidation prompts again passed.\n") })

            // 5. Connection Profile Editor: Verify SSH Key dropdown selector and optional credentials
            Log.i("SshKeyAndPinVerification", "Step 5: Profile editor SSH key selection & optional credentials")
            click("Back")
            Thread.sleep(600)
            click("Add")
            Thread.sleep(600)
            click("SSH")
            Thread.sleep(600)
            find("User Account (Optional for SSH)")
            find("SSH Key")
            click("None (Password / Interactive)")
            Thread.sleep(400)
            find("Production Server Key")
            click("Production Server Key")
            Thread.sleep(400)
            find("Production Server Key")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Profile editor SSH key selection and optional credentials UI passed.\n") })
            click("Close")

            // Clean up test data
            manager.deleteProfile(sshProfile.id)
            manager.deleteSshKey(originalKey.id)
            manager.setAppPinVerifier(null)
            Log.i("SshKeyAndPinVerification", "All verification steps passed successfully.")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
