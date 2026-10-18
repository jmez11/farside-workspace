package com.farside.workspace

import android.app.Activity
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID

/** Bind each owned audio session to Home's tracked window space; Home supplies head tracking. */
internal object QuestSpatialAudio {
    private const val TAG = "QuestSpatialAudio"
    private const val SERVICE = "horizonos.media.IHorizonAudioService"
    private const val SESSION = "horizonos.media.IAudioSessionExt"
    private data class Binding(val space: UUID, val endpoint: IBinder)
    private val bindings = mutableMapOf<Int, Binding>()
    private val failures = mutableMapOf<Int, String?>()
    private var service: IBinder? = null

    fun windowSpace(activity: Activity): UUID? {
        for (namespace in listOf("horizonos", "metavr")) try {
            val managerClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(managerClass) ?: continue
            val tokenClass = Class.forName("$namespace.view.VolumetricWindowToken")
            val token = Class.forName("$namespace.app.ActivityExt")
                .getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity) ?: continue
            val info = managerClass.getMethod("getWindowInfo", tokenClass).invoke(manager, token) ?: continue
            return info.javaClass.getMethod("getHzSpaceUuid").invoke(info) as? UUID
        } catch (_: ReflectiveOperationException) { /* Ordinary Android/older Horizon: framework fallback. */ }
        return null
    }

    /** OS-private HAS interface, checked by descriptor. Failures preserve ordinary audio playback. */
    @Synchronized fun bind(sessionId: Int, space: UUID): Boolean {
        if (sessionId <= 0) return false
        val current = bindings[sessionId]
        if (current?.space == space && current.endpoint.isBinderAlive) return true
        return try {
            val endpoint = current?.endpoint?.takeIf { it.isBinderAlive } ?: sessionEndpoint(sessionId)
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SESSION)
                // UUID's RFC 4122 byte order, not Parcel/host integer byte order.
                data.writeByteArray(ByteBuffer.allocate(16).putLong(space.mostSignificantBits)
                    .putLong(space.leastSignificantBits).array())
                check(endpoint.transact(2, data, reply, 0)) { "setSpaceToken unavailable" }
                reply.readException()
            } finally { data.recycle(); reply.recycle() }
            bindings[sessionId] = Binding(space, endpoint)
            failures.remove(sessionId)
            Log.i(TAG, "Bound audio session=$sessionId to window space=$space")
            true
        } catch (error: Exception) {
            bindings.remove(sessionId)
            if (!failures.containsKey(sessionId) || failures[sessionId] != error.message) {
                Log.w(TAG, "Window audio routing unavailable for session=$sessionId: ${error.message}")
                failures[sessionId] = error.message
            }
            false
        }
    }
    private fun sessionEndpoint(sessionId: Int): IBinder {
        val owner = service?.takeIf { it.isBinderAlive } ?: run {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "media.has") as? IBinder ?: error("Horizon audio service unavailable")
            check(binder.interfaceDescriptor == SERVICE) { "Unknown Horizon audio interface" }
            service = binder
            binder
        }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        val endpoint = try {
            data.writeInterfaceToken(SERVICE); data.writeInt(sessionId)
            // getSessionExt is scoped by HAS to the caller's process and an existing audio session.
            check(owner.transact(1, data, reply, 0)) { "getSessionExt unavailable" }
            reply.readException()
            checkNotNull(reply.readStrongBinder()) { "Audio session is not ready" }
        } finally { data.recycle(); reply.recycle() }
        check(endpoint.interfaceDescriptor == SESSION) { "Unknown audio session interface" }
        return endpoint
    }
    @Synchronized fun forget(sessionId: Int) { bindings.remove(sessionId); failures.remove(sessionId) }
}
