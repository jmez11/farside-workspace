package com.farside.workspace

import com.farside.workspace.data.*

internal object MacDefaultsFixture {
    fun run():String {
        val ssh=ConnectionProfile("fixture","Fixture",ConnectionType.SSH,"fixture.invalid",user="",pass="")
        check(ssh.enableSftp)
        check(!ConnectionProfile.fromJson(ssh.copy(enableSftp=false).toJson()).enableSftp)
        val old=ssh.toJson().apply { remove("enableSftp") }
        check(!ConnectionProfile.fromJson(old).enableSftp) // Preserve historical implicit off.
        val mac=ConnectionProfile("mac","Fixture",ConnectionType.VNC,"fixture.invalid",
            user="",pass="",vncAuthMode=VncAuthMode.MAC_USER,macSshRelayProfileId=ssh.id)
        val json=mac.toJson().apply { remove("sftpPairProfileId") }
        check(!ConnectionProfile.fromJson(mac.toJson().apply { remove("macSshRelaySameMac") }).macSshRelaySameMac)
        check(ConnectionProfile.fromJson(mac.copy(macSshRelaySameMac=true).toJson()).macSshRelaySameMac)
        check(ConnectionProfile.fromJson(json).sftpPairProfileId==ssh.id)
        check(!ConnectionProfile.fromJson(mac.copy(sftpPairProfileId="other").toJson()).sftpPairProfileId.isNullOrBlank())
        check(VncBridge.xtKeysym(0x15b)==0xffeb && VncBridge.xtKeysym(0x38)==0xffe9 && VncBridge.xtKeysym(0x138)==0xffea)
        val buttons=mutableListOf<Int>();val input=MacMouseInput { _,_,mask -> buttons+=mask }
        input.mouse(1,1,0x9000);input.mouse(2,2,0x0800);input.mouse(3,3,0x1000)
        check(buttons==listOf(1,1,0))
        return "PASS Command/Option mapping and drag button state; new SSH defaults SFTP on; explicit/historical off retained; legacy companion migration and JSON persistence"
    }
}
