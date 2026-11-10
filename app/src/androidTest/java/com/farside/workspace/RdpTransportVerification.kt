package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File
import java.util.UUID

/** Upstream loopback UDP tests plus automatic saved-profile/JNI routing and negotiated state. */
object RdpTransportVerification {
    @JvmStatic external fun nativeFixture(): Int
    @JvmStatic external fun nativeKeyboardFixture(): Int
    @JvmStatic external fun udpEnabled(instance: Long): Boolean
    @JvmStatic external fun unicodeEnabled(instance: Long): Boolean

    fun run(test: Instrumentation): String {
        // Production connect initializes HOME before FreeRDP creates its settings.
        android.system.Os.setenv("HOME",test.targetContext.filesDir.absolutePath,true)
        val bridge=FreeRDPBridge() // Load JNI before invoking test-only native methods.
        val nativeResult=nativeFixture()
        check(nativeResult==0) { "Upstream Android UDP socket/settings tests failed: $nativeResult" }
        check(bridge.getMultitransportState(0)==0)
        val manager=ProfileManager(test.targetContext)
        val evidence=File(test.targetContext.cacheDir,"rdp-transport-results.txt")
        evidence.writeText("PASS: WITH_RDPEUDP compiled; direct UDP Soft-Sync/proxy TCP policy; 21 upstream loopback UDP socket cases.\n")
        val owned=ConnectionProfile("transport-fixture","Transport fixture",ConnectionType.RDP,"127.0.0.1",user="",pass="")
        val legacy=owned.toJson(forBackup=true).apply {put("rdpTcpOnly",true)}
        val restored=ConnectionProfile.fromJson(legacy)
        check(!RdpTransport.tcpOnly(restored) && !restored.toJson(forBackup=true).has("rdpTcpOnly"))
        evidence.appendText("PASS: obsolete TCP-only preference is ignored and omitted from backups; direct routes use automatic UDP.\n")
        val original=manager.getProfiles().firstOrNull {it.type==ConnectionType.RDP && it.remoteApp.isNullOrBlank()}
            ?: return evidence.readText()+"UNVERIFIED: no saved RDP profile for live transport negotiation.\n"
        val profile=original.copy(id=UUID.randomUUID().toString(),name="Transport verification",
            cardBackground=null, enableCamera=false,redirectMicrophone=false)
        var activity:SessionActivity?=null
        val infoVisible=ConnectionInfoVisibility.get(test.targetContext)
        try {
            test.runOnMainSync {ConnectionInfoVisibility.set(test.targetContext,true)}
            manager.addProfile(profile)
            activity=test.startActivitySync(Intent(test.targetContext,SessionActivity::class.java)
                .putExtra("profileId",profile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
            val deadline=android.os.SystemClock.uptimeMillis()+45000
            while(activity.rdpInstance==0L && !activity.isDestroyed && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(200)
            check(activity.rdpInstance!=0L) {"Saved RDP connection unavailable (automatic route)"}
            val current=activity
            var state=0
            test.runOnMainSync {
                check(udpEnabled(current.rdpInstance)==!RdpTransport.tcpOnly(profile)) {"JNI UDP setting mismatch"}
                check(unicodeEnabled(current.rdpInstance)) {"Negotiated RDP Unicode input disabled"}
            }
            val settle=android.os.SystemClock.uptimeMillis()+15000
            do {
                test.runOnMainSync {state=bridge.getMultitransportState(current.rdpInstance)}
                if(state==2) break
                Thread.sleep(250)
            } while(android.os.SystemClock.uptimeMillis()<settle)
            check(bridge.getDesktopWidth(current.rdpInstance)>0)
            // A ready UDP tunnel alone does not establish GFX decode/presentation.
            var presented=false
            val frameDeadline=android.os.SystemClock.uptimeMillis()+30000
            do {
                test.runOnMainSync {
                    presented=bridge.getMonitorSurfaceState(current.rdpInstance).toList().chunked(8)
                        .any {it.size==8 && it[7]>0 && it[6]==it[5]}
                    current.rdpMonitor?.session?.let {session ->
                        presented=presented && !session.busy && !session.transition.active &&
                            session.inputReady && session.disconnectedReason.value==null
                    }
                }
                if(presented) break
                Thread.sleep(200)
            } while(android.os.SystemClock.uptimeMillis()<frameDeadline)
            check(presented) {"No rendered desktop for the current monitor revision (automatic route, tunnel=$state)"}
            state=connectionInfo(test,current)
            evidence.appendText("PASS: automatic route, native UDP setting=${!RdpTransport.tcpOnly(profile)}, tunnel=$state, current desktop revision rendered, info label verified (${if(state==2) "TCP + UDP experimental" else "TCP observed; live UDP not established"}).\n")
        } finally {
            test.runOnMainSync {ConnectionInfoVisibility.set(test.targetContext,infoVisible)}
            activity?.let {current ->
                test.runOnMainSync {current.finish()}
                val deadline=android.os.SystemClock.uptimeMillis()+10000
                while(!current.isDestroyed && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
            }
            manager.deleteProfile(profile.id)
            check(test.targetContext.getSharedPreferences("farside_profiles",0).edit().commit())
        }
        return evidence.readText()
    }

    private fun connectionInfo(test:Instrumentation, activity:SessionActivity):Int {
        fun nodes():List<android.view.accessibility.AccessibilityNodeInfo> {
            test.uiAutomation.clearCache()
            fun visit(n:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> =
                listOf(n)+(0 until n.childCount).flatMap {n.getChild(it)?.let(::visit).orEmpty()}
            val windows=test.uiAutomation.windowsOnAllDisplays
            return (0 until windows.size()).flatMap {i -> windows.valueAt(i).flatMap {it.root?.let(::visit).orEmpty()}}
        }
        val service=test.uiAutomation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=service
        revealSessionToolbar(test,activity)
        Thread.sleep(600)
        check(nodes().any {it.contentDescription?.toString()==test.targetContext.getString(R.string.window_manager)}==QuestWindowManager.isAvailable(test.targetContext)) {"RDP Window Manager button ignored Navigator feature state"}
        var clicked=false
        val clickDeadline=android.os.SystemClock.uptimeMillis()+5000
        do {
            val current=nodes()
            if(current.any {it.text?.toString()=="Connection Info"}) {clicked=true;break}
            var button=current.firstOrNull {it.contentDescription?.toString()==test.targetContext.getString(R.string.debug_actions)}
            while(button!=null && !button.isClickable) button=button.parent
            if(button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true) {clicked=true;break}
            Thread.sleep(150) // Reacquire semantics after the toolbar reveal/layout animation.
        } while(android.os.SystemClock.uptimeMillis()<clickDeadline)
        if(!clicked) {
            revealSessionToolbar(test,activity)
            tapSessionControl(test,activity,test.targetContext.getString(R.string.debug_actions))
        }
        var state=0
        var expected:String?=null
        var matched=false
        val deadline=android.os.SystemClock.uptimeMillis()+5000
        do {
            test.runOnMainSync {state=activity.rdpBridge!!.getMultitransportState(activity.rdpInstance)}
            expected=RdpTransport.statusResource(state)?.let(test.targetContext::getString)
            val visible=nodes()
            matched=visible.any {it.text?.toString()=="Connection Info"} &&
                (expected==null || visible.any {it.text?.toString()==expected})
            if(matched) break
            Thread.sleep(200)
        } while(android.os.SystemClock.uptimeMillis()<deadline)
        val bitmap=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
        val done=java.util.concurrent.CountDownLatch(1)
        var result=-1
        test.runOnMainSync {android.view.PixelCopy.request(activity.window,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
        check(done.await(5,java.util.concurrent.TimeUnit.SECONDS) && result==android.view.PixelCopy.SUCCESS)
        File(test.targetContext.cacheDir,"rdp-transport-auto.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        check(matched) {"Connection info expected=$expected state=$state; visible="+nodes().mapNotNull {it.text?.toString()}.filter {it.contains("TCP") || it.contains("UDP")}}
        if(expected==null) check(nodes().none {it.text?.toString()==test.targetContext.getString(R.string.rdp_transport_udp)}) {"TCP was labelled UDP"}
        return state
    }
}
