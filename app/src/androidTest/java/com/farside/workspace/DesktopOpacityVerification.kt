package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import kotlin.math.abs

/** Checks the shared desktop toolbar against the first saved RDP profile, then restores it. */
internal class DesktopOpacityVerification(private val test: Instrumentation) {
    private fun texture(view: View): TextureView? = when (view) {
        is TextureView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { texture(view.getChildAt(it)) }
        else -> null
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun descend(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descend).orEmpty() }
        val displays = test.uiAutomation.windowsOnAllDisplays
        return (0 until displays.size()).flatMap { i -> displays.valueAt(i).flatMap { window ->
            window.root?.takeIf { it.packageName == test.targetContext.packageName }?.let(::descend).orEmpty()
        } }
    }

    private fun await(reason: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { reason }
            Thread.sleep(50)
        }
    }

    private fun click(description: String) {
        await("Missing $description") { nodes().any { it.contentDescription?.toString() == description } }
        var node = nodes().first { it.contentDescription?.toString() == description }
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $description" }
    }

    fun run(): String {
        val manager = ProfileManager(test.targetContext)
        val profile = manager.getProfiles().first { it.type == ConnectionType.RDP }
        val originalOpacity = profile.terminalOpacity
        val target = if (abs(originalOpacity - 0.45f) < 0.01f) 0.7f else 0.45f
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java)
            .putExtra("profileId", profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
        try {
            click(test.targetContext.getString(R.string.keep_toolbar_visible))
            click(test.targetContext.getString(R.string.transparency))
            await("Saved RDP opacity did not load") {
                nodes().any { abs((it.rangeInfo?.current ?: -1f) - originalOpacity) < 0.01f } &&
                    abs((texture(activity.window.decorView)?.alpha ?: -1f) - originalOpacity) < 0.01f
            }
            val slider = nodes().first { abs((it.rangeInfo?.current ?: -1f) - originalOpacity) < 0.01f }
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target) }))
            await("RDP opacity did not apply and save") {
                abs((texture(activity.window.decorView)?.alpha ?: -1f) - target) < 0.01f &&
                    abs(manager.getProfiles().first { it.id == profile.id }.terminalOpacity - target) < 0.01f
            }
            return "PASS RDP toolbar loaded, applied and saved opacity; saved profile restored afterward. Physical passthrough appearance remains manual."
        } finally {
            test.runOnMainSync { activity.finish() }
            Thread.sleep(400)
            manager.getProfiles().firstOrNull { it.id == profile.id }?.let {
                manager.updateProfile(it.copy(terminalOpacity = originalOpacity))
            }
            check(abs(manager.getProfiles().first { it.id == profile.id }.terminalOpacity - originalOpacity) < 0.01f)
        }
    }
}
