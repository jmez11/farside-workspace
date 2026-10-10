package com.farside.workspace.tunnel

import android.content.Context
import android.util.Base64
import com.farside.workspace.CredentialManager
import org.json.JSONObject
import java.io.File

data class AdbTunnelConfig(val token: String, val hostname: String, val port: Int) {
    fun validate(): AdbTunnelConfig {
        require(port in 1024..65535) { "Use a port between 1024 and 65535." }
        val host = CloudflaredOptions.hostname(hostname)
        require(token.length in 40..4096 && token.matches(Regex("[A-Za-z0-9+/=_-]+"))) { "Enter only the tunnel token, without the command." }
        val decoded = runCatching { JSONObject(String(Base64.decode(token, Base64.DEFAULT), Charsets.UTF_8)) }.getOrNull()
        require(decoded != null && decoded.optString("a").matches(Regex("[0-9a-f]{32}")) &&
            decoded.optString("t").matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) && decoded.optString("s").isNotBlank()) { "Invalid Cloudflare tunnel token." }
        return copy(hostname = host)
    }
    fun yaml(): String = "# Token-based tunnels use dashboard ingress; mirror this rule there.\n" +
        "ingress:\n  - hostname: $hostname\n    service: tcp://127.0.0.1:$port\n  - service: http_status:404\n"
    fun clientCommand(): String = "cloudflared access tcp --hostname $hostname --url 127.0.0.1:$port"
    // Never include the token in diagnostics, assertion messages or UI status.
    override fun toString(): String = "AdbTunnelConfig(hostname=$hostname, port=$port)"
}

class AdbTunnelSettings(context: Context) {
    private val root = File(context.noBackupFilesDir, "adb-tunnel").apply { mkdirs(); android.system.Os.chmod(absolutePath, 448) }
    private val file = File(root, "settings.enc")
    fun load(): AdbTunnelConfig? = if (!file.exists()) null else runCatching {
        val json = JSONObject(CredentialManager.decrypt(file.readBytes()))
        AdbTunnelConfig(json.getString("token"), json.getString("hostname"), json.getInt("port")).validate()
    }.getOrNull()
    fun save(config: AdbTunnelConfig) {
        val value = config.validate()
        val temp = File(root, "settings.tmp")
        temp.writeBytes(CredentialManager.encrypt(JSONObject().put("token", value.token).put("hostname", value.hostname).put("port", value.port).toString()))
        android.system.Os.chmod(temp.absolutePath, 384)
        android.system.Os.rename(temp.absolutePath, file.absolutePath)
        File(root, "config.yml").writeText(value.yaml())
    }
}
