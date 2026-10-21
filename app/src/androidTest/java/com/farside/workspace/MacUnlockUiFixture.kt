package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger

/** Actual confirmation/cancel/one-shot UI with a counter, never a host or real credential. */
internal class MacUnlockUiFixture(private val test:Instrumentation) {
    private fun nodes():List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;repeat(n.childCount) { n.getChild(it)?.let(::visit) } }
        val windows=test.uiAutomation.windowsOnAllDisplays
        for(i in 0 until windows.size()) windows.valueAt(i).forEach { w -> w.root?.takeIf { it.packageName==test.targetContext.packageName }?.let(::visit) }
        return result
    }
    private fun find(label:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label) }
    private fun action(label:String):AccessibilityNodeInfo? {
        var node=find(label) ?: return null
        while(!node.isClickable && node.parent!=null) node=checkNotNull(node.parent)
        return node
    }
    private fun await(reason:String,check:()->Boolean) {
        val until=SystemClock.uptimeMillis()+10000
        while(!check()) { check(SystemClock.uptimeMillis()<until) { reason };Thread.sleep(100) }
    }
    private fun click(label:String) {
        await("Missing unlock action: $label") { find(label)!=null }
        val node=checkNotNull(action(label))
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }
    fun run():String {
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        var activity:MainActivity?=null
        try {
            test.targetContext.startActivity(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) { "Horizon did not open unlock UI fixture" } as MainActivity
            val enabled=mutableStateOf(false);val calls=AtomicInteger()
            test.runOnMainSync { activity.setContent { MaterialTheme { Surface {
                MacUnlockControl(enabled.value,{ calls.incrementAndGet();true },{},48.dp,24.dp)
            } } } }
            val label=test.targetContext.getString(R.string.mac_unlock_saved)
            await("Unlock action not rendered") { find(label)!=null }
            check(!checkNotNull(action(label)).isEnabled && calls.get()==0)
            test.runOnMainSync { enabled.value=true }
            await("Unlock action not enabled") { action(label)?.isEnabled==true }
            click(label)
            await("Confirmation not rendered") { find(test.targetContext.getString(R.string.mac_unlock_confirm))!=null }
            check(calls.get()==0)
            click(test.targetContext.getString(R.string.cancel));check(calls.get()==0)
            click(label);click(test.targetContext.getString(R.string.mac_unlock_send))
            await("Unlock not submitted exactly once") { calls.get()==1 }
            await("Unlock allowed a duplicate") { action(label)?.isEnabled==false }
            return "PASS actual unlock confirmation, cancel without input, disconnected disabled state and one-shot submit; no host/password used"
        } finally { activity?.let { a -> test.runOnMainSync { a.finish() } };test.removeMonitor(monitor) }
    }
}
