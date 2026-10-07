package com.farside.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)

    init {
        // Encrypt legacy plaintext in both arrays in one durable preference transaction.
        val editor = prefs.edit()
        var changed = false
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
                    else credentials.singleOrNull { it.user == profile.user && it.pass == profile.pass }
                if (profile.credentialId == null && credential != null) {
                    json.put("credentialId", credential.id)
                    migrated = true
                }
                profiles.add(if (credential == null) profile else profile.copy(
                    credentialId = credential.id, user = credential.user, pass = credential.pass))
            }
            if (migrated) prefs.edit().putString("profiles", jsonArray.toString()).apply()
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
            profiles[index] = profile
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
    
    fun deleteProfile(id: String) {
        val profiles = getProfiles().toMutableList()
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
        val profiles = getProfiles()
        val creds = getCredentials().toMutableList()
        val index = creds.indexOfFirst { it.id == cred.id }
        require(index >= 0) { "Saved credential no longer exists" }
        creds[index] = cred
        saveCredentials(creds)
        // Keep the stored snapshot current too, including existing deletion/fallback behavior.
        saveProfiles(profiles.map { profile ->
            if (profile.credentialId == cred.id) profile.copy(user = cred.user, pass = cred.pass) else profile
        })
    }
    
    fun deleteCredential(id: String) {
        val creds = getCredentials().toMutableList()
        creds.removeAll { it.id == id }
        saveCredentials(creds)
    }

    fun getAppPinVerifier(): String? {
        val stored = prefs.getString("app_pinVerifier", null) ?: return null
        return if (stored.startsWith("{")) {
            com.farside.workspace.CredentialManager.readPassword(org.json.JSONObject(stored), "pin")
        } else if (stored.startsWith("v1:")) {
            stored
        } else null
    }

    fun setAppPinVerifier(verifier: String?) {
        if (verifier == null) {
            prefs.edit().remove("app_pinVerifier").apply()
        } else {
            val json = org.json.JSONObject()
            com.farside.workspace.CredentialManager.writePassword(json, "pin", verifier)
            prefs.edit().putString("app_pinVerifier", json.toString()).apply()
        }
    }
}
