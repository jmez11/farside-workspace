package com.farside.workspace

import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.DragEvent
import com.farside.workspace.data.ConnectionProfile
import java.util.UUID

/** Home's activity drag contract: Home supplies the drop pose when sending this PendingIntent.
 * No session, slot or connection is created before a successful drop. */
internal object ConnectionSpatialDrag {
    const val MIME_ACTIVITY = "application/vnd.android.activity"
    const val EXTRA_PENDING_INTENT = "android.intent.extra.PENDING_INTENT"
    private var active: PendingIntent? = null
    private var request: String? = null

    fun attach(view: View): () -> Unit {
        view.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> active != null && event.clipDescription?.hasMimeType(MIME_ACTIVITY) == true
                DragEvent.ACTION_DRAG_ENDED -> {
                    // Home sends the intent during DROP, before ENDED. Cancel also frees unused
                    // tokens when the user releases over the launcher or abandons placement.
                    active?.cancel()
                    active = null
                    if (event.result) WindowLaunchMonitor.watch(request) else WindowLaunchMonitor.complete(request)
                    request = null
                    true
                }
                else -> false
            }
        }
        return {
            view.setOnDragListener(null)
            active?.cancel()
            active = null
            WindowLaunchMonitor.complete(request)
            request = null
        }
    }

    fun payload(view: View, profile: ConnectionProfile, request: String? = null): Pair<ClipData, PendingIntent> {
        val target = Intent(view.context, SessionActivity::class.java).apply {
            data = Uri.parse("farside://session/${profile.id}/drop/${UUID.randomUUID()}")
            putExtra("profileId", profile.id)
            putExtra(WindowLaunchMonitor.EXTRA_REQUEST, request)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val pending = PendingIntent.getActivity(view.context, 0, target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val clip = ClipData(profile.name, arrayOf(MIME_ACTIVITY),
            ClipData.Item(Intent().putExtra(EXTRA_PENDING_INTENT, pending)
                .putExtra("android.intent.extra.USER", android.os.Process.myUserHandle())))
        return clip to pending
    }

    fun start(view: View, profile: ConnectionProfile): Boolean {
        if (!WindowPlacementSupport.isAvailable()) return false
        if (active != null) return false
        request = WindowLaunchMonitor.prepare(view.context)
        val (clip, pending) = try { payload(view, profile, request) } catch (error: RuntimeException) {
            WindowLaunchMonitor.fail(request)
            request = null
            return false
        }
        active = pending
        val started = try {
            view.startDragAndDrop(clip, shadow(view, profile.name), null, View.DRAG_FLAG_GLOBAL)
        } catch (error: RuntimeException) {
            Log.w("SessionWindow", "Home rejected connection drag", error)
            false
        }
        if (!started) {
            pending.cancel()
            active = null
            WindowLaunchMonitor.fail(request)
            request = null
        }
        if (started) Log.i("SessionWindow", "Native connection placement drag started")
        return started
    }

    fun shadow(view: View, name: String): View.DragShadowBuilder = ConnectionShadow(view, name)

    private class ConnectionShadow(view: View, private val name: String) : View.DragShadowBuilder() {
        private val releaseHint = view.context.getString(R.string.release_to_open_here)
        private val scale = view.resources.displayMetrics.density
        // Read the target Activity's sizing contract, not the launcher's current bounds.
        // Horizon's display-metrics metadata is in pixels; layout dimensions are already
        // resolved to pixels by PackageManager. Keep the complete preview at that size.
        private val activity = view.context.packageManager.getActivityInfo(
            ComponentName(view.context, SessionActivity::class.java), PackageManager.GET_META_DATA)
        private val displaySize = activity.metaData?.getString("com.oculus.vr.default_display_metrics")
            ?.split("x")?.mapNotNull { it.toIntOrNull()?.takeIf { n -> n > 0 } }
            ?.takeIf { it.size == 2 }
        private val width = displaySize?.get(0) ?: activity.windowLayout?.width?.takeIf { it > 0 }
            ?: (1024 * scale).toInt()
        private val height = displaySize?.get(1) ?: activity.windowLayout?.height?.takeIf { it > 0 }
            ?: (640 * scale).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(width, height)
            touch.set(width / 2, height / 2)
        }
        override fun onDrawShadow(canvas: Canvas) {
            paint.color = Color.argb(230, 35, 40, 50)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12 * scale, 12 * scale, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * scale
            paint.color = Color.rgb(150, 185, 235)
            canvas.drawRoundRect(scale, scale, width - scale, height - scale, 12 * scale, 12 * scale, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 24 * scale
            val title = android.text.TextUtils.ellipsize(name, android.text.TextPaint(paint), width - 32 * scale,
                android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(title, width / 2f, height / 2f - 12 * scale, paint)
            paint.textSize = 18 * scale
            paint.color = Color.LTGRAY
            canvas.drawText(releaseHint, width / 2f, height / 2f + 24 * scale, paint)
        }
    }
}
