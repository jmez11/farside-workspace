package com.farside.workspace.data

import org.json.JSONObject
import java.util.UUID

data class SavedCredential(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val user: String,
    val pass: String
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("user", user)
        json.put("pass", pass)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SavedCredential {
            return SavedCredential(
                id = json.getString("id"),
                name = json.getString("name"),
                user = json.getString("user"),
                pass = json.getString("pass")
            )
        }
    }
}
