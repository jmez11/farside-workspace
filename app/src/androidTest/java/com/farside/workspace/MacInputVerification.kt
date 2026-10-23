package com.farside.workspace

import android.app.Instrumentation
import android.os.SystemClock
import com.farside.workspace.data.*
import java.io.File
import org.json.JSONObject

/** Bounded actual-window touch check; credentials are never typed by this fixture. */
internal class MacInputVerification(private val test:Instrumentation) {
    fun run(id:String):String {
        val context=test.targetContext
        val pm=ProfileManager(context)
        val savedProfile=pm.getProfiles().single { it.id==id && it.type==ConnectionType.VNC }
        test.arguments?.getString("macInputCleanupProfile")?.let {ownedId ->
            check(ownedId!=savedProfile.id)
            val owned=pm.getProfiles().single {it.id==ownedId}
            check(owned==savedProfile.copy(id=ownedId,cardBackground=null,reconnectAutomatically=false))
            pm.deleteProfile(ownedId)
            check(context.getSharedPreferences("farside_profiles",0).edit().commit())
            if(test.arguments?.getString("macInputCleanupOnly")=="true") return "PASS removed only explicitly identified interrupted Mac fixture copy; no connection opened"
        }
        val profile=if(test.arguments?.getString("macVirtualSession")=="true")
            savedProfile.copy(expMacShareConsole=false) else if(test.arguments?.getString("macConsoleSession")=="true") savedProfile.copy(expMacShareConsole=true) else savedProfile
        require(profile.proxyType in listOf(ProxyType.NONE,ProxyType.CLOUDFLARED))
        val report=File(context.cacheDir,"mac-input-results.txt").apply { writeText("") }
        fun record(message:String) { report.appendText("${SystemClock.uptimeMillis()}: $message\n") }
        record("Session policy savedConsole=${savedProfile.expMacShareConsole} testConsole=${profile.expMacShareConsole}; saved profile unchanged")
        val action=File(context.cacheDir,"mac-input-action.json").apply { delete() }
        if(test.arguments?.getString("macInputBaselineBefore")!="false") record(MacNativeBaseline(test).run(id))
        else record("Cold production window: no preliminary native VNC connection")
        MacMediaSurface(test,profile.copy(reconnectAutomatically=false),realSessionWindow=true).use { display ->
            check(display.awaitConnection())
            val bridge=checkNotNull(display.bridge);val handle=display.handle
            if(test.arguments?.getString("macQualityOnly")=="true") {
                val oldInfo=ConnectionInfoVisibility.get(context)
                ConnectionInfoVisibility.set(context,true)
                try {
                val q=checkNotNull(bridge.connectionQuality())
                val now=SystemClock.uptimeMillis()
                display.touch(.5f,.04f,true,now);Thread.sleep(80);display.touch(.5f,.04f,false,now)
                val info=test.uiAutomation.serviceInfo
                info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                test.uiAutomation.serviceInfo=info
                fun find(label:String):android.view.accessibility.AccessibilityNodeInfo? {
                    test.uiAutomation.clearCache()
                    fun visit(n:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                        if(n.text?.toString()==label || n.contentDescription?.toString()==label) return n
                        repeat(n.childCount) {n.getChild(it)?.let {c ->visit(c)?.let {return it}}};return null
                    }
                    val windows=test.uiAutomation.windowsOnAllDisplays
                    for(i in 0 until windows.size()) for(w in windows.valueAt(i)) {
                        w.root?.takeIf {it.packageName==context.packageName}?.let {visit(it)?.let {return it}}
                    }
                    return null
                }
                val until=SystemClock.uptimeMillis()+10000
                var icon=find(context.getString(R.string.debug_actions))
                while(icon==null && SystemClock.uptimeMillis()<until) {Thread.sleep(100);icon=find(context.getString(R.string.debug_actions))}
                var action=checkNotNull(icon) {"Info toolbar action missing"}
                while(!action.isClickable && action.parent!=null) action=checkNotNull(action.parent)
                check(action.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                val expected=context.getString(if(q.fallback) R.string.mac_quality_fallback else R.string.mac_quality_health)
                val deadline=SystemClock.uptimeMillis()+10000
                while(find(expected)==null && SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
                check(find(expected)!=null) {"Actual connection popup omitted media quality"}
                display.captureWindow("mac-quality-live-window.png")
                record("PASS actual SessionActivity toolbar Info quality state=${q.state} packets=${q.videoPackets} frames=${q.frames} missing=${q.missing} gaps=${q.gaps}; fallback diagnostics retained")
                } finally {ConnectionInfoVisibility.set(context,oldInfo)}
                return report.readText()
            }
            check(bridge.isMacHevc)
            // Give the real initial resize and its fade time to complete.
            Thread.sleep(8000)
            val initialColored=display.capture("mac-input-current.png")
            if(test.arguments?.getString("macInspectOnly")!="true") check(initialColored)
            if(test.arguments?.getString("macInspectOnly")=="true") {
                display.captureWindow("mac-input-initial-window.png")
                if(test.arguments?.getString("macInspectSeries")=="true") {
                    repeat(12) {i ->
                        display.capture("mac-input-startup-$i.png")
                        record("production startup/$i frames=${bridge.macMediaPipeline?.framesDecoded?.get()} ${bridge.macMediaPipeline?.videoStreamSummary}")
                        Thread.sleep(1000)
                    }
                }
                record("Incoming RTCP diagnostics: ${bridge.macMediaPipeline?.rtcpDiagnostics?.snapshot()}")
                record("Initial production window captured without physical resize or typed input; colored=$initialColored hardware frames=${bridge.macMediaPipeline?.framesDecoded?.get()}")
                if(test.arguments?.getString("macInspectWakeKey")=="true") {
                    bridge.sendKeyEvent(handle,0x2a,true);Thread.sleep(80);bridge.sendKeyEvent(handle,0x2a,false)
                    Thread.sleep(2000);display.capture("mac-input-after-wake-key.png")
                    record("Captured one Shift press/release wake comparison; no character or password sent")
                }
                if(test.arguments?.getString("macInspectWake")=="true") {
                    display.hover(.5f,.5f);Thread.sleep(1500)
                    display.capture("mac-input-after-hover.png")
                    val now=SystemClock.uptimeMillis()
                    display.touch(.5f,.5f,true,now);Thread.sleep(80);display.touch(.5f,.5f,false,now)
                    Thread.sleep(1500);display.capture("mac-input-after-wake.png")
                    record("Captured hover and one noncredential center-click wake comparison")
                }
                if(test.arguments?.getString("macInspectResize")=="true") {
                    display.resize(1191,749);Thread.sleep(6000)
                    display.capture("mac-input-after-resize.png")
                    record("Captured comparison after actual task resize")
                }
                return report.readText()
            }
            val before=bridge.macInputCounts
            val started=SystemClock.uptimeMillis()
            display.touch(.1f,.3f,true,started);Thread.sleep(80);display.touch(.1f,.3f,false,started)
            val deadline=SystemClock.uptimeMillis()+3000
            while(bridge.macInputCounts.first<before.first+2 && SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
            check(bridge.macInputCounts.first>=before.first+2) { "Actual window tap did not reach encrypted pointer writer" }
            record("PASS actual production-window touch dispatched and encrypted press/release sent")
            bridge.sendUnicodeKeyEvent(handle,9,true);bridge.sendUnicodeKeyEvent(handle,9,false)
            Thread.sleep(300)
            check(bridge.macInputCounts.second>=before.second+2)
            record("PASS Tab press/release sent without credentials; READY screenshots and bounded click actions")
            val until=SystemClock.uptimeMillis()+180000
            var finished=false
            var unlock=false
            while(!finished && SystemClock.uptimeMillis()<until) {
                display.capture("mac-input-current.png")
                bridge.macMediaPipeline?.let { video ->
                    File(context.cacheDir,"mac-input-media-stats.txt").writeText("frames=${video.framesDecoded.get()} received=${video.packetsReceived.get()} authenticated=${video.authenticatedPackets.get()} ${video.avcAssemblyDiagnostics}")
                }
                if(action.exists()) {
                    val request=JSONObject(action.readText());action.delete()
                    when(request.getString("action")) {
                        "click" -> {
                            val x=request.getDouble("x").toFloat();val y=request.getDouble("y").toFloat()
                            require(x in 0f..1f && y in 0f..1f)
                            val counts=bridge.macInputCounts
                            val now=SystemClock.uptimeMillis()
                            display.touch(x,y,true,now);Thread.sleep(100);display.touch(x,y,false,now)
                            Thread.sleep(400)
                            check(bridge.macInputCounts.first>=counts.first+2)
                            record("PASS production-window click x=$x y=$y encrypted press/release")
                        }
                        "resize" -> {
                            val w=request.getInt("width");val h=request.getInt("height")
                            val video=checkNotNull(bridge.macMediaPipeline)
                            val before=video.framesDecoded.get()
                            display.resize(w,h)
                            val deadline=SystemClock.uptimeMillis()+12000
                            while(SystemClock.uptimeMillis()<deadline &&
                                (video.decodedWidth!=(w and -2) || video.decodedHeight!=(h and -2) || video.framesDecoded.get()<=before)) Thread.sleep(50)
                            check(video.decodedWidth==(w and -2) && video.decodedHeight==(h and -2) && video.framesDecoded.get()>before) {
                                "Resize failed view=${w}x$h control=${bridge.getDesktopWidth(handle)}x${bridge.getDesktopHeight(handle)} video=${video.decodedWidth}x${video.decodedHeight}"
                            }
                            check(bridge.getDesktopWidth(handle)==video.decodedWidth && bridge.getDesktopHeight(handle)==video.decodedHeight)
                            Thread.sleep(2000) // Actual TextureView frame + production settling/fade.
                            display.captureWindow("mac-input-resize-${w}x$h.png")
                            val counts=bridge.macInputCounts
                            val now=SystemClock.uptimeMillis()
                            display.touch(.1f,.3f,true,now);Thread.sleep(80);display.touch(.1f,.3f,false,now)
                            val inputDeadline=SystemClock.uptimeMillis()+2000
                            while(bridge.macInputCounts.first<counts.first+2 && SystemClock.uptimeMillis()<inputDeadline) Thread.sleep(50)
                            check(bridge.macInputCounts.first>=counts.first+2) { "Input still masked after resize" }
                            record("PASS real-window resize view=${w}x$h control/video=${video.decodedWidth}x${video.decodedHeight}, new frame, dim/fade completed and touch resumed")
                        }
                        "hover" -> {
                            val before=bridge.macInputCounts.first
                            display.hover(request.getDouble("x").toFloat(),request.getDouble("y").toFloat());Thread.sleep(300)
                            check(bridge.macInputCounts.first>before) { "Native hover did not reach encrypted pointer writer" }
                            record("PASS actual generic hover forwarded")
                        }
                        "ime", "imeDelete" -> {
                            val before=bridge.macInputCounts.second
                            display.ime(request.getString("action")=="imeDelete");Thread.sleep(500)
                            check(bridge.macInputCounts.second>=before+2) { "IME text did not reach streaming writer" }
                            record("PASS real IME InputConnection forwarded noncredential text/delete")
                        }
                        "window" -> display.captureWindow("mac-input-window.png")
                        "key" -> {
                            val code=request.getInt("code")
                            require(code in listOf(android.view.KeyEvent.KEYCODE_X,android.view.KeyEvent.KEYCODE_DEL,android.view.KeyEvent.KEYCODE_TAB,android.view.KeyEvent.KEYCODE_ESCAPE))
                            val before=bridge.macInputCounts.second
                            display.key(code);Thread.sleep(400)
                            check(bridge.macInputCounts.second>=before+2) { "Window key did not reach encrypted writer" }
                            record("PASS actual window noncredential key press/release")
                        }
                        "unlock" -> {
                            check(!unlock && request.getBoolean("visuallyConfirmedOwnPasswordField"));unlock=true
                            check(bridge.submitSavedMacPassword(handle))
                            check(!bridge.submitSavedMacPassword(handle))
                            record("Own saved-password input submitted once after visual field confirmation; no retry")
                        }
                        "finish" -> finished=true
                        else -> error("Unknown input action")
                    }
                }
                Thread.sleep(500)
            }
            check(finished) { "Bounded input inspection expired" }
            display.capture("mac-input-final.png")
        }
        record(MacNativeBaseline(test).run(id))
        return report.readText()
    }
}
