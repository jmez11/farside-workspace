package com.farside.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.tunnel.CloudflaredTunnel
import com.farside.workspace.data.ProxyType
import com.farside.workspace.data.VncAuthMode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes connect, surface replacement and teardown; resizing never closes the VNC session. */
internal class VncSessionConnection(
    context: Context,
    private val profile: ConnectionProfile,
    private val bridge: VncBridge,
    private val onDisconnected: (String, Boolean) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onConnected: (Long) -> Unit,
) : DesktopSessionConnection {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    // Accessed only on worker.
    @Volatile internal var macRoute:MacConnectionPolicy.Route?=null;private set
    private var instance = 0L
    private var surface: Surface? = null
    private var mediaRelay: MacHevcSshRelay? = null
    private var mediaLease: com.farside.workspace.ssh.SshConnectionManager.Lease? = null
    private fun closeMediaRelay() {
        runCatching { mediaRelay?.close() }; mediaRelay=null
        mediaLease?.close(); mediaLease=null
    }
    private val tunnel = if (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED) CloudflaredTunnel(this.context) else null

    init {
        bridge.disconnectListener = listener@ { reason, established ->
            if(!connected.get()) return@listener
            tunnel?.close()
            runCatching { worker.execute {
                if(instance!=0L) bridge.disconnect(instance)
                instance=0L
                closeMediaRelay()
            } }
            if (ended.compareAndSet(false, true)) main.post { if (!closed.get()) onDisconnected(context.getString(reason), established) }
        }
    }

    override fun attach(next: Surface) {
        if (closed.get() || ended.get()) {
            next.release()
            return
        }
        worker.execute {
            if (closed.get() || ended.get()) {
                next.release()
                return@execute
            }
            if (instance == 0L) {
                if (!profile.useCloudflare && profile.proxyType !in listOf(ProxyType.NONE, ProxyType.CLOUDFLARED, ProxyType.HTTP, ProxyType.SOCKS5)) {
                    next.release()
                    ended.set(true)
                    main.post { if (!closed.get()) {
                        onDisconnected(context.getString(R.string.proxy_connection_not_supported, context.getString(profile.proxyType.labelResId)), false)
                        onConnected(0L)
                    } }
                    return@execute
                }
                var host = profile.host
                var port = profile.port
                if (closed.get() || ended.get()) { next.release(); return@execute }
                if (tunnel == null && profile.proxyType == ProxyType.NONE) {
                    host = kotlinx.coroutines.runBlocking {
                        com.farside.workspace.utils.NetworkScanner.resolveVncHost(context, host, port)
                    }
                }
                val credential = profile.credentialId?.let { id ->
                    com.farside.workspace.data.ProfileManager(context).getCredentials().find { it.id == id }
                } ?: if(profile.credentialId==null) com.farside.workspace.data.SavedCredential(
                    id=profile.id,name=profile.name,user=profile.user,pass=profile.pass) else null
                if (profile.credentialId != null && credential == null) {
                    next.release(); ended.set(true); tunnel?.close()
                    main.post { if (!closed.get()) { onDisconnected(context.getString(R.string.choose_another_credential), false); onConnected(0L) } }
                    return@execute
                }
                if (credential == null || credential.pass.isEmpty() ||
                    (profile.vncAuthMode == VncAuthMode.MAC_USER && credential.user.isBlank())) {
                    next.release(); ended.set(true); tunnel?.close()
                    main.post { if (!closed.get()) { onDisconnected(context.getString(
                        if (profile.vncAuthMode == VncAuthMode.MAC_USER) R.string.vnc_mac_credential_required
                        else R.string.choose_vnc_password_credential), false); onConnected(0L) } }
                    return@execute
                }
                var failure: String? = null
                val isMacAuth = profile.vncAuthMode == VncAuthMode.MAC_USER || profile.expMacHevc
                val manager=com.farside.workspace.data.ProfileManager(context)
                val companionId=profile.sftpPairProfileId ?: profile.macSshRelayProfileId
                val companion=manager.getProfiles().firstOrNull { it.id==companionId && it.type==com.farside.workspace.data.ConnectionType.SSH }
                val gate=if(isMacAuth) MacAuthenticationGate { closed.get() } else null
                val directHost=host;val directPort=port
                MacConnectionPolicy.connect(isMacAuth,companion!=null && profile.enableMacSshRelay,
                    // A Cloudflare TCP loopback endpoint cannot carry direct Apple UDP media.
                    // Do not create/discard a virtual Mac login before trying its SSH helper.
                    directMediaAvailable=tunnel==null,
                    cancelled={ closed.get() },
                    terminal={ MacConnectionPolicy.isTerminal(it) || closed.get() },
                    failed={ route,error ->
                        if(route!=MacConnectionPolicy.Route.VNC) bridge.recordMediaFallback()
                        if(instance!=0L) bridge.disconnect(instance)
                        instance=0L;closeMediaRelay()
                        failure=if(error is VncAuthenticationException) context.getString(R.string.vnc_mac_auth_failed)
                            else error.localizedMessage(context) ?: error.message
                        android.util.Log.i("MacConnection","$route unavailable: ${error.javaClass.simpleName}",error)
                    }) { route ->
                    closeMediaRelay()
                    host=directHost;port=directPort
                    // The helper owns its own control channel. Open the separate Cloudflare
                    // VNC tunnel only if ordinary VNC fallback is actually needed.
                    if(route!=MacConnectionPolicy.Route.COMPANION && tunnel!=null) {
                        port=tunnel.start(profile.host,profile.cloudflaredParameters,
                            { status -> main.post {if(!closed.get()) onStatus(status)} },
                            {
                                if(ended.compareAndSet(false,true)) main.post {if(!closed.get()) {
                                    onDisconnected(context.getString(R.string.cloudflared_tunnel_exited),connected.get())
                                    if(!connected.get()) onConnected(0L)
                                }}
                            },accessSubcommand="tcp")
                        host="127.0.0.1"
                    }
                    if(closed.get() || ended.get()) throw InterruptedException("Connection cancelled")
                    var sessionUsername=credential.user
                    if(route==MacConnectionPolicy.Route.COMPANION) {
                        val ssh=checkNotNull(companion)
                        val lease=com.farside.workspace.ssh.SshConnectionManager.acquire(context,ssh.id)
                        mediaLease=lease
                        gate?.await()
                        val client=lease.connect(ssh.copy(reconnectAutomatically=false))
                        sessionUsername=MacSessionIdentity.resolve(client,credential.user)
                        mediaRelay=MacHevcSshRelay(context,client,if(tunnel==null) directHost else profile.host,{ android.util.Log.i("MacMediaRelay",it) },profile.port,sameMac=true)
                        host="127.0.0.1";port=checkNotNull(mediaRelay).port
                    }
                    val streaming=route!=MacConnectionPolicy.Route.VNC
                    val chosen=profile.copy(expMacHevc=streaming,macPreferAvc=true,macAudioEnabled=true,
                        proxyType=if(route==MacConnectionPolicy.Route.COMPANION) ProxyType.NONE else profile.proxyType)
                    instance=bridge.connect(host,port,credential.pass,next,credential.user,profile.vncAuthMode,chosen,
                        macSessionUsername=sessionUsername,macDatagramSender=mediaRelay?.let { relay -> { p,b -> relay.send(p,b) } },
                        macAuthenticationGate=gate?.let { { it.await() } })
                    if(streaming && !bridge.awaitMacVideo(cancelled={closed.get()})) {
                        bridge.recordMediaFallback()
                        val media=bridge.macMediaPipeline
                        android.util.Log.w("MacConnection","$route media timeout: videoPackets=${media?.packetsReceived?.get()} authenticated=${media?.authenticatedPackets?.get()} frames=${media?.framesDecoded?.get()} audioPackets=${media?.audio?.authenticated?.get()} audioPeak=${media?.audio?.peak?.get()}")
                        bridge.disconnect(instance);instance=0L
                        throw java.io.IOException("Mac media did not arrive")
                    }
                    macRoute=route
                    android.util.Log.i("MacConnection","Connected using $route")
                    instance
                }
                if (instance == 0L) {
                    closeMediaRelay()
                    ended.set(true)
                    val fallback = context.getString(R.string.protocol_connection_failed, "VNC")
                    main.post { if (!closed.get()) onDisconnected(
                        failure?.takeIf { it.isNotBlank() } ?: fallback, false) }
                }
                val connected = instance
                this.connected.set(connected != 0L)
                if (connected == 0L) tunnel?.close()
                main.post { if (!closed.get()) { onStatus(""); onConnected(connected) } }
            } else {
                bridge.setSurface(instance, next)
            }
            surface?.release()
            surface = next
        }
    }

    override fun detach() {
        if (closed.get()) return
        worker.execute {
            if (instance != 0L) bridge.setSurface(instance, null)
            surface?.release()
            surface = null
        }
    }

    internal fun awaitClosed(timeoutMs:Long=10000):Boolean =
        worker.awaitTermination(timeoutMs,java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bridge.disconnectListener = null
        tunnel?.close() // Cancel browser authentication immediately, before worker teardown.
        worker.execute {
            if (instance != 0L) bridge.disconnect(instance)
            closeMediaRelay()
            instance = 0L
            surface?.release()
            surface = null
        }
        worker.shutdown()
    }
}
