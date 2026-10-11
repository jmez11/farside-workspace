package com.farside.workspace

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.util.Log

/** Bring the single connection manager to the session's current spatial location. */
internal object LauncherWindowLauncher {
    fun show(session: Activity) {
        // Clearing the Activity stack still reuses Home's old spatial window/pose.
        // Remove that launcher window before allocating its replacement at this session.
        session.getSystemService(android.app.ActivityManager::class.java).appTasks
            .filter { it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name }
            .forEach { it.finishAndRemoveTask() }
        val intent = Intent(session, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        session.startActivity(intent, placement(session))
    }

    internal fun placement(session: Activity): Bundle? {
        for (namespace in listOf("horizonos", "metavr")) {
            try {
                val managerClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowManager")
                val manager = session.getSystemService(managerClass) ?: continue
                val tokenClass = Class.forName("$namespace.view.VolumetricWindowToken")
                val token = Class.forName("$namespace.app.ActivityExt")
                    .getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, session) ?: continue
                val info = managerClass.getMethod("getWindowInfo", tokenClass).invoke(manager, token) ?: continue
                val pose = info.javaClass.getMethod("getPose").invoke(info)
                val orientation = pose.javaClass.getMethod("getOrientation").invoke(pose)
                val position = pose.javaClass.getMethod("getPosition").invoke(pose)
                fun component(value: Any, name: String) = (value.javaClass.getMethod(name).invoke(value) as Number).toFloat()
                val target = inFront(floatArrayOf(component(position, "getX"), component(position, "getY"), component(position, "getZ"),
                    component(orientation, "getX"), component(orientation, "getY"), component(orientation, "getZ"), component(orientation, "getW")))
                val scalar = Float::class.javaPrimitiveType!!
                val vector = Class.forName("$namespace.graphics.Vector3f")
                val translation = vector.getConstructor(scalar, scalar, scalar).newInstance(target[0], target[1], target[2])
                val value = pose.javaClass.getConstructor(orientation.javaClass, vector).newInstance(orientation, translation)
                val options = ActivityOptions.makeBasic()
                Class.forName("$namespace.app.ActivityOptionsExt")
                    .getMethod("setPose", ActivityOptions::class.java, pose.javaClass).invoke(null, options, value)
                return options.toBundle()
            } catch (error: ReflectiveOperationException) {
                Log.w("LauncherWindow", "$namespace launch placement unavailable", error)
            }
        }
        return null
    }

    // The panel's local +Z normal points toward its viewer. Preserve its orientation.
    internal fun inFront(pose: FloatArray): FloatArray = pose.copyOf().apply {
        val x = pose[3]; val y = pose[4]; val z = pose[5]; val w = pose[6]
        this[0] += 0.15f * 2f * (x * z + w * y)
        this[1] += 0.15f * 2f * (y * z - w * x)
        this[2] += 0.15f * (1f - 2f * (x * x + y * y))
    }
}
