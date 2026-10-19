package com.farside.workspace

import android.app.Instrumentation
import com.farside.workspace.data.BackupManager
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.SavedCredential
import com.farside.workspace.data.SavedSshKey
import java.io.File
import java.util.UUID
import javax.crypto.AEADBadTagException

class BackupRestoreVerification(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext

    fun run(): String {
        val pm = ProfileManager(context)

        // Snapshot original state
        val origProfiles = pm.getProfiles()
        val origCredentials = pm.getCredentials()
        val origSshKeys = pm.getSshKeys()
        val origPin = pm.getAppPinVerifier()
        val origSystemPin = pm.usesSystemPin()

        try {
            // Setup known test fixtures
            val testCredId = UUID.randomUUID().toString()
            val testCred = SavedCredential(
                id = testCredId,
                name = "Backup Test Cred",
                user = "testuser",
                pass = "SecretP@ssword123!",
                requirePin = true
            )
            pm.addCredential(testCred)

            val testKeyId = UUID.randomUUID().toString()
            val testKey = SavedSshKey(
                id = testKeyId,
                name = "Backup Test Key",
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nMOCKKEYDATA\n-----END OPENSSH PRIVATE KEY-----",
                passphrase = "keypassphrase456",
                requirePin = true
            )
            pm.addSshKey(testKey)

            val testProfileId = UUID.randomUUID().toString()
            val testProfile = ConnectionProfile(
                id = testProfileId,
                name = "Backup Test Profile",
                type = ConnectionType.RDP,
                host = "10.0.0.99",
                port = 3389,
                user = "testuser",
                pass = "SecretP@ssword123!",
                credentialId = testCredId,
                cloudflaredParameters = "--service-token-id test-token --service-token-secret test-secret",
                proxyPass = "proxypassword789"
            )
            pm.addProfile(testProfile)

            val testPinVerifier = CredentialPin.create("987654")
            pm.setAppPinVerifier(testPinVerifier)

            // Test 1: Round-trip payload serialization and authenticated encryption
            val payload = BackupManager.createPayload(pm)
            val json = BackupManager.payloadToJson(payload)
            val passphrase = "SuperStrongPassphrase#2026".toCharArray()
            val encryptedBytes = BackupManager.encryptPayload(json.toString(), passphrase)

            check(encryptedBytes.isNotEmpty()) { "Encrypted backup bytes must not be empty" }
            check(encryptedBytes.take(4).toByteArray().toString(Charsets.US_ASCII) == "FSBK") {
                "Encrypted backup must have FSBK header magic"
            }

            // Test 2: Decrypt with correct passphrase
            val decryptedJsonStr = BackupManager.decryptPayload(encryptedBytes, passphrase)
            val restoredPayload = BackupManager.jsonToPayload(org.json.JSONObject(decryptedJsonStr))

            val restoredCred = restoredPayload.credentials.find { it.id == testCredId }
            check(restoredCred != null) { "Restored payload missing credential" }
            check(restoredCred.pass == "SecretP@ssword123!") { "Decrypted credential password mismatch" }

            val restoredKey = restoredPayload.sshKeys.find { it.id == testKeyId }
            check(restoredKey != null) { "Restored payload missing SSH key" }
            check(restoredKey.privateKey.contains("MOCKKEYDATA")) { "Decrypted SSH key mismatch" }
            check(restoredKey.passphrase == "keypassphrase456") { "Decrypted SSH passphrase mismatch" }

            val restoredProf = restoredPayload.profiles.find { it.id == testProfileId }
            check(restoredProf != null) { "Restored payload missing profile" }
            check(restoredProf.cloudflaredParameters == "--service-token-id test-token --service-token-secret test-secret") {
                "Decrypted cloudflaredParameters mismatch"
            }
            check(restoredProf.proxyPass == "proxypassword789") { "Decrypted proxyPass mismatch" }

            check(restoredPayload.appPinVerifier == testPinVerifier) { "Restored PIN verifier mismatch" }

            // Test 3: Tamper / wrong passphrase rejection
            val wrongPass = "WrongPassphrase".toCharArray()
            var wrongPassRejected = false
            try {
                BackupManager.decryptPayload(encryptedBytes, wrongPass)
            } catch (e: AEADBadTagException) {
                wrongPassRejected = true
            } catch (e: Exception) {
                wrongPassRejected = true
            }
            check(wrongPassRejected) { "Wrong passphrase must be rejected by AEAD" }

            // Test 4: File I/O round-trip
            val backupFile = File(context.cacheDir, "test_backup.fsbk")
            backupFile.writeBytes(encryptedBytes)
            val fileBytes = backupFile.readBytes()
            val fileDecrypted = BackupManager.decryptPayload(fileBytes, passphrase)
            check(fileDecrypted.isNotEmpty()) { "Decrypted file payload should not be empty" }
            backupFile.delete()

            // Test 5: Re-encryption into ProfileManager
            val importResult = BackupManager.restorePayload(pm, restoredPayload)
            check(importResult.credentialsRestored >= 1) { "Import result must indicate credentials restored" }
            check(importResult.sshKeysRestored >= 1) { "Import result must indicate ssh keys restored" }
            check(importResult.profilesRestored >= 1) { "Import result must indicate profiles restored" }
            check(importResult.pinRestored) { "Import result must indicate pin restored" }

            // Verify live ProfileManager re-encrypted data
            val liveCred = pm.getCredentials().first { it.id == testCredId }
            check(liveCred.pass == "SecretP@ssword123!") { "Re-encrypted credential password could not be read" }

            val liveKey = pm.getSshKeys().first { it.id == testKeyId }
            check(liveKey.passphrase == "keypassphrase456") { "Re-encrypted SSH key could not be read" }

            val liveProf = pm.getProfiles().first { it.id == testProfileId }
            check(liveProf.cloudflaredParameters == "--service-token-id test-token --service-token-secret test-secret") {
                "Re-encrypted cloudflaredParameters could not be read"
            }

            return "PASS: authenticated encrypted backup/restore, AEAD rejection, Keystore re-encryption verified."
        } finally {
            // Restore original state cleanly
            pm.saveProfiles(origProfiles)
            pm.saveCredentials(origCredentials)
            pm.saveSshKeys(origSshKeys)
            pm.setAppPinVerifier(origPin)
            if (origSystemPin) pm.enableSystemPin()
        }
    }
}
