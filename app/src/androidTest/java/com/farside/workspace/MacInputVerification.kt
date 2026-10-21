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
        val profile=pm.getProfiles().single { it.id==id && it.type==ConnectionType.VNC }
        require(!profile.useCloudflare && profile.proxyType==ProxyType.NONE)
        val report=File(context.cacheDir,"mac-input-results.txt").apply { writeText("") }
        fun record(message:String) { report.appendText("${SystemClock.uptimeMillis()}: $message\n") }
        val action=File(context.cacheDir,"mac-input-action.json").apply { delete() }
        record(MacNativeBaseline(test).run(id))
        MacMediaSurface(test,profile.copy(reconnectAutomatically=false),realSessionWindow=true).use { display ->
            check(display.awaitConnection())
            val bridge=checkNotNull(display.bridge);val handle=display.handle
            check(bridge.isMacHevc)
            // Give the real initial resize and its fade time to complete.
            Thread.sleep(8000)
            check(display.capture("mac-input-current.png"))
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
