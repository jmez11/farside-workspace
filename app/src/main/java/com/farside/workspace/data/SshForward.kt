package com.farside.workspace.data

import org.json.JSONObject
import java.util.UUID

enum class ForwardKind(val label: String) { LOCAL("Local"), REMOTE("Remote"), DYNAMIC("Dynamic SOCKS") }
enum class ForwardFamily(val label: String) { AUTO("Auto"), IPV4("IPv4"), IPV6("IPv6") }

data class SshForward(
    val id: String = UUID.randomUUID().toString(),
    val kind: ForwardKind = ForwardKind.LOCAL,
    val sourcePort: Int = 8080,
    val destinationHost: String = "localhost",
    val destinationPort: Int = 80,
    val family: ForwardFamily = ForwardFamily.AUTO,
    val allowOtherHosts: Boolean = false,
    val enabled: Boolean = true
) {
    val bindHost: String get() = when (family) {
        ForwardFamily.AUTO -> if (allowOtherHosts) "" else "localhost"
        ForwardFamily.IPV4 -> if (allowOtherHosts) "0.0.0.0" else "127.0.0.1"
        ForwardFamily.IPV6 -> if (allowOtherHosts) "::" else "::1"
    }
    val route: String get() = if (kind == ForwardKind.DYNAMIC) "Quest :$sourcePort → SOCKS5 proxy"
        else "${if (kind == ForwardKind.LOCAL) "Quest" else "Server"} :$sourcePort → $destinationHost:$destinationPort"
    fun validate() {
        require(sourcePort in 1..65535) { "Source port must be 1–65535" }
        if (kind != ForwardKind.DYNAMIC) {
            require(destinationHost.isNotBlank() && destinationHost.none { it.isWhitespace() }) { "Enter a destination host without spaces" }
            require(destinationPort in 1..65535) { "Destination port must be 1–65535" }
        }
    }
    fun toJson() = JSONObject().put("id", id).put("kind", kind.name).put("sourcePort", sourcePort)
        .put("destinationHost", destinationHost).put("destinationPort", destinationPort)
        .put("family", family.name).put("allowOtherHosts", allowOtherHosts).put("enabled", enabled)
    companion object {
        fun fromJson(j: JSONObject) = SshForward(j.getString("id"), ForwardKind.valueOf(j.getString("kind")),
            j.getInt("sourcePort"), j.optString("destinationHost", "localhost"), j.optInt("destinationPort", 80),
            ForwardFamily.valueOf(j.optString("family", "AUTO")), j.optBoolean("allowOtherHosts"), j.optBoolean("enabled", true))
        fun validateList(rules: List<SshForward>) {
            rules.forEach { it.validate() }
            val active = rules.filter { it.enabled }
            require(active.map { Pair(it.kind == ForwardKind.REMOTE, it.sourcePort) }.distinct().size == active.size) {
                "Enabled tunnels on the same side need different source ports"
            }
        }
    }
}
