package com.farside.workspace

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Activity-scoped native geometry; no global-window access or hidden token conversion. */
internal class KeyboardWindowGeometry(context: Context) {
    data class Snapshot(val width: Int, val height: Int, val pose: String,
        val bounds: String)

    @Volatile private var snapshots: Map<String, Snapshot> = emptyMap()
    private var removeListener: (() -> Unit)? = null

    init {
        var owner = context
        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
        val activity = owner as? Activity
        if (activity != null) try {
            val cls = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(cls)
            val listenerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager\$OnWindowChangeListener")
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "onWindowsChanged" -> {
                        try {
                            val next = mutableMapOf<String, Snapshot>()
                            fun collect(windows: List<*>) {
                                for (info in windows.filterNotNull()) {
                                    val token = info.javaClass.getMethod("getVolumetricWindowToken").invoke(info) ?: continue
                                    val size = info.javaClass.getMethod("getWindowSize2d").invoke(info) ?: continue
                                    val pose = info.javaClass.getMethod("getPose").invoke(info)?.toString() ?: ""
                                    val bounds = info.javaClass.getMethod("getBounds").invoke(info)?.toString() ?: ""
                                    id(token)?.let { next[it] = Snapshot((size.javaClass.getMethod("getWidth").invoke(size) as Number).toInt(),
                                        (size.javaClass.getMethod("getHeight").invoke(size) as Number).toInt(),
                                        pose,
                                        bounds) }
                                    collect((info.javaClass.getMethod("getChildren").invoke(info) as? List<*>) ?: emptyList<Any?>())
                                }
                            }
                            collect(args!![0] as List<*>)
                            snapshots = next
                        } catch (error: ReflectiveOperationException) {
                            Log.w("KeyboardTransition", "Cannot read keyboard geometry", error)
                        }
                        null
                    }
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "KeyboardGeometryObserver"
                    else -> null
                }
            }
            cls.getMethod("addOnWindowChangeListener", Activity::class.java, Executor::class.java, listenerClass)
                .invoke(manager, activity, activity.mainExecutor, listener)
            removeListener = { cls.getMethod("removeOnWindowChangeListener", Activity::class.java, listenerClass).invoke(manager, activity, listener) }
        } catch (error: ReflectiveOperationException) {
            Log.w("KeyboardTransition", "Cannot observe keyboard geometry", error)
        }
    }

    fun read(token: Any): Snapshot? = snapshots[id(token)]

    fun close() {
        try { removeListener?.invoke() }
        catch (error: ReflectiveOperationException) { Log.w("KeyboardTransition", "Cannot remove geometry observer", error) }
        removeListener = null
    }

    private fun id(token: Any): String? = Regex("persistableToken = ([a-zA-Z0-9-]+)").find(token.toString())?.groupValues?.get(1)
}
