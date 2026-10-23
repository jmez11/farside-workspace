package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import metavrx.layout.compose.SpatialScene

internal class MacQualityVerification(private val test:Instrumentation) {
    fun run():String {
        val flags=test.uiAutomation.serviceInfo
        flags.flags=flags.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=flags
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        var activity:MainActivity?=null
        try {
            test.targetContext.startActivity(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) as MainActivity
            val q=mutableStateOf(MacConnectionQuality(frames=10,videoPackets=123,missing=7,gaps=2,reordered=9,duplicates=1,frameAgeMs=4000,recoveries=3))
            test.runOnMainSync {activity.setContent {MaterialTheme {SpatialScene {Surface {Column(Modifier.width(360.dp).padding(14.dp)) {MacConnectionQualityPanel(q.value)}}}}}}
            fun textVisible(label:String):Boolean {
                test.uiAutomation.clearCache()
                val windows=test.uiAutomation.windowsOnAllDisplays
                for(i in 0 until windows.size()) for(w in windows.valueAt(i)) {
                    val root=w.root ?: continue
                    fun visit(n:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                        if(n.isVisibleToUser && (n.text?.toString()==label || n.contentDescription?.toString()==label)) return true
                        repeat(n.childCount) {n.getChild(it)?.let {if(visit(it)) return true}}
                        return false
                    }
                    if(root.packageName==test.targetContext.packageName && visit(root)) return true
                }
                return false
            }
            fun await(label:String) {
                val until=SystemClock.uptimeMillis()+10000
                while(!textVisible(label)) {
                    if(SystemClock.uptimeMillis()>=until) {
                        val texts=mutableListOf<String>()
                        fun visit(n:android.view.accessibility.AccessibilityNodeInfo) {texts.add("${n.text} / ${n.contentDescription} visible=${n.isVisibleToUser}");repeat(n.childCount) {n.getChild(it)?.let(::visit)}}
                        val windows=test.uiAutomation.windowsOnAllDisplays
                        for(i in 0 until windows.size()) windows.valueAt(i).forEach {it.root?.let(::visit)}
                        File(test.targetContext.cacheDir,"mac-quality-nodes-private.txt").writeText(texts.joinToString("\n"))
                        error("Missing quality UI: $label")
                    }
                    Thread.sleep(100)
                }
            }
            await(test.targetContext.getString(R.string.mac_quality_stalled));await("123 / 10");await("7 / 2");await(test.targetContext.getString(R.string.mac_quality_age_value,4.0))
            test.uiAutomation.takeScreenshot().let {image ->File(test.targetContext.cacheDir,"mac-quality-stalled.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
            test.runOnMainSync {q.value=q.value.copy(fallback=true,rtcp=MacRtcpDiagnostics.Snapshot(12,8,9,11,1,2,3,4,5))}
            await(test.targetContext.getString(R.string.mac_quality_fallback));await("7 / 2");await("12 / 8");await("9 / 11");await("3 / 4 / 5");await("1 / 2")
            test.runOnMainSync {q.value=MacConnectionQuality(frames=20,frameAgeMs=20)}
            await(test.targetContext.getString(R.string.mac_quality_receiving))
            return "PASS production quality panel shows stalled state, counters, fallback with retained failed-attempt counts and fresh healthy state; synthetic render only"
        } finally {activity?.let {a ->test.runOnMainSync {a.finish()}};test.removeMonitor(monitor)}
    }
}
