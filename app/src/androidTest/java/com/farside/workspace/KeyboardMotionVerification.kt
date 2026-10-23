@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import metavrx.layout.compose.SpatialScene
import metavrx.layout.window.compose.state.WindowState
import metavrx.layout.window.compose.state.rememberWindowState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Production companion, real native attachment geometry, no remote connection or IME injection. */
internal class KeyboardMotionVerification(private val test: Instrumentation) {
    fun run(): String {
        val context=test.targetContext
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val visible=mutableStateOf(false)
        val motion=KeyboardPanelMotion()
        lateinit var state:WindowState
        val geometry=KeyboardWindowGeometry(activity)
        val samples=JSONArray()
        val durations=JSONArray()
        val content=activity.findViewById<android.view.View>(android.R.id.content)
        fun main(action:()->Unit)=test.runOnMainSync(action)
        try {
            main {activity.setContent {SpatialScene {MaterialTheme {
                state=rememberWindowState()
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
                RemoteKeyboardPanel(visible.value,state,{},motion) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }}}}
            Thread.sleep(300)
            val parentSize=content.width to content.height
            fun transition(open:Boolean) {
                val start=SystemClock.uptimeMillis()
                main {visible.value=open}
                var finished=false
                val poses=mutableSetOf<String>()
                val sizes=mutableSetOf<Pair<Int,Int>>()
                var intermediateAlpha=false
                var intermediateRoll=false
                while(SystemClock.uptimeMillis()-start<1800) {
                    main {
                        val alpha=motion.opacity.value
                        val roll=motion.roll.value
                        val token=(state.javaClass.methods.single {it.name.startsWith("getToken")}.invoke(state) as kotlinx.coroutines.flow.StateFlow<*>).value
                        val native=token?.let(geometry::read)
                        native?.let {poses+=it.pose;sizes+=it.width to it.height}
                        samples.put(JSONObject().put("open",open).put("ms",SystemClock.uptimeMillis()-start)
                            .put("alpha",alpha).put("roll",roll).put("native",native?.toString()))
                        intermediateAlpha=intermediateAlpha || alpha>0f && alpha<1f
                        intermediateRoll=intermediateRoll || roll>0f && roll<1f
                        // Fades may only paint the fully deployed keyboard.
                        check(alpha<=0f || roll>=.999f) {"Visible keys moved during native travel"}
                        finished=if(open) alpha==1f && roll==1f else alpha==0f && roll==0f && token==null
                    }
                    if(finished) break
                    Thread.sleep(8)
                }
                val elapsed=SystemClock.uptimeMillis()-start
                durations.put(JSONObject().put("open",open).put("ms",elapsed).put("nativePoses",poses.size).put("sizes",sizes.toString()))
                check(finished && elapsed<1000) {"Keyboard transition exceeded one second: open=$open elapsed=$elapsed"}
                check(intermediateRoll && intermediateAlpha) {"Keyboard transition snapped instead of animating"}
                check(poses.size>=3) {"Native attachment did not move smoothly: ${poses.size} observed poses"}
                check(sizes.size==1) {"Backing surface resized during travel: $sizes"}
                check(content.width to content.height==parentSize) {"Desktop resized during keyboard animation"}
                Thread.sleep(150)
            }
            repeat(3) {transition(true);transition(false)}
            // Reverse in mid-travel and in mid-fade; cancellation must resume current values.
            main {visible.value=true};Thread.sleep(180);main {visible.value=false};Thread.sleep(100)
            transition(true);transition(false)
            return "PASS native keyboard roll/fade under one second, ordered opacity, intermediate poses, fixed buffer/desktop size, repeated toggles and interrupted reopening; synthetic fixture, physical Horizon handle appearance/IME remains manual. Durations: $durations"
        } finally {
            File(context.cacheDir,"keyboard-motion.json").writeText(JSONObject().put("durations",durations).put("samples",samples).toString(2))
            main {visible.value=false;geometry.close();activity.finish()}
        }
    }
}
