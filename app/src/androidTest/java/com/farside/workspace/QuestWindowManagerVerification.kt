package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent

internal object QuestWindowManagerVerification {
    fun run(test: Instrumentation): String {
        val context=test.targetContext
        val enabled=QuestWindowManager.navigatorEnabled(context)
        val receiver=QuestWindowManager.receiverAvailable(context)
        val privileged = try {
            test.uiAutomation.adoptShellPermissionIdentity("horizonos.permission.READ_SETTINGS")
            QuestWindowManager.navigatorEnabled(context)
        } finally {test.uiAutomation.dropShellPermissionIdentity()}
        val expected=QuestWindowManager.canShow(enabled,receiver)
        check(QuestWindowManager.isAvailable(context)==expected)
        if(!expected) check(!QuestWindowManager.toggle(context)) {"Unavailable shortcut sent a broadcast"}
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val ui=SetupPhaseOneVerification(test)
            ui.find(context.getString(R.string.setup_vault))
            Thread.sleep(600)
            check(ui.nodes().any {it.contentDescription?.toString()==context.getString(R.string.window_manager)}==expected)
            ui.capture(activity,"navigator-feature-launcher.png")
            if(expected) {
                fun visible(stage:String):Boolean {
                    val descriptor=test.uiAutomation.executeShellCommand("dumpsys activity service com.oculus.vrshell/.ShellService desktopmanagerui")
                    val dump=descriptor.use {java.io.FileInputStream(it.fileDescriptor).bufferedReader().use {reader -> reader.readText()}}
                    java.io.File(context.cacheDir,"window-manager-$stage.private.txt").writeText(dump)
                    return checkNotNull(Regex("lastVisibilityState=(true|false)").find(dump)) {"Window Manager visibility unavailable"}.groupValues[1].toBoolean()
                }
                val before=visible("before")
                try {
                    ui.click(context.getString(R.string.window_manager)) // Production button, ordinary app UID.
                    val deadline=android.os.SystemClock.uptimeMillis()+10000
                    var changed=false
                    do {
                        if(visible("after")!=before) {changed=true;break}
                        Thread.sleep(300)
                    } while(android.os.SystemClock.uptimeMillis()<deadline)
                    check(changed) {"Launcher click did not toggle native Window Manager visibility"}
                } finally {
                    if(visible("cleanup")!=before) {
                        test.runOnMainSync {check(QuestWindowManager.toggle(context))}
                        val deadline=android.os.SystemClock.uptimeMillis()+5000
                        while(visible("restored")!=before && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(250)
                        check(visible("restored")==before) {"Window Manager visibility was not restored"}
                    }
                }
            }
            val action=if(expected) "actual launcher click toggled native Window Manager visibility and restored it" else "unavailable toggle sent no broadcast"
            return "PASS: ordinary app navigator_enabled=$enabled, test-only privileged read=$privileged, receiver=$receiver; launcher shortcut visible=$expected using explicit flag or receiver fallback; $action. No feature writes or app permissions added."
        } finally {test.runOnMainSync {activity.finish()}}
    }
}
