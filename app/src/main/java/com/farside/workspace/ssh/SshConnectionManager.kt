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
            return owner.connect(this, profile, onStatus, interactive, null, true)
        }
        /** Connects one hop of a jump chain, tunnelled through [via] when non-null; never resolves further jumps. */
        internal fun connectHop(profile: ConnectionProfile, via: SSHClient?, onStatus: (String) -> Unit, onExit: () -> Unit): SSHClient {
            check(!closed.get()) { "SSH connection closed" }
            this.onExit = onExit
            return owner.connect(this, profile, onStatus, null, via, false)
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
    private val hopLeases = mutableListOf<Lease>()
    private fun closeHops() {
        val toClose = synchronized(hopLeases) { hopLeases.toList().also { hopLeases.clear() } }
        toClose.asReversed().forEach { runCatching { it.close() } }
    }
    private fun disconnect() {
        runCatching { tunnel?.close() }
        runCatching { sshClient?.disconnect() }
        runCatching { sshClient?.close() }
        closeHops()
    }
    /** Connects every jump host in order (own lease "jumphost-<id>" each) and returns the last hop's client. */
    private fun connectJumpHosts(chain: JumpChain, onStatus: (String) -> Unit): SSHClient {
        val labels = chain.hops.map { it.name }
        val state = chain.hops.map { "…" }.toMutableList()
        fun progress() = labels.indices.joinToString(" → ") { "${labels[it]} ${state[it]}" }
        var upstream: SSHClient? = null
        chain.jumpHosts.forEachIndexed { index, hop ->
            val hopLease = acquire(context, "jumphost-${hop.id}")
            synchronized(hopLeases) { hopLeases.add(hopLease) }
            onStatus("[Jump host ${index + 1}/${chain.jumpHosts.size}: ${hop.name}]\r\n")
            try {
                upstream = hopLease.connectHop(hop, upstream, { onStatus(it.replace("[", "[${hop.name}: ")) }, { notifyExit() })
                state[index] = "✓"
            } catch (e: Exception) {
                state[index] = "✗"
                val reason = if (e is net.schmizz.sshj.userauth.UserAuthException) "auth failed" else e.message ?: e.javaClass.simpleName
                onStatus("[Chain: ${progress()} (${hop.name}: $reason)]\r\n")
                throw JumpChainException("Jump host ${hop.name} failed ($reason)")
            }
        }
        return checkNotNull(upstream)
    }
    private fun connect(lease: Lease, profile: ConnectionProfile, onStatus: (String) -> Unit,
                        interactive: ((Boolean) -> String?)?, viaParam: SSHClient?, resolveJumps: Boolean): SSHClient = synchronized(connectLock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "SSH IO must run off the main thread" }
        check(!closed.get() && !lease.closed.get()) { "SSH connection closed" }
        sshClient?.takeIf { it.isConnected && it.isAuthenticated }?.let {
            val previous = checkNotNull(connectedProfile)
            check(previous.host == profile.host && previous.port == profile.port && previous.user == profile.user &&
                previous.pass == profile.pass && previous.sshKeyId == profile.sshKeyId && previous.credentialId == profile.credentialId &&
                previous.proxyType == profile.proxyType && previous.useCloudflare == profile.useCloudflare &&
                previous.cloudflaredParameters == profile.cloudflaredParameters &&
                (!resolveJumps || previous.jumpHostProfileId == profile.jumpHostProfileId)) { "Connection settings changed. Close existing sessions and reconnect." }
            return@synchronized it
        }
        disconnect()
        ensureBouncyCastleProvider()
        var chainRef: JumpChain? = null
        var chainReported = false
        try {
                var via = viaParam
                var chain: JumpChain? = null
                if (resolveJumps && context != null && !profile.jumpHostProfileId.isNullOrBlank()) {
                    chain = JumpChains.resolveChain(profile, ProfileManager(context).getProfiles())
                    chainRef = chain
                    chain.warning?.let { onStatus("[Warning: ${context.getString(it)}]\r\n") }
                    if (chain.jumpHosts.isNotEmpty()) via = connectJumpHosts(chain, onStatus)
                }
                var targetHost = profile.host
                var targetPort = profile.port

                val useCloudflared = (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED) && via == null
                if (via != null && (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED)) {
                    onStatus("[Cloudflare tunnel ignored: this host is reached through a jump host]\r\n")
                }
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

                if (via != null) {
                    try {
                        ssh.connectVia(via.newDirectConnection(profile.host.substringAfter("@"), profile.port))
                    } catch (e: Exception) {
                        if (chain != null) {
                            chainReported = true
                            onStatus("[Chain: ${chain.hops.indices.joinToString(" → ") { i -> chain.hops[i].name + if (i < chain.hops.size - 1) " ✓" else " ✗" }} (${profile.name}: ${e.message ?: "unreachable"})]\r\n")
                        }
                        throw e
                    }
                } else ssh.connect(targetHost, targetPort)


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
            chain?.takeIf { it.jumpHosts.isNotEmpty() }?.let { onStatus("[Chain: ${it.hops.joinToString(" \u2192 ") { h -> h.name + " \u2713" }}]\r\n") }
            ssh
        } catch (error: Exception) {
            if (chainReported.not() && chainRef?.jumpHosts?.isNotEmpty() == true && error !is JumpChainException) {
                val hops = chainRef!!.hops
                val reason = if (error is net.schmizz.sshj.userauth.UserAuthException) "auth failed" else error.message ?: error.javaClass.simpleName
                onStatus("[Chain: ${hops.indices.joinToString(" \u2192 ") { i -> hops[i].name + if (i < hops.size - 1) " \u2713" else " \u2717" }} (${hops.last().name}: $reason)]\r\n")
            }
            disconnect()
            sshClient = null
            tunnel = null
            throw error
        }
    }
}
