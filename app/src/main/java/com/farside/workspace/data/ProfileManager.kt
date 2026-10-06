package com.farside.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("multidesk_profiles", Context.MODE_PRIVATE)
    
    fun getProfiles(): List<ConnectionProfile> {
        val profilesStr = prefs.getString("profiles", "[]")
        val profiles = mutableListOf<ConnectionProfile>()
        try {
            val jsonArray = JSONArray(profilesStr)
            for (i in 0 until jsonArray.length()) {
                profiles.add(ConnectionProfile.fromJson(jsonArray.getJSONObject(i)))
            }
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
    
    fun deleteCredential(id: String) {
        val creds = getCredentials().toMutableList()
        creds.removeAll { it.id == id }
        saveCredentials(creds)
    }
}
