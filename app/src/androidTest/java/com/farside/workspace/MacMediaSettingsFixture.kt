package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.AddProfileForm
import java.io.File

/** Exercises actual settings and JSON without saving or authenticating any connection. */
internal class MacMediaSettingsFixture(private val test:Instrumentation) {
    @OptIn(ExperimentalMaterial3Api::class)
    fun run(id:String):String {
        val pm=ProfileManager(test.targetContext)
        val before=pm.getProfiles().map { it.toJson().toString() }
        val vnc=pm.getProfiles().single { it.id==id && it.type==ConnectionType.VNC }
        val ssh=pm.getProfiles().single { it.host==vnc.host && it.type==ConnectionType.SSH }.copy(name="Fixture Companion",host="fixture.invalid",enableSftp=false)
        val fixture=vnc.copy(name="Media settings fixture",expMacHevc=true,macPreferAvc=false,macAudioEnabled=false,macSshRelayProfileId=null)
        var saved:ConnectionProfile?=null
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
        var activity:MainActivity?=null
        try {
            test.targetContext.startActivity(Intent(test.targetContext,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity=checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) { "Horizon did not open companion editor fixture" } as MainActivity
            test.runOnMainSync { activity.setContent { MaterialTheme {
                AddProfileForm(initialProfile=fixture,onSave={ saved=it },onCancel={},profileManager=pm,savedProfiles=listOf(fixture,ssh))
            } } }
            val forbidden=listOf(R.string.mac_media_avc,R.string.mac_media_audio,R.string.mac_media_ssh)
                .map(test.targetContext::getString)
            check(nodes().none { it.text?.toString() in forbidden })
            click(test.targetContext.getString(R.string.companion_remote_login))
            click(ssh.name)
            click(test.targetContext.getString(R.string.save_uppercase))
            val result=checkNotNull(saved) { "Form did not produce a profile" }
            check(result.expMacHevc && result.macPreferAvc && result.macAudioEnabled && result.sftpPairProfileId==ssh.id && result.macSshRelayProfileId==null && !result.enableMacSshRelay)
            val restored=ConnectionProfile.fromJson(result.toJson())
            check(restored.macPreferAvc && restored.macAudioEnabled && restored.sftpPairProfileId==ssh.id && restored.macSshRelayProfileId==null && restored.expMacHevc && !restored.enableMacSshRelay)
            check(pm.getProfiles().map { it.toJson().toString() }==before) { "Fixture changed saved connections" }
            return "PASS automatic H.264/audio, one Companion selector accepting different-host SSH with SFTP off, JSON persistence and unchanged profiles"
        } finally { test.removeMonitor(monitor);test.runOnMainSync { activity?.finish() } }
    }
    private fun nodes():List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;repeat(n.childCount) { n.getChild(it)?.let(::visit) } }
        val windows=test.uiAutomation.windowsOnAllDisplays
        for(i in 0 until windows.size()) windows.valueAt(i).forEach { it.root?.let(::visit) }
        return result
    }
    private fun click(label:String) {
        val deadline=SystemClock.uptimeMillis()+8000
        while(SystemClock.uptimeMillis()<deadline) {
            val all=nodes()
            val node=all.firstOrNull { it.isVisibleToUser && it.isClickable && it.contentDescription?.toString()==label }
                ?: all.firstOrNull { it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label) }
            if(node!=null) {
                var clickable:AccessibilityNodeInfo=node
                while(!clickable.isClickable && clickable.parent!=null) clickable=checkNotNull(clickable.parent)
                if(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(300);return }
            }
            all.filter { it.isScrollable && it.className?.toString()!="android.widget.EditText" }.maxByOrNull {
                android.graphics.Rect().also(it::getBoundsInScreen).height()
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(300)
        }
        File(test.targetContext.cacheDir,"mac-media-settings-ui.txt").writeText(nodes().joinToString("\n") { "${it.className} scroll=${it.isScrollable} click=${it.isClickable} visible=${it.isVisibleToUser} text=${it.text} desc=${it.contentDescription}" })
        test.uiAutomation.takeScreenshot()?.let { image ->
            File(test.targetContext.cacheDir,"mac-media-settings-failure.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        }
        error("Missing media settings control: $label")
    }
}
