package com.farside.workspace

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent

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
    fun forgetLegacyPositions(context: Context) {
        context.deleteSharedPreferences("session_window_positions")
    }

    fun intent(context: Context, profile: String, slot: Int) = Intent(context, SessionActivity::class.java).apply {
        data = android.net.Uri.parse("farside://session/$profile/$slot")
        putExtra("profileId", profile)
        putExtra(SessionWindowSlots.EXTRA_SLOT, slot)
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    fun launch(activity: Activity, profile: String) {
        val slot = SessionWindowSlots.reserve(activity, profile)
        val request = WindowLaunchMonitor.prepare(activity) { SessionWindowSlots.release(profile, slot) }
        val intent = intent(activity, profile, slot).putExtra(WindowLaunchMonitor.EXTRA_REQUEST, request)
        val replaceLauncher = activity is MainActivity && !WindowPlacementSupport.isAvailable()
        // Earlier Home releases replace the launcher at its current pose.
        try {
            activity.startActivity(intent, if (replaceLauncher) LauncherWindowLauncher.placement(activity, 0f) else null)
            WindowLaunchMonitor.watch(request)
            if (activity is MainActivity) {
                if (replaceLauncher) activity.finishAndRemoveTask()
                else activity.moveTaskToBack(true)
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("SessionWindow", "Window launch rejected", error)
            WindowLaunchMonitor.fail(request)
        }
    }
}
