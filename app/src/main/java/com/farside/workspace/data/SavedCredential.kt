package com.farside.workspace.data

import org.json.JSONObject
import java.util.UUID

data class SavedCredential(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val user: String,
    val pass: String,
    val pinVerifier: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("user", user)
        com.farside.workspace.CredentialManager.writePassword(json, "pass", pass)
        com.farside.workspace.CredentialManager.writePassword(json, "pinVerifier", pinVerifier)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SavedCredential {
            return SavedCredential(
                id = json.getString("id"),
                name = json.getString("name"),
                user = json.getString("user"),
                pass = com.farside.workspace.CredentialManager.readPassword(json, "pass") ?: "",
                pinVerifier = com.farside.workspace.CredentialManager.readPassword(json, "pinVerifier")
            )
        }
    }
}
