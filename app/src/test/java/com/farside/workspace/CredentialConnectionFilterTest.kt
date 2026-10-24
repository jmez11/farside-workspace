package com.farside.workspace

import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.credentialMatchesConnection
import com.farside.workspace.ui.setup.SetupSignInState
import org.junit.Assert.*
import org.junit.Test

class CredentialConnectionFilterTest {
    @Test fun credentialKindsAreExclusiveToTheirConnectionMode() {
        val credentials=listOf(SavedCredential(name="ordinary",user="account",pass="password"),
            SavedCredential(name="vnc",user="",pass="password"),
            SavedCredential(name="mac",user="Full Name",userShort="short",pass="password"))
        for(type in ConnectionType.entries) for(auth in VncAuthMode.entries) {
            val profile=ConnectionProfile("owned","Owned",type,"fixture.invalid",user="",pass="",vncAuthMode=auth)
            credentials.forEachIndexed {index,credential ->
                val expected=when {
                    type==ConnectionType.SSH -> index==0 || index==2
                    type!=ConnectionType.VNC -> index==0
                    auth==VncAuthMode.VNC_PASSWORD -> index==1
                    else -> index==2
                }
                assertEquals("$type/$auth credential $index",expected,credentialMatchesConnection(credential,profile))
                val state=SetupSignInState(null).apply {credentialId=credential.id}
                assertEquals(expected,state.profile(profile,credentials).credentialId==credential.id)
            }
        }
    }
}
