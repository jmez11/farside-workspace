package com.farside.workspace.data

import android.content.Context
import android.net.Uri
import com.farside.workspace.CredentialPin
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupManager {
    private const val HEADER_MAGIC = "FSBK"
    private const val FORMAT_VERSION = 1
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KDF_ITERATIONS = 200_000
    private const val KEY_LEN_BITS = 256
    private const val SALT_LEN_BYTES = 16
    private const val IV_LEN_BYTES = 12
    private const val TAG_LEN_BITS = 128
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private val AAD_BYTES = "com.farside.workspace:backup:v1".toByteArray(StandardCharsets.UTF_8)

    data class ExportPayload(
        val profiles: List<ConnectionProfile>,
        val credentials: List<SavedCredential>,
        val sshKeys: List<SavedSshKey>,
        val appPinVerifier: String?,
        val systemPinEnabled: Boolean
    )

    data class ImportResult(
        val profilesRestored: Int,
        val credentialsRestored: Int,
        val sshKeysRestored: Int,
        val pinRestored: Boolean
    )

    fun createPayload(profileManager: ProfileManager): ExportPayload {
        return ExportPayload(
            profiles = profileManager.getProfiles(),
            credentials = profileManager.getCredentials(),
            sshKeys = profileManager.getSshKeys(),
            appPinVerifier = profileManager.getAppPinVerifier(),
            systemPinEnabled = profileManager.usesSystemPin()
        )
    }

    fun payloadToJson(payload: ExportPayload): JSONObject {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("timestamp", System.currentTimeMillis())

        val profilesArr = JSONArray()
        payload.profiles.forEach { profilesArr.put(it.toJson()) }
        root.put("profiles", profilesArr)

        val credsArr = JSONArray()
        payload.credentials.forEach { credsArr.put(it.toJson()) }
        root.put("credentials", credsArr)

        val keysArr = JSONArray()
        payload.sshKeys.forEach { keysArr.put(it.toJson()) }
        root.put("ssh_keys", keysArr)

        if (payload.appPinVerifier != null) {
            root.put("app_pinVerifier", payload.appPinVerifier)
        }
        root.put("system_pin_enabled", payload.systemPinEnabled)

        return root
    }

    fun jsonToPayload(json: JSONObject): ExportPayload {
        val profilesList = mutableListOf<ConnectionProfile>()
        val profilesArr = json.optJSONArray("profiles") ?: JSONArray()
        for (i in 0 until profilesArr.length()) {
            profilesList.add(ConnectionProfile.fromJson(profilesArr.getJSONObject(i)))
        }

        val credsList = mutableListOf<SavedCredential>()
        val credsArr = json.optJSONArray("credentials") ?: JSONArray()
        for (i in 0 until credsArr.length()) {
            credsList.add(SavedCredential.fromJson(credsArr.getJSONObject(i)))
        }

        val keysList = mutableListOf<SavedSshKey>()
        val keysArr = json.optJSONArray("ssh_keys") ?: JSONArray()
        for (i in 0 until keysArr.length()) {
            keysList.add(SavedSshKey.fromJson(keysArr.getJSONObject(i)))
        }

        val pinVerifier = if (json.has("app_pinVerifier") && !json.isNull("app_pinVerifier")) {
            json.getString("app_pinVerifier")
        } else null

        val sysPin = json.optBoolean("system_pin_enabled", false)

        return ExportPayload(
            profiles = profilesList,
            credentials = credsList,
            sshKeys = keysList,
            appPinVerifier = pinVerifier,
            systemPinEnabled = sysPin
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_LEN_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encryptPayload(payloadJson: String, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val salt = ByteArray(SALT_LEN_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN_BYTES).also { SecureRandom().nextBytes(it) }

        val secretKey = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(AAD_BYTES)

        val plaintextBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintextBytes)

        val magicBytes = HEADER_MAGIC.toByteArray(StandardCharsets.US_ASCII)
        val headerLen = magicBytes.size + 1 + 4 + SALT_LEN_BYTES + IV_LEN_BYTES
        val totalSize = headerLen + ciphertext.size

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(magicBytes)
        buffer.put(FORMAT_VERSION.toByte())
        buffer.putInt(KDF_ITERATIONS)
        buffer.put(salt)
        buffer.put(iv)
        buffer.put(ciphertext)

        return buffer.array()
    }

    fun decryptPayload(data: ByteArray, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val magicBytes = HEADER_MAGIC.toByteArray(StandardCharsets.US_ASCII)
        val minHeaderLen = magicBytes.size + 1 + 4 + SALT_LEN_BYTES + IV_LEN_BYTES
        require(data.size > minHeaderLen) { "Invalid backup file: file too short" }

        val buffer = ByteBuffer.wrap(data)
        val readMagic = ByteArray(magicBytes.size)
        buffer.get(readMagic)
        require(readMagic.contentEquals(magicBytes)) { "Invalid backup file format" }

        val version = buffer.get().toInt()
        require(version == FORMAT_VERSION) { "Unsupported backup version: $version" }

        val iterations = buffer.int
        require(iterations in 10_000..5_000_000) { "Unsupported KDF iterations in backup" }

        val salt = ByteArray(SALT_LEN_BYTES)
        buffer.get(salt)

        val iv = ByteArray(IV_LEN_BYTES)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LEN_BITS)
        val keyBytes = try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(AAD_BYTES)

        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, StandardCharsets.UTF_8)
    }

    fun exportToUri(context: Context, profileManager: ProfileManager, uri: Uri, passphrase: CharArray) {
        val payload = createPayload(profileManager)
        val json = payloadToJson(payload)
        val encryptedData = encryptPayload(json.toString(), passphrase)

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(encryptedData)
            out.flush()
        } ?: throw IllegalStateException("Could not open output stream for $uri")
    }

    fun importFromUri(context: Context, profileManager: ProfileManager, uri: Uri, passphrase: CharArray): ImportResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw IllegalStateException("Could not read from $uri")

        val decryptedJson = decryptPayload(bytes, passphrase)
        val json = JSONObject(decryptedJson)
        val payload = jsonToPayload(json)

        return restorePayload(profileManager, payload)
    }

    fun restorePayload(profileManager: ProfileManager, payload: ExportPayload): ImportResult {
        // Restore credentials (re-encrypted with local Keystore in profileManager)
        payload.credentials.forEach { cred ->
            profileManager.addCredential(cred)
        }

        // Restore SSH Keys (re-encrypted with local Keystore in profileManager)
        payload.sshKeys.forEach { key ->
            profileManager.addSshKey(key)
        }

        // Restore profiles (re-encrypted passwords & cloudflared parameters)
        payload.profiles.forEach { prof ->
            profileManager.addProfile(prof)
        }

        var pinRestored = false
        if (payload.appPinVerifier != null) {
            profileManager.setAppPinVerifier(payload.appPinVerifier)
            pinRestored = true
        }

        return ImportResult(
            profilesRestored = payload.profiles.size,
            credentialsRestored = payload.credentials.size,
            sshKeysRestored = payload.sshKeys.size,
            pinRestored = pinRestored
        )
    }
}
