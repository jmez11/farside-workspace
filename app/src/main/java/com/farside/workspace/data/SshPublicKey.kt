package com.farside.workspace.data

import android.util.Base64
import com.farside.workspace.LocalizableException
import com.farside.workspace.R
import com.farside.workspace.ssh.SshConnectionManager
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.security.MessageDigest

data class SshPublicKey(val line: String, val fingerprint: String)

fun deriveSshPublicKey(privateKey: String, passphrase: String, name: String): SshPublicKey {
    try {
        SshConnectionManager.ensureBouncyCastleProvider()
        val finder = passphrase.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        val provider = SSHClient().loadKeys(privateKey.trim().replace("\r\n", "\n"), null as String?, finder)
        val type = provider.type
        require(type == KeyType.RSA || type == KeyType.ED25519 || type == KeyType.ECDSA256 ||
            type == KeyType.ECDSA384 || type == KeyType.ECDSA521)
        val blob = Buffer.PlainBuffer().putPublicKey(provider.public).compactData
        val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val fingerprint = "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
        return SshPublicKey("${type} $encoded $name", fingerprint)
    } catch (_: Exception) {
        throw LocalizableException(R.string.ssh_public_key_invalid)
    }
}
