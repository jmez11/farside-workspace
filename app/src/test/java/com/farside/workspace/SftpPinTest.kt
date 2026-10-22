package com.farside.workspace

import com.farside.workspace.data.*
import com.farside.workspace.sftp.sftpRequiresPin
import org.junit.Assert.*
import org.junit.Test

class SftpPinTest {
    @Test fun onlySecretsUsedByTargetOrJumpHostsRequirePin() {
        val credential=SavedCredential(name="Protected",user="fixture",pass="",requirePin=true)
        val key=SavedSshKey(name="Protected key",privateKey="fixture",requirePin=true)
        val plain=ConnectionProfile("plain","Plain",ConnectionType.SSH,"127.0.0.1",user="fixture",pass="")
        val byCredential=plain.copy(id="credential",credentialId=credential.id)
        val byKey=plain.copy(id="key",sshKeyId=key.id)
        val throughJump=plain.copy(id="jump-target",jumpHostProfileId=byCredential.id)
        val all=listOf(plain,byCredential,byKey,throughJump)
        assertFalse(sftpRequiresPin(plain,all,listOf(credential),listOf(key)))
        assertTrue(sftpRequiresPin(byCredential,all,listOf(credential),listOf(key)))
        assertTrue(sftpRequiresPin(byKey,all,listOf(credential),listOf(key)))
        assertTrue(sftpRequiresPin(throughJump,all,listOf(credential),listOf(key)))
        assertFalse(sftpRequiresPin(byCredential,all,listOf(credential.copy(requirePin=false)),listOf(key)))
    }
}
