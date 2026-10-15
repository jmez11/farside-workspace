package com.farside.workspace.ssh

import android.content.Context
import android.os.Looper
import androidx.annotation.StringRes
import com.farside.workspace.R
import com.farside.workspace.localizedMessage
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
    private fun text(@StringRes resId: Int, english: String, vararg args: Any): String =
        context?.getString(resId, *args) ?: english
    private fun reason(error: Throwable): String? = context?.let { error.localizedMessage(it) } ?: error.message
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
            check(!closed.get()) { owner.text(R.string.ssh_connection_closed, "SSH connection closed") }
            this.onExit = onExit
            return owner.connect(this, profile, onStatus, interactive, null, true)
        }
        /** Connects one hop of a jump chain, tunnelled through [via] when non-null; never resolves further jumps. */
        internal fun connectHop(profile: ConnectionProfile, via: SSHClient?, onStatus: (String) -> Unit, onExit: () -> Unit): SSHClient {
            check(!closed.get()) { owner.text(R.string.ssh_connection_closed, "SSH connection closed") }
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
        val state = chain.hops.map { text(R.string.ssh_hop_pending, "…") }.toMutableList()
        fun progress() = labels.indices.joinToString(text(R.string.jump_route_separator, " → ")) { text(R.string.ssh_hop_state, "${labels[it]} ${state[it]}", labels[it], state[it]) }
        var upstream: SSHClient? = null
        chain.jumpHosts.forEachIndexed { index, hop ->
            val hopLease = acquire(context, "jumphost-${hop.id}")
            synchronized(hopLeases) { hopLeases.add(hopLease) }
            onStatus(text(R.string.ssh_jump_progress, "[Jump host ${index + 1}/${chain.jumpHosts.size}: ${hop.name}]\r\n", index + 1, chain.jumpHosts.size, hop.name))
            try {
                upstream = hopLease.connectHop(hop, upstream, { onStatus(it.replace("[", text(R.string.ssh_hop_prefix, "[${hop.name}: ", hop.name))) }, { notifyExit() })
                state[index] = text(R.string.ssh_hop_success, "✓")
            } catch (e: Exception) {
                state[index] = text(R.string.ssh_hop_failure, "✗")
                val reason = if (e is net.schmizz.sshj.userauth.UserAuthException) text(R.string.ssh_auth_failed_short, "auth failed") else reason(e) ?: e.javaClass.simpleName
                onStatus(text(R.string.ssh_chain_failure, "[Chain: ${progress()} (${hop.name}: $reason)]\r\n", progress(), hop.name, reason))
                throw JumpChainException(R.string.ssh_jump_failed, hop.name, reason)
            }
        }
        return checkNotNull(upstream)
    }
    private fun connect(lease: Lease, profile: ConnectionProfile, onStatus: (String) -> Unit,
                        interactive: ((Boolean) -> String?)?, viaParam: SSHClient?, resolveJumps: Boolean): SSHClient = synchronized(connectLock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { text(R.string.ssh_io_main_thread, "SSH IO must run off the main thread") }
        check(!closed.get() && !lease.closed.get()) { text(R.string.ssh_connection_closed, "SSH connection closed") }
        sshClient?.takeIf { it.isConnected && it.isAuthenticated }?.let {
            val previous = checkNotNull(connectedProfile)
            check(previous.host == profile.host && previous.port == profile.port && previous.user == profile.user &&
                previous.pass == profile.pass && previous.sshKeyId == profile.sshKeyId && previous.credentialId == profile.credentialId &&
                previous.proxyType == profile.proxyType && previous.useCloudflare == profile.useCloudflare &&
                previous.proxyHost == profile.proxyHost && previous.proxyPort == profile.proxyPort &&
                previous.proxyUser == profile.proxyUser && previous.proxyPass == profile.proxyPass &&
                previous.cloudflaredParameters == profile.cloudflaredParameters &&
                (!resolveJumps || previous.jumpHostProfileId == profile.jumpHostProfileId)) { text(R.string.ssh_settings_changed, "Connection settings changed. Close existing sessions and reconnect.") }
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
                    chain.warning?.let { val warning = context.getString(it); onStatus(text(R.string.ssh_warning, "[Warning: $warning]\r\n", warning)) }
                    if (chain.jumpHosts.isNotEmpty()) via = connectJumpHosts(chain, onStatus)
                }
                var targetHost = profile.host
                var targetPort = profile.port

                val useCloudflared = (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED) && via == null
                if (via != null && (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED)) {
                    onStatus(text(R.string.ssh_tunnel_ignored, "[Cloudflare tunnel ignored: this host is reached through a jump host]\r\n"))
                }
                if (via != null && profile.proxyType in listOf(ProxyType.HTTP, ProxyType.SOCKS5)) {
                    throw IllegalArgumentException("Connection proxy cannot be combined with an SSH jump host")
                }
                if (useCloudflared && context != null) {
                    onStatus(text(R.string.ssh_tunnel_starting, "[Starting Cloudflare Access SSH tunnel...]\r\n"))
                    val cfTunnel = CloudflaredTunnel(context)
                    tunnel = cfTunnel
                    val tunnelPort = cfTunnel.start(
                        hostInput = profile.host,
                        parameters = profile.cloudflaredParameters,
                        onStatus = { status -> onStatus(text(R.string.ssh_status_line, "[$status]\r\n", status)) },
                        onExit = {
                            if (!closed.get()) {
                                onStatus(text(R.string.ssh_tunnel_process_exited, "\r\n[Cloudflared tunnel process exited]\r\n"))
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
                ssh.connectTimeout = 10_000
                if (via == null && profile.proxyType in listOf(ProxyType.HTTP, ProxyType.SOCKS5) && !profile.useCloudflare) {
                    val proxyHost = profile.proxyHost?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw com.farside.workspace.LocalizableException(if (profile.proxyType == ProxyType.SOCKS5) R.string.socks5_proxy_host_port_required else R.string.http_proxy_host_port_required)
                    val proxyPort = profile.proxyPort?.takeIf { it in 1..65535 }
                        ?: throw com.farside.workspace.LocalizableException(if (profile.proxyType == ProxyType.SOCKS5) R.string.socks5_proxy_host_port_required else R.string.http_proxy_host_port_required)
                    ssh.socketFactory = if (profile.proxyType == ProxyType.SOCKS5)
                        Socks5SocketFactory(proxyHost, proxyPort, profile.proxyUser, profile.proxyPass)
                    else HttpConnectSocketFactory(proxyHost, proxyPort, profile.proxyUser, profile.proxyPass)
                    onStatus("[Connecting through ${profile.proxyType} proxy $proxyHost:$proxyPort...]\r\n")
                } else if (via == null && profile.proxyType !in listOf(ProxyType.NONE, ProxyType.CLOUDFLARED)) {
                    throw IllegalArgumentException(text(R.string.proxy_connection_not_supported,
                        "${profile.proxyType} proxy connections are not supported yet. Select None or Cloudflared.",
                        context?.getString(profile.proxyType.labelResId) ?: profile.proxyType.name))
                }

                if (via != null) {
                    try {
                        ssh.connectVia(via.newDirectConnection(profile.host.substringAfter("@"), profile.port))
                    } catch (e: Exception) {
                        if (chain != null) {
                            chainReported = true
                            val route = chain.hops.indices.joinToString(text(R.string.jump_route_separator, " → ")) { i ->
                                val state = if (i < chain.hops.size - 1) text(R.string.ssh_hop_success, "✓") else text(R.string.ssh_hop_failure, "✗")
                                text(R.string.ssh_hop_state, "${chain.hops[i].name} $state", chain.hops[i].name, state)
                            }
                            val reason = reason(e) ?: text(R.string.ssh_unreachable, "unreachable")
                            onStatus(text(R.string.ssh_chain_failure, "[Chain: $route (${profile.name}: $reason)]\r\n", route, profile.name, reason))
                        }
                        throw e
                    }
                } else ssh.connect(targetHost, targetPort)


                var user = profile.user.trim()
                if (user.isEmpty() && profile.host.contains("@")) {
                    user = profile.host.substringBefore("@").trim()
                }
                if (user.isEmpty()) {
                    if (interactive == null) throw net.schmizz.sshj.userauth.UserAuthException(text(R.string.ssh_username_required, "A saved SSH username is required."))
                    onStatus(text(R.string.ssh_login_prompt, "login as: "))
                    user = interactive?.invoke(true)?.trim() ?: throw java.util.concurrent.CancellationException(text(R.string.ssh_sign_in_cancelled, "SSH sign-in cancelled"))
                    if (user.isEmpty()) {
                        onStatus(text(R.string.ssh_username_empty, "No username provided.\r\n"))
                        throw java.util.concurrent.CancellationException(text(R.string.ssh_sign_in_cancelled, "SSH sign-in cancelled"))
                    }
                }

                var authenticated = false

                // 1. Try public key authentication if SSH key is selected
                val savedKey = profile.sshKeyId?.let { keyId ->
                    context?.let { ctx -> ProfileManager(ctx).getSshKeys().find { it.id == keyId } }
                }
                if (savedKey != null && savedKey.privateKey.isNotBlank()) {
                    onStatus(text(R.string.ssh_key_offering, "[Offering SSH key '${savedKey.name}' for user '$user'...]\r\n", savedKey.name, user))
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
                            onStatus(text(R.string.ssh_key_accepted, "[SSH key '${savedKey.name}' accepted by server]\r\n", savedKey.name))
                        } else {
                            val allowed = try { ssh.userAuth.allowedMethods } catch (e: Exception) { null }
                            val methods = if (allowed != null) text(R.string.ssh_allowed_methods, " (allowed methods: $allowed)", allowed) else ""
                            onStatus(text(R.string.ssh_key_refused, "[Server refused key '${savedKey.name}' for user '$user'$methods]\r\n", savedKey.name, user, methods))
                        }
                    } catch (e: Exception) {
                        val allowed = try { ssh.userAuth.allowedMethods } catch (ex: Exception) { null }
                        val reason = if (e is net.schmizz.sshj.userauth.UserAuthException) {
                            val methods = if (allowed != null) text(R.string.ssh_allowed_methods, " (allowed methods: $allowed)", allowed) else ""
                            text(R.string.ssh_key_refused_reason, "Key refused by server$methods", methods)
                        } else {
                            reason(e) ?: text(R.string.ssh_auth_failed, "Authentication failed")
                        }
                        onStatus(text(R.string.ssh_key_refused_detail, "[Server refused SSH key '${savedKey.name}' for user '$user': $reason]\r\n", savedKey.name, user, reason))
                    }
                } else if (savedKey != null && savedKey.privateKey.isBlank()) {
                    onStatus(text(R.string.ssh_key_empty, "[Notice: SSH key '${savedKey.name}' has no private key data]\r\n", savedKey.name))
                } else if (!profile.sshKeyId.isNullOrBlank()) {
                    onStatus(text(R.string.ssh_key_missing, "[Notice: Configured SSH key not found in saved keys]\r\n"))
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
                        onStatus(text(R.string.ssh_password_prompt, "$user@$targetHost's password: ", user, targetHost))
                        val enteredPass = interactive?.invoke(false) ?: throw net.schmizz.sshj.userauth.UserAuthException(text(R.string.ssh_credential_check, "Check the saved credential or SSH key."))
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
                            onStatus(text(R.string.ssh_permission_denied, "Permission denied, please try again.\r\n"))
                        }
                    }
                }

                if (!authenticated) {
                    throw net.schmizz.sshj.userauth.UserAuthException(text(R.string.ssh_credential_check, "Check the saved credential or SSH key."))
                }


            check(!closed.get() && !lease.closed.get()) { text(R.string.ssh_connection_closed, "SSH connection closed") }
            connectedProfile = profile
            chain?.takeIf { it.jumpHosts.isNotEmpty() }?.let {
                val route = it.hops.joinToString(text(R.string.jump_route_separator, " → ")) { h ->
                    val state = text(R.string.ssh_hop_success, "✓")
                    text(R.string.ssh_hop_state, "${h.name} $state", h.name, state)
                }
                onStatus(text(R.string.ssh_chain_success, "[Chain: $route]\r\n", route))
            }
            ssh
        } catch (error: Exception) {
            if (chainReported.not() && chainRef?.jumpHosts?.isNotEmpty() == true && error !is JumpChainException) {
                val hops = chainRef!!.hops
                val reason = if (error is net.schmizz.sshj.userauth.UserAuthException) text(R.string.ssh_auth_failed_short, "auth failed") else reason(error) ?: error.javaClass.simpleName
                val route = hops.indices.joinToString(text(R.string.jump_route_separator, " → ")) { i ->
                    val state = if (i < hops.size - 1) text(R.string.ssh_hop_success, "✓") else text(R.string.ssh_hop_failure, "✗")
                    text(R.string.ssh_hop_state, "${hops[i].name} $state", hops[i].name, state)
                }
                onStatus(text(R.string.ssh_chain_failure, "[Chain: $route (${hops.last().name}: $reason)]\r\n", route, hops.last().name, reason))
            }
            disconnect()
            sshClient = null
            tunnel = null
            throw error
        }
    }
}
