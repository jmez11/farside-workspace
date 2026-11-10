package com.farside.workspace

import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ProxyType

/** UDP cannot traverse the TCP-only routes supported by Farside. */
internal object RdpTransport {
    fun tcpOnly(profile: ConnectionProfile): Boolean =
        profile.useCloudflare || profile.proxyType != ProxyType.NONE

    // Never infer UDP use from the requested settings or the presence of server capabilities.
    fun statusResource(state: Int): Int? = when (state) {
        2 -> R.string.rdp_transport_udp
        1 -> R.string.rdp_transport_pending
        else -> null
    }
}
