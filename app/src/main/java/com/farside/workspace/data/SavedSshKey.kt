package com.farside.workspace.data

import org.json.JSONObject
import java.util.UUID

data class SavedSshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val privateKey: String,
    val passphrase: String = "",
    val requirePin: Boolean = false
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        com.farside.workspace.CredentialManager.writePassword(json, "privateKey", privateKey)
        com.farside.workspace.CredentialManager.writePassword(json, "passphrase", passphrase)
        json.put("requirePin", requirePin)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SavedSshKey {
            return SavedSshKey(
                id = json.getString("id"),
                name = json.getString("name"),
                privateKey = com.farside.workspace.CredentialManager.readPassword(json, "privateKey") ?: "",
                passphrase = com.farside.workspace.CredentialManager.readPassword(json, "passphrase") ?: "",
                requirePin = json.optBoolean("requirePin", false)
            )
        }
    }
}
