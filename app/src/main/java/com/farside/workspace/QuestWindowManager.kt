package com.farside.workspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** v209 VrShell shortcut; Home owns the Window Manager UI. */
internal object QuestWindowManager {
    private const val PACKAGE = "com.oculus.vrshell"
    private const val RECEIVER = "com.oculus.vrshell.ShellControlBroadcastReceiver"
    private const val ACTION = "com.oculus.vrshell.intent.action.TOGGLE_WINDOW_MANAGER"

    private val component = ComponentName(PACKAGE, RECEIVER)

    fun isAvailable(context: Context): Boolean = context.packageManager
        .queryBroadcastReceivers(Intent(ACTION).setPackage(PACKAGE), 0)
        .any { it.activityInfo?.let { receiver ->
            receiver.name == RECEIVER && receiver.enabled && receiver.exported
        } == true }

    fun toggle(context: Context) {
        context.sendBroadcast(Intent(ACTION).setComponent(component))
    }
}
