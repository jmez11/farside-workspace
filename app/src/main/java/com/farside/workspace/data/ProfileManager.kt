package com.farside.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)

    init {
        com.farside.workspace.DeviceCredentialAuth.initialize(context)
        // Encrypt legacy plaintext in both arrays in one durable preference transaction.
        val editor = prefs.edit()
        var changed = prefs.contains("mac_relay_warning_shown")
        if (changed) editor.remove("mac_relay_warning_shown")
        for (key in listOf("credentials", "profiles")) {
            val array = JSONArray(prefs.getString(key, "[]"))
            var arrayChanged = false
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                for (field in listOf("pass", "proxyPass")) {
                    if (json.has(field)) {
                        if (!json.has(field + "Encrypted") && !json.isNull(field)) {
                            com.farside.workspace.CredentialManager.writePassword(json, field, json.getString(field))
                        } else json.remove(field)
                        arrayChanged = true
                    }
                }
            }
            if (arrayChanged) {
                editor.putString(key, array.toString())
                changed = true
            }
        }
        if (changed) check(editor.commit()) { "Could not migrate saved passwords" }
    }
    
    fun getProfiles(): List<ConnectionProfile> {
        val profilesStr = prefs.getString("profiles", "[]")
        val profiles = mutableListOf<ConnectionProfile>()
        val credentials = getCredentials()
        var migrated = false
        try {
            val jsonArray = JSONArray(profilesStr)
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val profile = ConnectionProfile.fromJson(json)
                // Old profiles stored only a snapshot. Link only an unambiguous match.
                val credential = if (profile.credentialId != null) credentials.find { it.id == profile.credentialId }
                    else if(!json.has("credentialSource")) credentials.singleOrNull { it.user == profile.user && it.pass == profile.pass } else null
                if (profile.credentialId == null && credential != null) {
                    json.put("credentialId", credential.id)
                    migrated = true
                }
                if (profile.credentialId != null || credential != null) {
                    for (field in listOf("user", "pass", "passEncrypted")) {
                        if (json.has(field)) { json.remove(field); migrated = true }
                    }
                    profiles.add(profile.copy(credentialId = credential?.id ?: profile.credentialId,
                        user = (if(profile.type==ConnectionType.SSH) credential?.userShort ?: credential?.user else credential?.user).orEmpty(), pass = credential?.pass.orEmpty()))
                } else profiles.add(profile)
            }
            if (migrated) check(prefs.edit().putString("profiles", jsonArray.toString()).commit())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Deduplicate profiles by ID to fix corrupted states
        val deduped = profiles.distinctBy { it.id }
        if (deduped.size < profiles.size) {
            // Background thread to save deduplicated list
            Thread {
                try {
                    val newArray = JSONArray()
                    deduped.forEach { newArray.put(it.toJson()) }
                    prefs.edit().putString("profiles", newArray.toString()).commit()
                } catch (e: Exception) {}
            }.start()
        }
        return deduped
    }
    
    fun saveProfiles(profiles: List<ConnectionProfile>) {
        val jsonArray = JSONArray()
        profiles.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString("profiles", jsonArray.toString()).apply()
    }
    
    fun addProfile(profile: ConnectionProfile) {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = if (profile.cardBackground == null) profile.copy(cardBackground = profiles[index].cardBackground) else profile
        } else {
            profiles.add(profile)
        }
        saveProfiles(profiles)
    }
    
    fun updateProfile(profile: ConnectionProfile) {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
            saveProfiles(profiles)
        }
    }
    
    fun removeCardBackground(id: String) {
        val profile = getProfiles().firstOrNull { it.id == id } ?: return
        val name = profile.cardBackground ?: return
        updateProfile(profile.copy(cardBackground = null))
        java.io.File(context.filesDir, "card-backgrounds/$name").delete()
    }

    fun deleteProfile(id: String) {
        val profiles = getProfiles().toMutableList()
        profiles.firstOrNull { it.id == id }?.cardBackground?.let {
            java.io.File(context.filesDir, "card-backgrounds/$it").delete()
        }
        profiles.removeAll { it.id == id }
        saveProfiles(profiles)
    }

    fun getCredentials(): List<SavedCredential> {
        val credsStr = prefs.getString("credentials", "[]")
        val creds = mutableListOf<SavedCredential>()
        try {
            val jsonArray = JSONArray(credsStr)
            for (i in 0 until jsonArray.length()) {
                creds.add(SavedCredential.fromJson(jsonArray.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return creds
    }
    
    fun saveCredentials(creds: List<SavedCredential>) {
        val jsonArray = JSONArray()
        creds.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString("credentials", jsonArray.toString()).apply()
    }
    
    fun addCredential(cred: SavedCredential) {
        val creds = getCredentials().toMutableList()
        creds.add(cred)
        saveCredentials(creds)
    }

    fun updateCredential(cred: SavedCredential) {
        // Establish legacy links while the old username/password still match.
        getProfiles()
        val creds = getCredentials().toMutableList()
        val index = creds.indexOfFirst { it.id == cred.id }
        require(index >= 0) { "Saved credential no longer exists" }
        creds[index] = cred
        saveCredentials(creds)
    }
    
    fun deleteCredential(id: String) {
        // Establish legacy links before removal, then erase snapshots in the same transaction.
        val profiles = getProfiles()
        val creds = getCredentials().toMutableList()
        creds.removeAll { it.id == id }
        val savedCredentials = JSONArray().apply { creds.forEach { put(it.toJson()) } }
        val savedProfiles = JSONArray().apply { profiles.forEach { put(it.toJson()) } }
        check(prefs.edit().putString("credentials", savedCredentials.toString())
            .putString("profiles", savedProfiles.toString()).commit())
    }

    fun getSshKeys(): List<SavedSshKey> {
        val str = prefs.getString("ssh_keys", "[]")
        val list = mutableListOf<SavedSshKey>()
        try {
            val jsonArray = JSONArray(str)
            for (i in 0 until jsonArray.length()) {
                list.add(SavedSshKey.fromJson(jsonArray.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveSshKeys(keys: List<SavedSshKey>) {
        val jsonArray = JSONArray()
        keys.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString("ssh_keys", jsonArray.toString()).commit()
    }

    fun addSshKey(key: SavedSshKey) {
        val list = getSshKeys().toMutableList()
        list.removeAll { it.id == key.id }
        list.add(key)
        saveSshKeys(list)
    }

    fun updateSshKey(key: SavedSshKey) {
        val list = getSshKeys().toMutableList()
        val index = list.indexOfFirst { it.id == key.id }
        require(index >= 0) { "Saved SSH key no longer exists" }
        list[index] = key
        saveSshKeys(list)
    }

    fun deleteSshKey(id: String) {
        val list = getSshKeys().toMutableList()
        list.removeAll { it.id == id }
        saveSshKeys(list)
    }

    fun getAppPinVerifier(): String? {
        val stored = prefs.getString("app_pinVerifier", null) ?: return null
        return if (stored.startsWith("{")) {
            com.farside.workspace.CredentialManager.readPassword(org.json.JSONObject(stored), "pin")
        } else if (stored.startsWith("v1:")) {
            stored
        } else null
    }

    fun usesSystemPin(): Boolean = prefs.getBoolean("system_pin_enabled", false)

    /** Called only after the system-authentication callback has verified the device credential. */
    fun enableSystemPin() {
        com.farside.workspace.CredentialPin.clearCache()
        check(prefs.edit().putBoolean("system_pin_enabled", true).remove("app_pinVerifier")
            .remove("app-pin-attempts").remove("app-pin-until").commit())
    }

    fun setAppPinVerifier(verifier: String?) {
        com.farside.workspace.CredentialPin.clearCache()
        if (verifier == null) {
            prefs.edit().remove("app_pinVerifier").commit()
        } else {
            val json = org.json.JSONObject()
            com.farside.workspace.CredentialManager.writePassword(json, "pin", verifier)
            prefs.edit().putString("app_pinVerifier", json.toString()).commit()
        }
    }
}

