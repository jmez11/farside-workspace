package com.farside.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Observe each requested window; never infer availability from an assumed OS limit. */
internal object WindowLaunchMonitor {
    const val EXTRA_REQUEST = "windowLaunchRequest"
    private val handler = Handler(Looper.getMainLooper())
    private val requests = mutableMapOf<String, Runnable>()
    internal val pendingCount get() = requests.size
    fun observe(activity: android.app.Activity) {
        val id = activity.intent.getStringExtra(EXTRA_REQUEST) ?: return
        if (id !in requests) return
        val view = activity.window.decorView
        if (view.isHardwareAccelerated) {
            view.viewTreeObserver.registerFrameCommitCallback {
                if (!activity.isFinishing && view.isAttachedToWindow && view.isShown &&
                    view.windowVisibility == android.view.View.VISIBLE) complete(id)
            }
            view.invalidate()
        }
    }
    fun prepare(context: Context, onFailure: () -> Unit = {}): String {
        val id = java.util.UUID.randomUUID().toString()
        requests[id] = Runnable {
            if (requests.remove(id) != null) { onFailure(); showFailure(context) }
        }
        return id
    }
    fun watch(id: String?) { requests[id]?.let { handler.removeCallbacks(it); handler.postDelayed(it, 10_000) } }
    fun complete(id: String?) { requests.remove(id)?.let {
        handler.removeCallbacks(it)
        android.util.Log.i("WindowLaunch", "Request completed or canceled")
    } }
    fun fail(id: String?) { requests[id]?.run() }
    fun showFailure(context: Context) {
        Toast.makeText(context.applicationContext,
            context.getString(R.string.window_launch_failed), Toast.LENGTH_LONG).show()
    }
}
