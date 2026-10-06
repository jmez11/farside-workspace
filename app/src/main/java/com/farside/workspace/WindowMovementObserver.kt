package com.farside.workspace

import android.app.Activity
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Observe the Home-owned panel pose without requesting global window-management access.
 * Horizon APIs live in the headset framework; reflection keeps ordinary Android builds runnable.
 */
internal class WindowMovementObserver(private val activity: Activity, private val onMoved: () -> Unit, private val onPose: (Any) -> Unit = {}) {
    private var removeListener: (() -> Unit)? = null

    fun start() {
        if (removeListener != null) return
        for (namespace in listOf("horizonos", "metavr")) {
            try {
                val managerClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowManager")
                val manager = activity.getSystemService(managerClass) ?: continue
                val listenerClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowManager\$OnWindowChangeListener")
                val token = Class.forName("$namespace.app.ActivityExt")
                    .getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity) ?: continue
                val infoClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowInfo")
                val getToken = infoClass.getMethod("getVolumetricWindowToken")
                val getChildren = infoClass.getMethod("getChildren")
                val getPose = infoClass.getMethod("getPose")
                var lastPose: Any? = null
                fun observe(windows: List<*>) {
                    for (info in windows.filterNotNull()) {
                        if (getToken.invoke(info) == token) {
                            val pose = getPose.invoke(info)
                            if (lastPose != null && pose != lastPose) {
                                Log.d("SessionWindow", "Panel pose changed")
                                onMoved()
                            }
                            if (pose != null) onPose(pose)
                            lastPose = pose
                            return
                        }
                        observe(getChildren.invoke(info) as List<*>)
                    }
                }
                val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                    when (method.name) {
                        "onWindowsChanged" -> {
                            try { if (!activity.isDestroyed) observe(args!![0] as List<*>) }
                            catch (error: ReflectiveOperationException) { Log.w("SessionWindow", "Cannot read panel pose", error) }
                            null
                        }
                        "equals" -> proxy === args?.get(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "SessionWindowMovementListener"
                        else -> null
                    }
                }
                managerClass.getMethod("addOnWindowChangeListener", Activity::class.java, Executor::class.java, listenerClass)
                    .invoke(manager, activity, activity.mainExecutor, listener)
                removeListener = {
                    managerClass.getMethod("removeOnWindowChangeListener", Activity::class.java, listenerClass)
                        .invoke(manager, activity, listener)
                }
                Log.i("SessionWindow", "Panel movement observer registered ($namespace)")
                return
            } catch (error: ReflectiveOperationException) {
                Log.d("SessionWindow", "$namespace panel observation unavailable", error)
            }
        }
        Log.w("SessionWindow", "Panel movement observation unavailable on this OS")
    }

    fun close() {
        try { removeListener?.invoke() }
        catch (error: ReflectiveOperationException) { Log.w("SessionWindow", "Cannot remove panel observer", error) }
        removeListener = null
    }
}
