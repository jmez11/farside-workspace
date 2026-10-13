package com.farside.workspace.data

import org.json.JSONObject
import java.util.UUID
import androidx.annotation.StringRes
import com.farside.workspace.LocalizableException
import com.farside.workspace.R

enum class ForwardKind(@StringRes val labelResId: Int) { LOCAL(R.string.forward_kind_local), REMOTE(R.string.forward_kind_remote), DYNAMIC(R.string.forward_kind_dynamic) }
enum class ForwardFamily(@StringRes val labelResId: Int) { AUTO(R.string.forward_family_auto), IPV4(R.string.forward_family_ipv4), IPV6(R.string.forward_family_ipv6) }

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
    @get:StringRes val routeResId: Int get() = when (kind) {
        ForwardKind.DYNAMIC -> R.string.forward_route_dynamic
        ForwardKind.LOCAL -> R.string.forward_route_local
        ForwardKind.REMOTE -> R.string.forward_route_remote
    }
    val routeFormatArgs: Array<out Any> get() = if (kind == ForwardKind.DYNAMIC) arrayOf(sourcePort)
        else arrayOf(sourcePort, destinationHost, destinationPort)
    fun validate() {
        if (sourcePort !in 1..65535) throw LocalizableException(R.string.forward_source_port_invalid)
        if (kind != ForwardKind.DYNAMIC) {
            if (!(destinationHost.isNotBlank() && destinationHost.none { it.isWhitespace() })) throw LocalizableException(R.string.forward_host_invalid)
            if (destinationPort !in 1..65535) throw LocalizableException(R.string.forward_destination_port_invalid)
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
            if (active.map { Pair(it.kind == ForwardKind.REMOTE, it.sourcePort) }.distinct().size != active.size)
                throw LocalizableException(R.string.forward_port_conflict)
        }
    }
}
