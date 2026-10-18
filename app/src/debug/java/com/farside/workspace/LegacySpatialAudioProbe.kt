package com.farside.workspace

import android.app.Activity
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Debug-only control for the failed window-token routing approach. Association does not preserve spatial
 * audio for multiple Activity windows, including windows in separate processes with the
 * same app UID. These OS-private calls can be absent and never prove audible routing.
 */
internal object LegacySpatialAudioProbe {
    private const val TAG = "LegacySpatialAudioProbe"

    private var initialized = false
    private var vrosInitMethod: java.lang.reflect.Method? = null
    private val sessionBindings = java.util.concurrent.ConcurrentHashMap<Int, IBinder>()

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true

        try {
            val vrosClass = Class.forName("android.media.VrosSpatialAudio")
            for (method in vrosClass.declaredMethods) {
                if (method.name == "init" && method.parameterTypes.size == 1 && method.parameterTypes[0] == IBinder::class.java) {
                    method.isAccessible = true
                    vrosInitMethod = method
                    Log.i(TAG, "VrosSpatialAudio.init method acquired")
                    break
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "VrosSpatialAudio reflection unavailable: ${e.message}")
        }
    }

    /**
     * Resolves the Horizon OS volumetric window token for the given activity.
     */
    fun resolveVolumetricWindowToken(activity: Activity): IBinder? {
        // 1. Check Activity.mVolumetricToken (direct instance field on Horizon OS android.app.Activity)
        try {
            val field = Activity::class.java.getDeclaredField("mVolumetricToken")
            field.isAccessible = true
            val tok = field.get(activity) as? IBinder
            if (tok != null) {
                Log.i(TAG, "Resolved IBinder directly from Activity.mVolumetricToken: $tok")
                return tok
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Activity.mVolumetricToken check: ${t.message}")
        }

        // 2. Try Horizon OS ActivityExt.getVolumetricWindowToken
        for (namespace in listOf("horizonos", "metavr")) {
            try {
                val actExtClass = Class.forName("$namespace.app.ActivityExt")
                val tokenObj = actExtClass.getMethod("getVolumetricWindowToken", Activity::class.java)
                    .invoke(null, activity) ?: continue
                Log.i(TAG, "Got tokenObj from $namespace.app.ActivityExt: $tokenObj (${tokenObj.javaClass.name})")

                // Try Parcelable write/read: skips 4-byte int scope, reads strong binder
                if (tokenObj is android.os.Parcelable) {
                    val parcel = Parcel.obtain()
                    try {
                        tokenObj.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        val directBinder = parcel.readStrongBinder()
                        if (directBinder != null) {
                            Log.i(TAG, "Unparceled direct IBinder: $directBinder")
                            return directBinder
                        }
                        // Rewind and skip int (scope) then read strong binder (token)
                        parcel.setDataPosition(0)
                        val scope = parcel.readInt()
                        val binderAfterScope = parcel.readStrongBinder()
                        Log.i(TAG, "Parcel read scope=$scope, binderAfterScope=$binderAfterScope")
                        if (binderAfterScope != null) {
                            Log.i(TAG, "Unparceled IBinder after scope: $binderAfterScope")
                            return binderAfterScope
                        }
                    } finally {
                        parcel.recycle()
                    }
                }

                // Try declared fields for IBinder
                var cls: Class<*>? = tokenObj.javaClass
                while (cls != null && cls != Any::class.java) {
                    for (field in cls.declaredFields) {
                        try {
                            field.isAccessible = true
                            val value = field.get(tokenObj)
                            if (value is IBinder) {
                                Log.i(TAG, "Found IBinder in $namespace.${cls.simpleName}.${field.name}: $value")
                                return value
                            }
                        } catch (t: Throwable) {}
                    }
                    cls = cls.superclass
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ActivityExt in $namespace note: ${e.message}")
            }
        }

        // An ordinary Android decor token is not a volumetric token.
        return null
    }

    /**
     * Sets the default volumetric window token on VrosSpatialAudio for the process.
     */
    fun setDefaultVolumetricWindowToken(token: IBinder?) {
        if (token == null) return
        ensureInitialized()

        try {
            vrosInitMethod?.invoke(null, token)
            Log.i(TAG, "VrosSpatialAudio.init called with token: $token")
        } catch (e: Throwable) {
            Log.w(TAG, "VrosSpatialAudio init failed", e)
        }
    }

    init {
        try {
            System.loadLibrary("freerdp-jni")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load freerdp-jni library: ${t.message}")
        }
    }

    @JvmStatic
    private external fun nativeSetVolumetricWindowTokenForSession(sessionId: Int, token: IBinder): Boolean

    /**
     * Associates an audio playback session ID with a specific volumetric window token
     * on the underlying AudioTrack via hidden Android framework API.
     */
    fun setAudioTrackVolumetricWindowToken(sessionId: Int, token: IBinder): Boolean {
        return try {
            nativeSetVolumetricWindowTokenForSession(sessionId, token)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeSetVolumetricWindowTokenForSession error", t)
            false
        }
    }

    /**
     * Associates an audio playback session ID with a specific volumetric window token
     * in the Horizon OS system window manager.
     */
    fun associateSpatialAudio(sessionId: Int, token: IBinder): Boolean {
        val previous = sessionBindings[sessionId]
        val shouldLog = previous != token
        return try {
            val smCls = Class.forName("android.os.ServiceManager")
            val getService = smCls.getMethod("getService", String::class.java)
            val serviceBinder = getService.invoke(null, "interoperable_volumetric_window") as? IBinder ?: return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("internal.horizonos.vwr.IInteroperableVolumetricWindowManager")
                data.writeInt(sessionId)
                data.writeStrongBinder(token)
                data.writeInt(android.os.Process.myPid()) // source PID; this callback is restricted to audioserver
                val res = serviceBinder.transact(24, data, reply, 0)
                if (res) reply.readException()
                if (shouldLog) {
                    Log.i(TAG, "interoperable_volumetric_window.associateSpatialAudio($sessionId, $token) = $res")
                }
                res
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "associateSpatialAudio note: ${e.message}")
            false
        }
    }

    /**
     * Requests native AudioTrack association. Acceptance does not establish HAL routing.
     */
    fun bindAudioSessionToWindow(sessionId: Int, token: IBinder?): Boolean {
        if (sessionId <= 0 || token == null) return false
        if (sessionBindings[sessionId] == token) return true
        ensureInitialized()

        // Transaction 24 is an audioserver callback, not an app-facing fallback. Horizon
        // rejects this UID even when transact() itself returns true.
        val success = setAudioTrackVolumetricWindowToken(sessionId, token)
        if (success) sessionBindings[sessionId] = token
        return success
    }

    fun forget(sessionId: Int) { sessionBindings.remove(sessionId) }
}
