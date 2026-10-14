package com.farside.workspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.farside.workspace.data.ProfileManager
import java.io.File
import java.util.UUID

/** Tracks connected windows and their focus order within this process. */
internal object SessionCardActions {
    private val profiles = mutableMapOf<SessionActivity, String>()
    private val activeAt = mutableMapOf<SessionActivity, Long>()
    val connectedCounts = mutableStateMapOf<String, Int>()
    private var sequence = 0L

    fun register(activity: SessionActivity, profile: String) {
        profiles[activity] = profile
        focused(activity)
        publish(profile)
    }

    fun connected(activity: SessionActivity, connected: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { connected(activity, connected) }
            return
        }
        val profile = profiles[activity] ?: return
        activity.cardConnected = connected
        publish(profile)
    }

    fun focused(activity: SessionActivity) {
        if (activity in profiles) activeAt[activity] = ++sequence
    }

    fun unregister(activity: SessionActivity) {
        val profile = profiles.remove(activity)
        activeAt.remove(activity)
        profile?.let(::publish)
    }

    private fun publish(profile: String) {
        connectedCounts[profile] = profiles.count { (activity, id) ->
            id == profile && activity.cardConnected && !activity.isDestroyed && !activity.isFinishing
        }
    }

    private fun latest(profile: String): SessionActivity? = profiles.keys
        .filter { profiles[it] == profile && it.cardConnected && !it.isDestroyed && !it.isFinishing }
        .maxByOrNull { activeAt[it] ?: 0L }

    internal fun selectedTask(profile: String): Int? = latest(profile)?.taskId

    fun capture(profile: String, onResult: (Boolean) -> Unit) {
        val activity = latest(profile) ?: return onResult(false)
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return onResult(false)
        fun texture(node: View): TextureView? {
            if (node is TextureView) return node
            if (node is ViewGroup) for (index in 0 until node.childCount)
                texture(node.getChildAt(index))?.let { return it }
            return null
        }
        val terminal = activity.sshCaptureView?.takeIf { it.width > 0 && it.height > 0 }
        val surface = if (terminal == null) texture(view) else null
        val bitmap = when {
            terminal != null -> Bitmap.createBitmap(terminal.width, terminal.height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(android.graphics.Color.BLACK)
                terminal.draw(canvas)
            }
            surface != null -> runCatching { surface.bitmap }.getOrNull()
            else -> Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        if (bitmap == null || !activity.cardConnected || !hasContent(bitmap)) {
            bitmap?.recycle()
            onResult(false)
            return
        }
        Thread {
                val directory = File(activity.filesDir, "card-backgrounds").apply { mkdirs() }
                val name = UUID.randomUUID().toString() + ".jpg"
                val file = File(directory, name)
                val scale = minOf(1f, 640f / bitmap.width)
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                val saved = runCatching {
                    file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                    val manager = ProfileManager(activity.applicationContext)
                    val current = manager.getProfiles().firstOrNull { it.id == profile } ?: error("Profile removed")
                    manager.updateProfile(current.copy(cardBackground = name))
                    current.cardBackground?.let { File(directory, it).delete() }
                }.isSuccess
                if (!saved) file.delete()
                scaled.recycle()
                bitmap.recycle()
                Handler(Looper.getMainLooper()).post { onResult(saved) }
        }.start()
    }

    private fun hasContent(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return false
        val row = IntArray(width)
        for (y in 0 until height) {
            if (y >= 96 && y % 4 != 0) continue
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            if (row.any { it and 0x00ffffff != 0 }) return true
        }
        return false
    }
}
