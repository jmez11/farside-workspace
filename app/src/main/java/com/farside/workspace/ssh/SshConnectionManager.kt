package com.farside.workspace.ssh

import android.content.Context
import android.os.Looper
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.ProxyType
import com.farside.workspace.tunnel.CloudflaredTunnel
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean

/** Reference-counted transport shared by terminal and SFTP channels for one profile. */
class SshConnectionManager private constructor(private val context: Context?, private val id: String) {
    companion object {
        private val holders = mutableMapOf<String, SshConnectionManager>()
        private val cleanup = java.util.concurrent.Executors.newCachedThreadPool()
        @Synchronized fun acquire(context: Context?, id: String): Lease = synchronized(holders) {
            val owner = holders.getOrPut(id) { SshConnectionManager(context?.applicationContext, id) }
            Lease(owner).also { owner.leases.add(it) }
        }
        @Synchronized fun ensureBouncyCastleProvider() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
    class Lease internal constructor(private val owner: SshConnectionManager) : AutoCloseable {
        internal val closed = AtomicBoolean(false)
        internal var onExit: () -> Unit = {}
        fun connect(profile: ConnectionProfile, onStatus: (String) -> Unit = {},
                    interactive: ((Boolean) -> String?)? = null, onExit: () -> Unit = {}): SSHClient {
            check(!closed.get()) { "SSH connection closed" }
            this.onExit = onExit
            return owner.connect(this, profile, onStatus, interactive)
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val last = synchronized(holders) {
                owner.leases.remove(this)
                if (owner.leases.isEmpty()) { holders.remove(owner.id, owner); owner.closed.set(true); true } else false
            }
            if (last) cleanup.execute { owner.disconnect() }
        }
    }
    private val leases = java.util.concurrent.CopyOnWriteArraySet<Lease>()
    private val closed = AtomicBoolean(false)
    private val connectLock = Any()
    @Volatile private var sshClient: SSHClient? = null
    @Volatile private var tunnel: CloudflaredTunnel? = null
    private var connectedProfile: ConnectionProfile? = null
    private fun notifyExit() { leases.forEach { runCatching { it.onExit() } } }
    private fun disconnect() {
        runCatching { tunnel?.close() }
        runCatching { sshClient?.disconnect() }
        runCatching { sshClient?.close() }
    }
    private fun connect(lease: Lease, profile: ConnectionProfile, onStatus: (String) -> Unit,
                        interactive: ((Boolean) -> String?)?): SSHClient = synchronized(connectLock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "SSH IO must run off the main thread" }
        check(!closed.get() && !lease.closed.get()) { "SSH connection closed" }
        sshClient?.takeIf { it.isConnected && it.isAuthenticated }?.let {
            val previous = checkNotNull(connectedProfile)
            check(previous.host == profile.host && previous.port == profile.port && previous.user == profile.user &&
                previous.pass == profile.pass && previous.sshKeyId == profile.sshKeyId && previous.credentialId == profile.credentialId &&
                previous.proxyType == profile.proxyType && previous.useCloudflare == profile.useCloudflare &&
                previous.cloudflaredParameters == profile.cloudflaredParameters) { "Connection settings changed. Close existing sessions and reconnect." }
            return@synchronized it
        }
        disconnect()
        ensureBouncyCastleProvider()
        try {
                var targetHost = profile.host
                var targetPort = profile.port

                val useCloudflared = profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED
                if (useCloudflared && context != null) {
                    onStatus("[Starting Cloudflare Access SSH tunnel...]\r\n")
                    val cfTunnel = CloudflaredTunnel(context)
                    tunnel = cfTunnel
                    val tunnelPort = cfTunnel.start(
                        hostInput = profile.host,
                        parameters = profile.cloudflaredParameters,
                        onStatus = { status -> onStatus("[$status]\r\n") },
                        onExit = {
                            if (!closed.get()) {
                                onStatus("\r\n[Cloudflared tunnel process exited]\r\n")
                                notifyExit()
                            }
                        },
                        accessSubcommand = "ssh"
                    )
                    targetHost = "127.0.0.1"
                    targetPort = tunnelPort
                }

                val ssh = SSHClient()
                sshClient = ssh
                ssh.addHostKeyVerifier(PromiscuousVerifier())

                ssh.connect(targetHost, targetPort)

                var user = profile.user.trim()
                if (user.isEmpty() && profile.host.contains("@")) {
                    user = profile.host.substringBefore("@").trim()
                }
                if (user.isEmpty()) {
                    if (interactive == null) throw net.schmizz.sshj.userauth.UserAuthException("A saved SSH username is required.")
                    onStatus("login as: ")
                    user = interactive?.invoke(true)?.trim() ?: throw java.util.concurrent.CancellationException("SSH sign-in cancelled")
                    if (user.isEmpty()) {
                        onStatus("No username provided.\r\n")
                        throw java.util.concurrent.CancellationException("SSH sign-in cancelled")
                    }
                }

                var authenticated = false

                // 1. Try public key authentication if SSH key is selected
                val savedKey = profile.sshKeyId?.let { keyId ->
                    context?.let { ctx -> ProfileManager(ctx).getSshKeys().find { it.id == keyId } }
                }
                if (savedKey != null && savedKey.privateKey.isNotBlank()) {
                    onStatus("[Offering SSH key '${savedKey.name}' for user '$user'...]\r\n")
                    try {
                        ensureBouncyCastleProvider()
                        val passwordFinder = if (savedKey.passphrase.isNotEmpty()) {
                            net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(savedKey.passphrase.toCharArray())
                        } else null
                        val cleanKey = savedKey.privateKey.trim().replace("\r\n", "\n")
                        val keyProvider = ssh.loadKeys(cleanKey, null as String?, passwordFinder)
                        ssh.authPublickey(user, keyProvider)
                        authenticated = ssh.isAuthenticated
                        if (authenticated) {
                            onStatus("[SSH key '${savedKey.name}' accepted by server]\r\n")
                        } else {
                            val allowed = try { ssh.userAuth.allowedMethods } catch (e: Exception) { null }
                            onStatus("[Server refused key '${savedKey.name}' for user '$user'${if (allowed != null) " (allowed methods: $allowed)" else ""}]\r\n")
                        }
                    } catch (e: Exception) {
                        val allowed = try { ssh.userAuth.allowedMethods } catch (ex: Exception) { null }
                        val reason = if (e is net.schmizz.sshj.userauth.UserAuthException) {
                            "Key refused by server${if (allowed != null) " (allowed methods: $allowed)" else ""}"
                        } else {
                            e.message ?: "Authentication failed"
                        }
                        onStatus("[Server refused SSH key '${savedKey.name}' for user '$user': $reason]\r\n")
                    }
                } else if (savedKey != null && savedKey.privateKey.isBlank()) {
                    onStatus("[Notice: SSH key '${savedKey.name}' has no private key data]\r\n")
                } else if (!profile.sshKeyId.isNullOrBlank()) {
                    onStatus("[Notice: Configured SSH key not found in saved keys]\r\n")
                }

                // 2. Try password authentication if password was provided and not yet authenticated
                if (!authenticated && profile.pass.isNotEmpty()) {
                    try {
                        ssh.authPassword(user, profile.pass)
                        authenticated = ssh.isAuthenticated
                    } catch (e: Exception) {
                        // Password failed; fall back to interactive prompt below
                    }
                }

                // 3. Fallback to interactive login when credentials were not entered or failed
                if (!authenticated && interactive != null) {
                    var attempts = 0
                    while (!authenticated && attempts < 3 && !lease.closed.get()) {
                        attempts++
                        onStatus("$user@$targetHost's password: ")
                        val enteredPass = interactive?.invoke(false) ?: throw net.schmizz.sshj.userauth.UserAuthException("Check the saved credential or SSH key.")
                        try {
                            ssh.authPassword(user, enteredPass)
                            authenticated = ssh.isAuthenticated
                        } catch (e: Exception) {
                            try {
                                ssh.auth(user, AuthKeyboardInteractive(object : ChallengeResponseProvider {
                                    override fun getSubmethods(): List<String> = emptyList()
                                    override fun init(resource: Resource<*>?, name: String?, instruction: String?) {}
                                    override fun getResponse(prompt: String?, echo: Boolean): CharArray = enteredPass.toCharArray()
                                    override fun shouldRetry(): Boolean = false
                                }))
                                authenticated = ssh.isAuthenticated
                            } catch (e2: Exception) {
                                // Interactive also rejected
                            }
                        }
                        if (!authenticated) {
                            onStatus("Permission denied, please try again.\r\n")
                        }
                    }
                }

                if (!authenticated) {
                    throw net.schmizz.sshj.userauth.UserAuthException("Check the saved credential or SSH key.")
                }


            check(!closed.get() && !lease.closed.get()) { "SSH connection closed" }
            connectedProfile = profile
            ssh
        } catch (error: Exception) {
            disconnect()
            sshClient = null
            tunnel = null
            throw error
        }
    }
}
