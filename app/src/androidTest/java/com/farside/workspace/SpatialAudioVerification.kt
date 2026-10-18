package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ResultReceiver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal fun readHorizonAudioState(test: Instrumentation): String = test.uiAutomation.executeShellCommand("dumpsys media.has").use {
    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
}
internal fun horizonSessionIsSpatial(dump: String, session: Int): Boolean =
    Regex("session: $session \\([^\\n]*\\)\\s*<SPATIALIZER: EPS>\\s*engine stream spatial state:\\s+ON").containsMatchIn(dump)

/** Checks actual HAL mixer destinations, separately from successful token calls and writes. */
internal class SpatialAudioVerification(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val main = Handler(Looper.getMainLooper())
    private val windows = mutableListOf<Bundle>()
    private val nativeActivities = mutableMapOf<Int, SpatialAudioFixtureActivity>()
    private val directory = File(context.cacheDir, "spatial-audio").apply { mkdirs() }
    private val evidence = JSONObject()
    private val stages = JSONArray()
    private fun receive(action: (ResultReceiver) -> Unit): Bundle {
        val latch = CountDownLatch(1)
        var result: Bundle? = null
        var success = false
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                success = resultCode == 1; result = resultData; latch.countDown()
            }
        }
        // The remote app process does not load classes from the instrumentation APK.
        val parcel = android.os.Parcel.obtain()
        try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            action(ResultReceiver.CREATOR.createFromParcel(parcel))
        } finally { parcel.recycle() }
        check(latch.await(20, TimeUnit.SECONDS) && success) { "Remote fixture did not complete" }
        return result ?: Bundle.EMPTY
    }
    private fun command(window: Bundle, what: Int = 1, muted: Boolean = false) = receive { reply ->
        Messenger(window.getBinder("commands")).send(Message.obtain().apply {
            this.what = what; arg1 = if (muted) 1 else 0
            data = Bundle().apply { putParcelable("reply", reply) }
        })
    }
    private fun start(index: Int, isolated: Boolean, token: String): Bundle {
        val component = if (isolated) "com.farside.workspace.SpatialAudioIsolatedActivity$index"
            else SpatialAudioFixtureActivity::class.java.name
        val intent = Intent().setClassName(context.packageName, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .setData(android.net.Uri.parse("farside://audio-fixture/${java.util.UUID.randomUUID()}"))
            .putExtra("audioToken", token)
        var local: SpatialAudioFixtureActivity? = null
        val result = receive { ready ->
            intent.putExtra("fixtureReady", ready)
            if (isolated) context.startActivity(intent)
            else local = test.startActivitySync(intent) as SpatialAudioFixtureActivity
        }
        local?.let { nativeActivities[result.getInt("task")] = it }
        windows += result
        check(result.getInt("session") > 0)
        android.util.Log.i("SpatialAudioVerification", "opened pid=${result.getInt("pid")} uid=${result.getInt("uid")} session=${result.getInt("session")} bound=${result.getBoolean("bound")}")
        return result
    }
    private fun launcher() = test.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun shell(command: String): String = test.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun capture(stage: String): List<String> {
        Thread.sleep(1000)
        val log = shell("logcat -d -t 12000")
        File(directory, "$stage.log").writeText(log)
        val has = readHorizonAudioState(test)
        File(directory, "$stage.has.txt").writeText(has)
        val routes = windows.map { window ->
            val session = window.getInt("session")
            val port = Regex("CreateTrackResponse[^\\n]*sessionId: $session,[^\\n]*portId: (\\d+)").findAll(log).lastOrNull()?.groupValues?.get(1)
            val route = port?.let { Regex("(?:added active source $it to |source $it moving to )([A-Za-z]+) mixer")
                .findAll(log).lastOrNull()?.groupValues?.get(1) } ?: "unknown"
            val initialRoute = port?.let { Regex("added active source $it to ([A-Za-z]+) mixer")
                .find(log)?.groupValues?.get(1) } ?: "unknown"
            if (window.getBoolean("spaceAccepted")) {
                check(initialRoute == "Spatial") { "Stream started before its spatial binding: session=$session initial=$initialRoute" }
                check(horizonSessionIsSpatial(has, session)) { "EPS did not enable spatialization for session=$session" }
            }
            JSONObject().apply { put("pid", window.getInt("pid")); put("session", session)
                put("port", port); put("nativeBindingAccepted", window.getBoolean("bound"))
                put("serviceAccepted", window.getBoolean("serviceAccepted"))
                put("spaceAccepted", window.getBoolean("spaceAccepted")); put("space", window.getString("space"))
                put("epsOn", horizonSessionIsSpatial(has, session)); put("initialMixer", initialRoute); put("mixer", route) }
        }
        stages.put(JSONObject().apply { put("stage", stage); put("streams", JSONArray(routes)) })
        android.util.Log.i("SpatialAudioVerification", "$stage: ${routes.joinToString()}")
        return routes.map { it.getString("mixer") }
    }
    private fun move(window: Bundle, pose: SavedWindowPose) {
        val activity = checkNotNull(nativeActivities[window.getInt("task")])
        val managerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
        val tokenClass = Class.forName("horizonos.view.VolumetricWindowToken")
        val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", android.app.Activity::class.java)
            .invoke(null, activity)
        val manager = activity.getSystemService(managerClass)
        test.uiAutomation.adoptShellPermissionIdentity("horizonos.permission.MANAGE_VOLUMETRIC_WINDOWS")
        try { test.runOnMainSync {
            val builderClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            val value = pose.platformPose("horizonos")
            builderClass.getMethod("setPose", tokenClass, value.javaClass).invoke(builder, token, value)
            val transaction = checkNotNull(builderClass.getMethod("build").invoke(builder))
            managerClass.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
        } } finally { test.uiAutomation.dropShellPermissionIdentity() }
        Thread.sleep(800)
        val info = checkNotNull(managerClass.getMethod("getWindowInfo", tokenClass).invoke(manager, token))
        val actual = SavedWindowPose.read(checkNotNull(info.javaClass.getMethod("getPose").invoke(info)))
        check(kotlin.math.abs(actual.x - pose.x) < 0.02f && kotlin.math.abs(actual.z - pose.z) < 0.02f) { "Native fixture movement failed" }
        check(QuestSpatialAudio.windowSpace(activity).toString() == window.getString("space"))
    }
    fun run(mode: String, token: String = "space") {
        val isolated = mode == "spatial-isolation"
        var manager = launcher()
        evidence.put("isolatedProcesses", isolated); evidence.put("token", token); evidence.put("stages", stages)
        try {
            val first = start(0, isolated, token)
            capture("launcher-and-session")
            test.runOnMainSync { manager.finishAndRemoveTask() }
            val single = capture("single-window")
            check(single == listOf("Spatial")) { "Single-window spatial baseline failed: $single" }
            manager = launcher()
            capture("launcher-reopened")
            val second = start(1, isolated, token)
            check(first.getInt("session") != second.getInt("session"))
            check(first.getInt("uid") == second.getInt("uid"))
            check((first.getInt("pid") != second.getInt("pid")) == isolated)
            var multi = capture("two-sessions-and-launcher")
            check(first.getString("space") != second.getString("space"))
            if (token == "space") {
                check(multi.all { it == "Spatial" }) { "Multi-window routing failed: $multi" }
                if (!isolated) {
                    move(first, SavedWindowPose(-0.55f, 0.2f, -1.5f, 0f, 0f, 0f, 1f))
                    move(second, SavedWindowPose(0.55f, 0.2f, -1.5f, 0f, 0f, 0f, 1f))
                    check(capture("windows-moved").all { it == "Spatial" })
                    val state = readHorizonAudioState(test)
                    check(windows.all { horizonSessionIsSpatial(state, it.getInt("session")) })
                    evidence.put("syntheticNativeMovementSpatial", true)
                }
                val previousSession = first.getInt("session")
                val reopened = command(first, 4)
                check(reopened.getInt("session") > 0 && reopened.getInt("session") != previousSession)
                first.putAll(reopened)
                multi = capture("stream-reopened")
                check(multi.all { it == "Spatial" })
                evidence.put("streamReopenSpatial", true)
            }
            check(multi.none { it == "unknown" }) { "Missing HAL routing evidence" }
            command(first, 2, muted = true); Thread.sleep(500)
            val mutedBefore = command(first).getLongArray("stats")!!
            val otherBefore = command(second).getLongArray("stats")!!
            Thread.sleep(600)
            val mutedAfter = command(first).getLongArray("stats")!!
            val otherAfter = command(second).getLongArray("stats")!!
            check(mutedAfter[0] > mutedBefore[0] && mutedAfter[1] == mutedBefore[1])
            check(otherAfter[1] > otherBefore[1])
            command(first, 2, muted = false); Thread.sleep(500)
            check(command(first).getLongArray("stats")!![1] > mutedAfter[1])
            evidence.put("independentMuteResume", true)
            command(first, 3); windows.remove(first)
            check(command(second).getLongArray("stats")!![1] > otherAfter[1])
            evidence.put("independentTeardown", true)
            capture("session-closed")
            test.runOnMainSync { manager.finishAndRemoveTask() }
            check(capture("remaining-single-window") == listOf("Spatial"))
            evidence.put("multiWindowSpatial", multi.all { it == "Spatial" })
            if (token == "space") check(multi.all { it == "Spatial" }) {
                "Process isolation does not restore spatial audio: HAL mixers=$multi"
            }
        } finally {
            for (window in windows.toList()) runCatching { command(window, 3) }
            File(directory, "results.json").writeText(evidence.toString(2))
            launcher()
        }
    }
}
