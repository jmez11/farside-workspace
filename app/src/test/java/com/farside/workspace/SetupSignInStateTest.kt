package com.farside.workspace

import com.farside.workspace.data.*
import com.farside.workspace.ui.setup.SetupSignInState
import org.junit.Assert.*
import org.junit.Test

class SetupSignInStateTest {
    private val base=ConnectionProfile("owned","Owned",ConnectionType.SSH,"127.0.0.1",user="",pass="")
    @Test fun savedPairsNeverBorrowConnectionSpecificUsernameOrPassword() {
        val first=SavedCredential(name="First",user="account",pass="first")
        val second=SavedCredential(name="Second",user="other",pass="second")
        val all=listOf(first,second)
        val state=SetupSignInState(null).apply {credentialId=first.id;user="typed";password="typedpass"}
        state.profile(base,all).let {assertEquals("account",it.user);assertEquals("first",it.pass)}
        state.credentialId=second.id
        state.profile(base,all).let {assertEquals("other",it.user);assertEquals("second",it.pass)}
        state.specific=true
        state.profile(base,all).let {assertNull(it.credentialId);assertEquals("typed",it.user);assertEquals("typedpass",it.pass)}
    }
    @Test fun storedPasswordNeverLeaksIntoSpecificKeyOrEmptyInteractiveAuth() {
        val stored=SavedCredential(name="Stored",user="account",pass="secret")
        val state=SetupSignInState(base.copy(credentialId=stored.id,user=stored.user,pass=stored.pass))
        state.specific=true
        state.profile(base,listOf(stored)).let {assertEquals("",it.user);assertEquals("",it.pass);assertNull(it.credentialId)}
        state.keyMode=true;state.keyId="owned-key";state.user="keyuser";state.password="unused"
        state.profile(base,listOf(stored)).let {assertEquals("keyuser",it.user);assertEquals("",it.pass);assertEquals("owned-key",it.sshKeyId)}
    }
    @Test fun rdpAcceptsUsernameWithoutPasswordWhileVncStillRequiresPassword() {
        val credential=SavedCredential(name="Username only",user="fixture",pass="")
        val state=SetupSignInState(null).apply {credentialId=credential.id}
        assertNull(state.error(base.copy(type=ConnectionType.RDP),listOf(credential)))
        state.specific=true;state.user="fixture"
        assertNull(state.error(base.copy(type=ConnectionType.RDP),listOf(credential)))
        assertNotNull(state.error(base.copy(type=ConnectionType.VNC,vncAuthMode=VncAuthMode.MAC_USER),listOf(credential)))
    }
}
