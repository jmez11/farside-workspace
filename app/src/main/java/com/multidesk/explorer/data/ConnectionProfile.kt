package com.multidesk.explorer.data

import org.json.JSONObject

enum class ConnectionType {
    RDP, SSH
}

enum class ProxyType {
    NONE, SOCKS4, SOCKS5, HTTP, LOCAL, TELNET, CLOUDFLARED
}

enum class SoundRedirection {
    PLAY_ON_DEVICE, PLAY_ON_REMOTE, DO_NOT_PLAY
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
    val useCloudflare: Boolean = false, // Keep for legacy/shortcut
    val enableCamera: Boolean = false,
    val cameraId: String? = null,
    
    // Proxy Settings
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val proxyUser: String? = null,
    val proxyPass: String? = null,
    val proxyExclude: String? = null,
    val proxyCommand: String? = null,
    val cloudflaredParameters: String = "",
    
    // RDP specific options
    val soundRedirection: SoundRedirection = SoundRedirection.PLAY_ON_DEVICE,
    val redirectMicrophone: Boolean = false,
    val redirectStorage: Boolean = false,
    val redirectClipboard: Boolean = true,
    val redirectLocation: Boolean = false,
    val customResolution: Boolean = false,
    val matchDevice: Boolean = true,
    val useDefaultResolution: Boolean = false,
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val resolutionScaling: Int = 100,
    val swapMouseButtons: Boolean = false,
    val adminMode: Boolean = false
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
        
        json.put("proxyType", proxyType.name)
        json.put("proxyHost", proxyHost)
        json.put("proxyPort", proxyPort)
        json.put("proxyUser", proxyUser)
        json.put("proxyPass", proxyPass)
        json.put("proxyExclude", proxyExclude)
        json.put("proxyCommand", proxyCommand)
        if (cloudflaredParameters.isNotEmpty()) json.put("cloudflaredParametersEncrypted",
            android.util.Base64.encodeToString(com.multidesk.explorer.CredentialManager.encrypt(cloudflaredParameters), android.util.Base64.NO_WRAP))
        
        json.put("soundRedirection", soundRedirection.name)
        json.put("redirectMicrophone", redirectMicrophone)
        json.put("redirectStorage", redirectStorage)
        json.put("redirectClipboard", redirectClipboard)
        json.put("redirectLocation", redirectLocation)
        json.put("customResolution", customResolution)
        json.put("matchDevice", matchDevice)
        json.put("useDefaultResolution", useDefaultResolution)
        json.put("resolutionWidth", resolutionWidth)
        json.put("resolutionHeight", resolutionHeight)
        json.put("resolutionScaling", resolutionScaling)
        json.put("swapMouseButtons", swapMouseButtons)
        json.put("adminMode", adminMode)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ConnectionProfile {
            val pTypeStr = if (json.has("proxyType")) json.getString("proxyType") else ProxyType.NONE.name
            val sRedirectStr = if (json.has("soundRedirection")) json.getString("soundRedirection") else SoundRedirection.PLAY_ON_DEVICE.name
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
                cameraId = if (json.has("cameraId") && !json.isNull("cameraId")) json.getString("cameraId") else null,
                
                proxyType = try { ProxyType.valueOf(pTypeStr) } catch(e: Exception) { ProxyType.NONE },
                proxyHost = if (json.has("proxyHost") && !json.isNull("proxyHost")) json.getString("proxyHost") else null,
                proxyPort = if (json.has("proxyPort") && !json.isNull("proxyPort")) json.getInt("proxyPort") else null,
                proxyUser = if (json.has("proxyUser") && !json.isNull("proxyUser")) json.getString("proxyUser") else null,
                proxyPass = if (json.has("proxyPass") && !json.isNull("proxyPass")) json.getString("proxyPass") else null,
                proxyExclude = if (json.has("proxyExclude") && !json.isNull("proxyExclude")) json.getString("proxyExclude") else null,
                proxyCommand = if (json.has("proxyCommand") && !json.isNull("proxyCommand")) json.getString("proxyCommand") else null,
                cloudflaredParameters = if (json.has("cloudflaredParametersEncrypted"))
                    com.multidesk.explorer.CredentialManager.decrypt(android.util.Base64.decode(json.getString("cloudflaredParametersEncrypted"), android.util.Base64.NO_WRAP)) else "",
                
                soundRedirection = try { SoundRedirection.valueOf(sRedirectStr) } catch (e: Exception) { SoundRedirection.PLAY_ON_DEVICE },
                redirectMicrophone = json.optBoolean("redirectMicrophone", false),
                redirectStorage = json.optBoolean("redirectStorage", false),
                redirectClipboard = json.optBoolean("redirectClipboard", true),
                redirectLocation = json.optBoolean("redirectLocation", false),
                customResolution = json.optBoolean("customResolution", false),
                matchDevice = json.optBoolean("matchDevice", true),
                useDefaultResolution = json.optBoolean("useDefaultResolution", false),
                resolutionWidth = json.optInt("resolutionWidth", 1920),
                resolutionHeight = json.optInt("resolutionHeight", 1080),
                resolutionScaling = json.optInt("resolutionScaling", 100),
                swapMouseButtons = json.optBoolean("swapMouseButtons", false),
                adminMode = json.optBoolean("adminMode", false)
            )
        }
    }
}
