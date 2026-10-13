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
    private var instance = 0L
    private var surface: Surface? = null
    private val tunnel = if (profile.useCloudflare || profile.proxyType == ProxyType.CLOUDFLARED) CloudflaredTunnel(this.context) else null

    init {
        bridge.disconnectListener = { reason, established ->
            tunnel?.close()
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
                if (!profile.useCloudflare && profile.proxyType !in listOf(ProxyType.NONE, ProxyType.CLOUDFLARED)) {
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
                if (tunnel != null) try {
                    port = tunnel.start(profile.host, profile.cloudflaredParameters,
                        { status -> main.post { if (!closed.get()) onStatus(status) } },
                        {
                            if (ended.compareAndSet(false, true)) main.post {
                                if (!closed.get()) {
                                    onDisconnected(context.getString(R.string.cloudflared_tunnel_exited), connected.get())
                                    if (!connected.get()) onConnected(0L)
                                }
                            }
                        }, accessSubcommand = "tcp")
                    host = "127.0.0.1"
                } catch (error: Exception) {
                    android.util.Log.w("Cloudflared", "Tunnel setup failed: ${error.message}")
                    tunnel.close()
                    next.release()
                    ended.set(true)
                    main.post { if (!closed.get()) {
                        onStatus("")
                        onDisconnected(error.localizedMessage(context) ?: context.getString(R.string.cloudflared_connection_failed), false)
                        onConnected(0L)
                    } }
                    return@execute
                }
                if (closed.get() || ended.get()) { next.release(); return@execute }
                val credential = profile.credentialId?.let { id ->
                    com.farside.workspace.data.ProfileManager(context).getCredentials().find { it.id == id }
                }
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
                instance = try { bridge.connect(host, port, credential.pass, next, credential.user, profile.vncAuthMode) }
                    catch (error: VncAuthenticationException) { failure = context.getString(R.string.vnc_mac_auth_failed); 0L }
                    catch (error: IllegalArgumentException) {
                        failure = error.localizedMessage(context) ?: context.getString(R.string.invalid_vnc_password); 0L
                    }
                    catch (error: Exception) { android.util.Log.w("VncSession", "Connection failed", error); 0L }
                if (instance == 0L) {
                    ended.set(true)
                    main.post { if (!closed.get()) onDisconnected(
                        failure ?: context.getString(R.string.vnc_connection_failed_help), false) }
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bridge.disconnectListener = null
        tunnel?.close() // Cancel browser authentication immediately, before worker teardown.
        worker.execute {
            if (instance != 0L) bridge.disconnect(instance)
            instance = 0L
            surface?.release()
            surface = null
        }
        worker.shutdown()
    }
}
