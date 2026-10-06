package com.multidesk.explorer

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONArray

/** Seven numeric pose components; no Android/Horizon objects survive in preferences. */
internal data class SavedWindowPose(val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float) {
    fun encode(): String = JSONArray(listOf(x, y, z, qx, qy, qz, qw)).toString()
    fun platformPose(namespace: String): Any {
        val scalar = Float::class.javaPrimitiveType!!
        val vector = Class.forName("$namespace.graphics.Vector3f")
        val rotation = Class.forName("$namespace.graphics.Vector4f")
        val position = vector.getConstructor(scalar, scalar, scalar).newInstance(x, y, z)
        val orientation = rotation.getConstructor(scalar, scalar, scalar, scalar).newInstance(qx, qy, qz, qw)
        return Class.forName("$namespace.graphics.Pose").getConstructor(rotation, vector).newInstance(orientation, position)
    }
    companion object {
        fun read(pose: Any): SavedWindowPose {
            val position = pose.javaClass.getMethod("getPosition").invoke(pose)
            val rotation = pose.javaClass.getMethod("getOrientation").invoke(pose)
            fun component(value: Any, name: String) = (value.javaClass.getMethod(name).invoke(value) as Number).toFloat()
            return SavedWindowPose(component(position, "getX"), component(position, "getY"), component(position, "getZ"),
                component(rotation, "getX"), component(rotation, "getY"), component(rotation, "getZ"), component(rotation, "getW"))
        }
        fun decode(value: String): SavedWindowPose? = try {
            val json = JSONArray(value)
            require(json.length() == 7)
            val n = (0..6).map { json.getDouble(it).toFloat().also { v -> require(v.isFinite()) } }
            val length = n.drop(3).sumOf { (it * it).toDouble() }
            require(length in 0.9..1.1)
            SavedWindowPose(n[0], n[1], n[2], n[3], n[4], n[5], n[6])
        } catch (_: Exception) { null }
    }
}

internal class SessionWindowPositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
    fun load(profile: String, slot: Int): SavedWindowPose? = prefs.getString("$profile/$slot", null)?.let(SavedWindowPose::decode)
    fun save(profile: String, slot: Int, pose: SavedWindowPose, sync: Boolean = false) {
        val edit = prefs.edit().putString("$profile/$slot", pose.encode())
        if (sync) edit.commit() else edit.apply()
    }
}

/** Ordinals distinguish simultaneous windows for the same profile, without reusing an occupied slot. */
internal object SessionWindowSlots {
    const val EXTRA_SLOT = "sessionWindowSlot"
    private val occupied = mutableSetOf<Pair<String, Int>>()
    @Synchronized fun reserve(context: Context, profile: String, excludeTask: Int = -1): Int {
        val running = context.getSystemService(ActivityManager::class.java).appTasks.map { it.taskInfo }
            .filter { it.taskId != excludeTask && it.isRunning && it.baseIntent.component?.className == SessionActivity::class.java.name && it.baseIntent.getStringExtra("profileId") == profile }
            .map { it.baseIntent.getIntExtra(EXTRA_SLOT, 0) }.toSet()
        var slot = 0
        while ((profile to slot) in occupied || slot in running) slot++
        occupied += profile to slot
        return slot
    }
    @Synchronized fun claim(profile: String, slot: Int) { occupied += profile to slot }
    @Synchronized fun release(profile: String, slot: Int) { occupied -= profile to slot }
}

internal object SessionWindowLauncher {
    fun intent(context: Context, profile: String, slot: Int) = Intent(context, SessionActivity::class.java).apply {
        data = android.net.Uri.parse("multidesk://session/$profile/$slot")
        putExtra("profileId", profile)
        putExtra(SessionWindowSlots.EXTRA_SLOT, slot)
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    fun options(pose: SavedWindowPose?): Bundle? {
        if (pose == null) return null
        for (namespace in listOf("horizonos", "metavr")) try {
            val value = pose.platformPose(namespace)
            val options = ActivityOptions.makeBasic()
            Class.forName("$namespace.app.ActivityOptionsExt").getMethod("setPose", ActivityOptions::class.java, value.javaClass)
                .invoke(null, options, value)
            Log.i("SessionWindow", "Requested saved pose in launch options ($namespace)")
            return options.toBundle()
        } catch (error: Exception) { Log.d("SessionWindow", "Launch pose unavailable ($namespace): ${error.javaClass.simpleName}") }
        return null
    }

    fun launch(activity: Activity, profile: String) {
        val slot = SessionWindowSlots.reserve(activity, profile)
        val intent = intent(activity, profile, slot)
        val options = options(SessionWindowPositionStore(activity).load(profile, slot))
        try {
            try { activity.startActivity(intent, options) }
            catch (error: RuntimeException) {
                if (options == null) throw error
                Log.w("SessionWindow", "OS rejected launch options; using default placement", error)
                activity.startActivity(intent)
            }
        } catch (error: RuntimeException) {
            SessionWindowSlots.release(profile, slot)
            throw error
        }
    }
}

/** Save observed Home poses; issue one unprivileged restoration request per Activity lifetime. */
internal class SessionWindowPlacement(private val activity: Activity, private val profile: String, val slot: Int) {
    private val store = SessionWindowPositionStore(activity)
    private val saved = store.load(profile, slot)
    private var latest: SavedWindowPose? = null
    private var restoreAttempted = false

    fun observe(pose: Any) {
        try {
            latest = SavedWindowPose.read(pose)
            store.save(profile, slot, latest!!)
        } catch (error: Exception) { Log.w("SessionWindow", "Cannot save panel pose", error) }
    }

    fun restoreOnce() {
        if (restoreAttempted) return
        restoreAttempted = true
        if (saved == null) return
        for (namespace in listOf("horizonos", "metavr")) try {
            val managerClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(managerClass) ?: continue
            val token = Class.forName("$namespace.app.ActivityExt").getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity) ?: continue
            val pose = saved.platformPose(namespace)
            val builderClass = Class.forName("$namespace.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            builderClass.getMethod("setPose", token.javaClass, pose.javaClass).invoke(builder, token, pose)
            val transaction = builderClass.getMethod("build").invoke(builder)
            managerClass.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
            Log.i("SessionWindow", "Requested saved pose for session slot $slot; Home may adjust or deny it")
            return
        } catch (error: Exception) { Log.w("SessionWindow", "Cannot restore pose ($namespace); keeping Home placement", error) }
    }

    fun saveOnClose() {
        latest?.let { store.save(profile, slot, it, sync = true) }
    }
}
