package com.multidesk.explorer.data

import org.json.JSONObject

enum class ConnectionType {
    RDP, SSH
}

data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ConnectionType,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val remoteApp: String? = null,
    val useCloudflare: Boolean = false,
    val enableCamera: Boolean = false,
    val cameraId: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("type", type.name)
        json.put("host", host)
        json.put("port", port)
        json.put("user", user)
        json.put("pass", pass)
        json.put("remoteApp", remoteApp)
        json.put("useCloudflare", useCloudflare)
        json.put("enableCamera", enableCamera)
        json.put("cameraId", cameraId)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ConnectionProfile {
            return ConnectionProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                type = ConnectionType.valueOf(json.getString("type")),
                host = json.getString("host"),
                port = json.getInt("port"),
                user = json.getString("user"),
                pass = json.getString("pass"),
                remoteApp = if (json.has("remoteApp") && !json.isNull("remoteApp")) json.getString("remoteApp") else null,
                useCloudflare = if (json.has("useCloudflare")) json.getBoolean("useCloudflare") else false,
                enableCamera = if (json.has("enableCamera")) json.getBoolean("enableCamera") else false,
                cameraId = if (json.has("cameraId") && !json.isNull("cameraId")) json.getString("cameraId") else null
            )
        }
    }
}
