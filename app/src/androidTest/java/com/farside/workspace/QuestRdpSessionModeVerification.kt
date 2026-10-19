package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.util.UUID

/** Reads actual native session settings; does not infer success from server appearance. */
object QuestRdpSessionModeVerification {
    @JvmStatic external fun getFlags(instance: Long): Int

    fun run(test: Instrumentation): String {
        val manager = ProfileManager(test.targetContext)
        val original = manager.getProfiles().first { it.type == ConnectionType.RDP && it.remoteApp.isNullOrBlank() }
        for (console in listOf(false, true)) {
            val profile = original.copy(id = UUID.randomUUID().toString(), name = "Console session verification",
                connectToConsole = console, adminMode = false, enableCamera = false, redirectMicrophone = false)
            var activity: SessionActivity? = null
            try {
                manager.addProfile(profile)
                activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java)
                    .putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
                val deadline = android.os.SystemClock.uptimeMillis() + 45000
                while (activity.rdpInstance == 0L && !activity.isDestroyed && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                check(activity.rdpInstance != 0L) { "RDP connection unavailable (console=$console)" }
                test.runOnMainSync {
                    check(getFlags(activity.rdpInstance) == if (console) 1 else 0) {
                        "Native ConsoleSession/RestrictedAdminModeRequired did not match the saved profile"
                    }
                }
            } finally {
                activity?.let { current ->
                    test.runOnMainSync { current.finish() }
                    val deadline = android.os.SystemClock.uptimeMillis() + 10000
                    while (!current.isDestroyed && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
                }
                manager.deleteProfile(profile.id)
                check(test.targetContext.getSharedPreferences("farside_profiles", 0).edit().commit())
            }
        }
        return "PASS: saved profiles connected through production JNI with ConsoleSession off/on and RestrictedAdminModeRequired off; fixtures removed. Server console appearance remains a wearer check."
    }
}
