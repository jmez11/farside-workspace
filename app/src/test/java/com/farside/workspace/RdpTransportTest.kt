package com.farside.workspace

import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProxyType
import org.junit.Assert.*
import org.junit.Test

class RdpTransportTest {
    private val direct = ConnectionProfile(id="transport-test", name="Transport", type=ConnectionType.RDP,
        host="127.0.0.1", port=3389, user="", pass="")

    @Test fun onlyDirectRoutesMayUseUdp() {
        assertFalse(RdpTransport.tcpOnly(direct))
        assertTrue(RdpTransport.tcpOnly(direct.copy(useCloudflare=true)))
        ProxyType.entries.filter { it != ProxyType.NONE }.forEach {
            assertTrue("UDP must not bypass $it", RdpTransport.tcpOnly(direct.copy(proxyType=it)))
        }
    }

    @Test fun udpLabelRequiresReadyTunnelAndClearsOnFailure() {
        assertNull(RdpTransport.statusResource(0))
        assertEquals(R.string.rdp_transport_pending, RdpTransport.statusResource(1))
        assertEquals(R.string.rdp_transport_udp, RdpTransport.statusResource(2))
        assertNull(RdpTransport.statusResource(0))
        assertNull(RdpTransport.statusResource(-1))
    }
}
