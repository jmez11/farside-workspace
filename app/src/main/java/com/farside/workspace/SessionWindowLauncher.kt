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
        val intent = intent(activity, profile, slot)
        // Home owns placement, including native drag/drop and docking.
        try {
            activity.startActivity(intent)
        } catch (error: RuntimeException) {
            SessionWindowSlots.release(profile, slot)
            throw error
        }
    }
}
