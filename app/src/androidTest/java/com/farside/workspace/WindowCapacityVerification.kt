package com.farside.workspace

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo
import metavrx.layout.window.compose.state.WindowLifecycle

internal class WindowCapacityVerification(private val test: Instrumentation) {
    fun run(): String {
        val activities = mutableListOf<WindowCapacityFixtureActivity>()
        val report = StringBuilder()
        fun snapshot(stage: String) {
            val activity = activities.last()
            val cls = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(cls)
            try { report.appendLine("$stage ordinary getWindows=${cls.getMethod("getWindows").invoke(manager)}") }
            catch (e: Exception) { report.appendLine("$stage ordinary getWindows denied: ${e.cause ?: e}") }
            test.uiAutomation.adoptShellPermissionIdentity()
            try {
                report.appendLine("$stage shell windows=${cls.getMethod("getWindows").invoke(manager)}")
                for (a in activities) {
                    val token = Class.forName("horizonos.app.ActivityExt")
                        .getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, a)
                    val info = cls.getMethod("getWindowInfo", Class.forName("horizonos.view.VolumetricWindowToken")).invoke(manager, token)
                    val visible = info.javaClass.getMethod("isVisible").invoke(info)
                    val drawn = info.javaClass.getMethod("isDrawn").invoke(info)
                    report.appendLine("$stage task=${a.taskId} visible=$visible drawn=$drawn")
                    check(visible == true && drawn == true) { "Main task ${a.taskId} not visible/drawn" }
                }
            } finally { test.uiAutomation.dropShellPermissionIdentity() }
            report.appendLine("$stage activities=${activities.map { "${it.taskId}:destroyed=${it.isDestroyed},children=${it.children.map { s -> s.lifecycle.value }}" }}")
            File(test.targetContext.cacheDir, "window-capacity.txt").writeText(report.toString())
        }
        try {
            repeat(6) { index ->
                val a = test.startActivitySync(Intent(test.targetContext, WindowCapacityFixtureActivity::class.java)
                    .setData(android.net.Uri.parse("farside://capacity/$index/${System.nanoTime()}"))
                    .putExtra("number", index + 1)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as WindowCapacityFixtureActivity
                activities += a
                Thread.sleep(1000)
            }
            snapshot("six-main")
            val parent = activities.last()
            for (count in 1..2) {
                test.runOnMainSync { parent.childCount = count }
                Thread.sleep(2000)
                snapshot("six-main-$count-children")
                check(parent.children.take(count).all { it.lifecycle.value == WindowLifecycle.Active }) { "Child $count not active" }
                check(activities.none { it.isDestroyed }) { "Main activity displaced/destroyed" }
            }
            test.runOnMainSync { parent.childCount = 0 }
            Thread.sleep(1000)
            snapshot("children-closed")
        } finally {
            activities.forEach { test.runOnMainSync { it.finishAndRemoveTask() } }
            File(test.targetContext.cacheDir, "window-capacity.txt").writeText(report.toString())
        }
        Thread.sleep(1000)
        File(test.targetContext.cacheDir, "window-capacity.txt").writeText(report.toString())
        return "Six main windows remain visible/drawn with two active children. This is a capacity observation, not a fixed launcher limit."
    }

}
