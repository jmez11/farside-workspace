package com.farside.workspace.data

import org.json.JSONObject
import androidx.annotation.StringRes
import com.farside.workspace.R

enum class ConnectionType(@get:StringRes val labelResId: Int) {
    RDP(R.string.protocol_rdp), SSH(R.string.protocol_ssh), VNC(R.string.protocol_vnc)
}

enum class ProxyType {
    NONE, SOCKS4, SOCKS5, HTTP, LOCAL, TELNET, CLOUDFLARED;

    @get:StringRes val labelResId: Int get() = when (this) {
        NONE -> R.string.proxy_none
        CLOUDFLARED -> R.string.proxy_cloudflared
        SOCKS4 -> R.string.proxy_socks4
        SOCKS5 -> R.string.proxy_socks5
        HTTP -> R.string.proxy_http
        LOCAL -> R.string.proxy_local
        TELNET -> R.string.proxy_telnet
    }
    companion object {
        // Retain other enum values only to read legacy profiles without silently dropping proxies.
        val choices = listOf(NONE, CLOUDFLARED, SOCKS5, HTTP)
    }
}

enum class SoundRedirection {
    PLAY_ON_DEVICE, PLAY_ON_REMOTE, DO_NOT_PLAY
}

enum class VncAuthMode { VNC_PASSWORD, MAC_USER }

enum class CameraResolution(@get:StringRes val labelResId: Int, val maxDim: Int) {
    SD(R.string.camera_resolution_sd, 480),
    HD_720(R.string.camera_resolution_720, 720),
    NATIVE_MAX(R.string.camera_resolution_native, -1)
}

data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ConnectionType,
    val host: String,
    val port: Int = when (type) { ConnectionType.RDP -> 3389; ConnectionType.SSH -> 22; ConnectionType.VNC -> 5900 },
    val user: String,
    val pass: String,
    val remoteApp: String? = null,
    val useCloudflare: Boolean = false, // Keep for legacy/shortcut
    val enableCamera: Boolean = false,
    val cameraResolution: CameraResolution = CameraResolution.HD_720,
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
    val customResolution: Boolean = false,
    val matchDevice: Boolean = true,
    val useDefaultResolution: Boolean = false,
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val resolutionScaling: Int = 100,
    val swapMouseButtons: Boolean = false,
    val autoCopySelection: Boolean = true,
    val adminMode: Boolean = false,
    val credentialId: String? = null,
    val sshKeyId: String? = null,
    val jumpHostProfileId: String? = null,
    val enableSftp: Boolean = type == ConnectionType.SSH,
    val vncAuthMode: VncAuthMode = VncAuthMode.VNC_PASSWORD,
    val sftpPairProfileId: String? = null,
    val sshForwards: List<SshForward> = emptyList(),
    val terminalScrollback: Int = 2000,
    val terminalFontSize: Int = 14,
    val terminalOpacity: Float = 1f,
    val reconnectAutomatically: Boolean = false,
    val cardBackground: String? = null,
    val connectToConsole: Boolean = false,
    val expMacHevc: Boolean = false,
    val expMacShareConsole: Boolean = false,
    val macPreferAvc: Boolean = true,
    val macAudioEnabled: Boolean = false,
    val macSshRelayProfileId: String? = null,
    val enableMacSshRelay: Boolean = false,
    val macSshRelaySameMac: Boolean = false
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("type", type.name)
        json.put("host", host)
        json.put("port", port)
        json.put("credentialSource", if(credentialId==null) "connection" else "vault")
        if (credentialId == null) {
            json.put("user", user)
            com.farside.workspace.CredentialManager.writePassword(json, "pass", pass)
        }
        json.put("credentialId", credentialId)
        json.put("sshKeyId", sshKeyId)
        json.put("jumpHostProfileId", if (type == ConnectionType.SSH) jumpHostProfileId else null)
        json.put("enableSftp", type == ConnectionType.SSH && enableSftp)
        if (type == ConnectionType.VNC) {
            json.put("vncAuthMode", vncAuthMode.name)
            json.put("sftpPairProfileId", sftpPairProfileId)
        }
        json.put("remoteApp", remoteApp)
        json.put("useCloudflare", useCloudflare)
        json.put("enableCamera", enableCamera)
        json.put("cameraResolution", cameraResolution.name)
        json.put("cameraId", cameraId)
        
        json.put("proxyType", proxyType.name)
        json.put("proxyHost", proxyHost)
        json.put("proxyPort", proxyPort)
        json.put("proxyUser", proxyUser)
        com.farside.workspace.CredentialManager.writePassword(json, "proxyPass", proxyPass)
        json.put("proxyExclude", proxyExclude)
        json.put("proxyCommand", proxyCommand)
        if (cloudflaredParameters.isNotEmpty()) json.put("cloudflaredParametersEncrypted",
            android.util.Base64.encodeToString(com.farside.workspace.CredentialManager.encrypt(cloudflaredParameters), android.util.Base64.NO_WRAP))
        
        json.put("soundRedirection", soundRedirection.name)
        json.put("redirectMicrophone", redirectMicrophone)
        json.put("redirectStorage", redirectStorage)
        json.put("redirectClipboard", redirectClipboard)
        json.put("customResolution", customResolution)
        json.put("matchDevice", matchDevice)
        json.put("useDefaultResolution", useDefaultResolution)
        json.put("resolutionWidth", resolutionWidth)
        json.put("resolutionHeight", resolutionHeight)
        json.put("resolutionScaling", resolutionScaling)
        json.put("swapMouseButtons", swapMouseButtons)
        json.put("autoCopySelection", autoCopySelection)
        json.put("sshForwards", org.json.JSONArray().also { a -> sshForwards.forEach { a.put(it.toJson()) } })
        json.put("terminalScrollback", terminalScrollback)
        json.put("terminalFontSize", terminalFontSize)
        json.put("terminalOpacity", terminalOpacity)
        json.put("reconnectAutomatically", reconnectAutomatically)
        json.put("cardBackground", cardBackground)
        json.put("adminMode", adminMode)
        json.put("connectToConsole", connectToConsole)
        json.put("expMacHevc", expMacHevc)
        json.put("expMacShareConsole", expMacShareConsole)
        json.put("macPreferAvc", macPreferAvc)
        json.put("macAudioEnabled", macAudioEnabled)
        json.put("macSshRelayProfileId", macSshRelayProfileId)
        json.put("enableMacSshRelay", enableMacSshRelay)
        json.put("macSshRelaySameMac", macSshRelaySameMac)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ConnectionProfile {
            val pTypeStr = if (json.has("proxyType")) json.getString("proxyType") else ProxyType.NONE.name
            val sRedirectStr = if (json.has("soundRedirection")) json.getString("soundRedirection") else SoundRedirection.PLAY_ON_DEVICE.name
            val camResStr = if (json.has("cameraResolution")) json.getString("cameraResolution") else CameraResolution.HD_720.name
            return ConnectionProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                type = ConnectionType.valueOf(json.getString("type")),
                host = json.getString("host"),
                port = json.optInt("port", when (json.getString("type")) { "VNC" -> 5900; "SSH" -> 22; else -> 3389 }),
                user = json.optString("user", ""),
                pass = com.farside.workspace.CredentialManager.readPassword(json, "pass") ?: "",
                credentialId = if (json.has("credentialId") && !json.isNull("credentialId")) json.getString("credentialId") else null,
                sshKeyId = if (json.has("sshKeyId") && !json.isNull("sshKeyId")) json.getString("sshKeyId") else null,
                jumpHostProfileId = if (json.getString("type") == "SSH" && json.has("jumpHostProfileId") && !json.isNull("jumpHostProfileId")) json.getString("jumpHostProfileId") else null,
                enableSftp = json.getString("type") == "SSH" && json.optBoolean("enableSftp", false),
                vncAuthMode = if (json.getString("type") == "VNC")
                    runCatching { VncAuthMode.valueOf(json.optString("vncAuthMode", VncAuthMode.VNC_PASSWORD.name)) }.getOrDefault(VncAuthMode.VNC_PASSWORD)
                    else VncAuthMode.VNC_PASSWORD,
                sftpPairProfileId = if (json.getString("type") == "VNC" && json.has("sftpPairProfileId") && !json.isNull("sftpPairProfileId"))
                    json.getString("sftpPairProfileId") else if (json.getString("type") == "VNC") json.optString("macSshRelayProfileId").takeIf { it.isNotBlank() && it != "null" } else null,
                remoteApp = if (json.has("remoteApp") && !json.isNull("remoteApp")) json.getString("remoteApp") else null,
                useCloudflare = if (json.has("useCloudflare")) json.getBoolean("useCloudflare") else false,
                enableCamera = if (json.has("enableCamera")) json.getBoolean("enableCamera") else false,
                cameraResolution = if (camResStr == "FHD_1080") CameraResolution.NATIVE_MAX
                    else try { CameraResolution.valueOf(camResStr) } catch(e: Exception) { CameraResolution.HD_720 },
                cameraId = if (json.has("cameraId") && !json.isNull("cameraId")) json.getString("cameraId") else null,
                
                proxyType = try { ProxyType.valueOf(pTypeStr) } catch(e: Exception) { ProxyType.NONE },
                proxyHost = if (json.has("proxyHost") && !json.isNull("proxyHost")) json.getString("proxyHost") else null,
                proxyPort = if (json.has("proxyPort") && !json.isNull("proxyPort")) json.getInt("proxyPort") else null,
                proxyUser = if (json.has("proxyUser") && !json.isNull("proxyUser")) json.getString("proxyUser") else null,
                proxyPass = com.farside.workspace.CredentialManager.readPassword(json, "proxyPass"),
                proxyExclude = if (json.has("proxyExclude") && !json.isNull("proxyExclude")) json.getString("proxyExclude") else null,
                proxyCommand = if (json.has("proxyCommand") && !json.isNull("proxyCommand")) json.getString("proxyCommand") else null,
                cloudflaredParameters = if (json.has("cloudflaredParametersEncrypted"))
                    com.farside.workspace.CredentialManager.decrypt(android.util.Base64.decode(json.getString("cloudflaredParametersEncrypted"), android.util.Base64.NO_WRAP)) else "",
                
                soundRedirection = try { SoundRedirection.valueOf(sRedirectStr) } catch (e: Exception) { SoundRedirection.PLAY_ON_DEVICE },
                redirectMicrophone = json.optBoolean("redirectMicrophone", false),
                redirectStorage = json.optBoolean("redirectStorage", false),
                redirectClipboard = json.optBoolean("redirectClipboard", true),
                customResolution = json.optBoolean("customResolution", false),
                matchDevice = json.optBoolean("matchDevice", true),
                useDefaultResolution = json.optBoolean("useDefaultResolution", false),
                resolutionWidth = json.optInt("resolutionWidth", 1920),
                resolutionHeight = json.optInt("resolutionHeight", 1080),
                resolutionScaling = json.optInt("resolutionScaling", 100),
                swapMouseButtons = json.optBoolean("swapMouseButtons", false),
                autoCopySelection = json.optBoolean("autoCopySelection", true),
                sshForwards = json.optJSONArray("sshForwards")?.let { a -> (0 until a.length()).map { SshForward.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                terminalScrollback = json.optInt("terminalScrollback", 2000).coerceIn(100, 50000),
                terminalFontSize = json.optInt("terminalFontSize", 14).coerceIn(8, 32),
                terminalOpacity = json.optDouble("terminalOpacity", 1.0).toFloat().let { if (it.isFinite()) it.coerceIn(0.2f, 1f) else 1f },
                reconnectAutomatically = json.optBoolean("reconnectAutomatically", false),
                cardBackground = json.optString("cardBackground").takeIf { it.isNotBlank() },
                connectToConsole = json.optBoolean("connectToConsole", false),
                adminMode = json.optBoolean("adminMode", false),
                expMacHevc = json.optBoolean("expMacHevc", false),
                expMacShareConsole = json.optBoolean("expMacShareConsole", false),
                macPreferAvc = json.optBoolean("macPreferAvc", true),
                macAudioEnabled = json.optBoolean("macAudioEnabled", false),
                macSshRelayProfileId = json.optString("macSshRelayProfileId").takeIf { it.isNotBlank() && it != "null" },
                enableMacSshRelay = json.optBoolean("enableMacSshRelay", false),
                macSshRelaySameMac = json.optBoolean("macSshRelaySameMac", false)
            )
        }
    }
}
