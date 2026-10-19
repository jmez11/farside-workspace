package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.data.SavedCredential
import com.farside.workspace.data.SoundRedirection
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Temporary protocol spike: one existing connection and one atlas presentation window. */
internal class RdpMonitorProbeVerification(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val report = JSONObject()
    private val stages = JSONArray()
    private val captures = JSONArray()

    fun run(mode: String): String {
        check(BuildConfig.DEBUG)
        if (mode == "import") return importConnection()
        check(mode == "run")
        val manager = ProfileManager(context)
        val original = manager.getProfiles().first { it.type == ConnectionType.RDP }
        check(original.remoteApp.isNullOrEmpty()) { "The diagnostic requires a full desktop profile" }
        val fixture = original.copy(id = "rdp-monitor-probe-${UUID.randomUUID()}", name = "RDP monitor probe",
            enableCamera = false, redirectMicrophone = false, redirectStorage = false,
            redirectClipboard = false, soundRedirection = SoundRedirection.DO_NOT_PLAY,
            reconnectAutomatically = false)
        manager.addProfile(fixture)
        flushProfiles()
        var activity: SessionActivity? = null
        var bridge: FreeRDPBridge? = null
        var handle = 0L
        var initial: LongArray? = null
        report.put("stages", stages).put("captures", captures).put("scope", "Protocol-only; one atlas window, no toolbar feature")
        try {
            val liveActivity = test.startActivitySync(SessionWindowLauncher.intent(context, fixture.id,
                SessionWindowSlots.reserve(context, fixture.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
            activity = liveActivity
            await(60_000, "connected desktop; a pending trust or authentication prompt requires user action") {
                test.runOnMainSync { handle = liveActivity.rdpInstance; bridge = liveActivity.rdpBridge }
                handle != 0L && bridge != null
            }
            val liveBridge = checkNotNull(bridge)
            check(liveBridge.setDisplayProbeActive(handle, true)) { "Debug display probe unavailable" }
            await(20_000, "GFX monitor topology and Display Control capabilities") {
                val state = liveBridge.getDisplayProbeState(handle)
                state.size >= 15 && state[0] == 1L && state[8] == 1L && state[9] == 1L
            }
            initial = liveBridge.getDisplayProbeState(handle)
            val baseline = checkNotNull(initial)
            report.put("capabilities", JSONObject().put("maxMonitors", baseline[1])
                .put("areaFactorA", baseline[2]).put("areaFactorB", baseline[3]))
            check(baseline[1] >= 2) { "Server advertises fewer than two monitors" }
            record("initial", baseline)
            capture(liveActivity, "initial")

            fun apply(name: String, widths: IntArray, heights: IntArray) {
                val previous = liveBridge.getDisplayProbeState(handle)
                var left = 0
                val row = IntArray(widths.size * 5)
                for (i in widths.indices) {
                    row[i * 5] = left
                    row[i * 5 + 1] = 0
                    row[i * 5 + 2] = widths[i]
                    row[i * 5 + 3] = heights[i]
                    row[i * 5 + 4] = if (i == 0) 1 else 0
                    left += widths[i]
                }
                check(liveBridge.requestDisplayProbeLayout(handle, row)) { "Rejected diagnostic layout $name" }
                await(20_000, "server-confirmed topology for $name") {
                    val state = liveBridge.getDisplayProbeState(handle)
                    state[7] > previous[7] && matches(state, row)
                }
                var currentHandle = 0L
                test.runOnMainSync { currentHandle = liveActivity.rdpInstance }
                check(currentHandle == handle) { "Native connection changed during $name" }
                record(name, liveBridge.getDisplayProbeState(handle))
                capture(liveActivity, name)
            }

            apply("two", intArrayOf(1280, 1280), intArrayOf(800, 800))
            apply("secondary-resized", intArrayOf(1280, 1024), intArrayOf(800, 768))
            if (baseline[1] >= 3) {
                apply("three", intArrayOf(1280, 1024, 1024), intArrayOf(800, 768, 768))
                apply("two-after-removal", intArrayOf(1280, 1024), intArrayOf(800, 768))
            }
            apply("one-restored", intArrayOf((baseline[12] - baseline[10] + 1).toInt()),
                intArrayOf((baseline[13] - baseline[11] + 1).toInt()))
            report.put("passed", true).put("sameNativeConnection", true)
            writeReport()
            return "Dynamic monitor add, secondary resize and removal confirmed by GFX topology on one native connection."
        } catch (error: Throwable) {
            report.put("passed", false).put("error", error.message ?: error.javaClass.simpleName)
            bridge?.let { if (handle != 0L) record("failure-observation", it.getDisplayProbeState(handle)) }
            writeReport()
            throw error
        } finally {
            val liveBridge = bridge
            val baseline = initial
            if (liveBridge != null && handle != 0L && baseline != null) {
                val restore = intArrayOf(0, 0, (baseline[12] - baseline[10] + 1).toInt(),
                    (baseline[13] - baseline[11] + 1).toInt(), 1)
                if (liveBridge.requestDisplayProbeLayout(handle, restore)) {
                    val end = android.os.SystemClock.elapsedRealtime() + 8000
                    while (android.os.SystemClock.elapsedRealtime() < end && !matches(liveBridge.getDisplayProbeState(handle), restore)) Thread.sleep(100)
                    report.put("cleanupTopologyRestored", matches(liveBridge.getDisplayProbeState(handle), restore))
                }
                liveBridge.setDisplayProbeActive(handle, false)
            }
            activity?.let { test.runOnMainSync { it.finishAndRemoveTask() } }
            Thread.sleep(1000)
            manager.deleteProfile(fixture.id)
            flushProfiles()
            writeReport()
        }
    }

    private fun importConnection(): String {
        val keyFile = File(context.cacheDir, "rdp-probe-transfer.key")
        val dataFile = File(context.cacheDir, "rdp-probe-profile.aes")
        val key = keyFile.readBytes()
        try {
            val blob = dataFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            val payload = JSONObject(String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8))
            val manager = ProfileManager(context)
            if (!payload.isNull("credential")) manager.addCredential(SavedCredential.fromJson(payload.getJSONObject("credential")))
            val json = payload.getJSONObject("profile")
            manager.addProfile(ConnectionProfile.fromJson(json).copy(cloudflaredParameters = json.optString("cloudflaredParameters")))
            flushProfiles()
            check(manager.getProfiles().any { it.type == ConnectionType.RDP })
            return "Imported one existing RDP connection with credentials re-encrypted by this installation."
        } finally {
            key.fill(0)
            keyFile.delete()
            dataFile.delete()
        }
    }

    private fun matches(state: LongArray, row: IntArray): Boolean {
        if (state.size < 10 || state[9] != (row.size / 5).toLong() || state[6] != 0L) return false
        if (state[4] != row.indices.filter { it % 5 == 2 }.sumOf { row[it] }.toLong() ||
            state[5] != row.indices.filter { it % 5 == 3 }.maxOf { row[it] }.toLong()) return false
        val expected = row.toList().chunked(5).map { listOf(it[0].toLong(), it[1].toLong(),
            (it[0] + it[2] - 1).toLong(), (it[1] + it[3] - 1).toLong(), it[4].toLong()) }.toSet()
        return state.drop(10).chunked(5).toSet() == expected
    }

    private fun record(name: String, state: LongArray) {
        stages.put(JSONObject().put("name", name).put("state", JSONArray(state.toList())))
        writeReport()
    }

    private fun capture(activity: SessionActivity, stage: String) {
        var bitmap: Bitmap? = null
        fun texture(view: View): TextureView? {
            if (view is TextureView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
            return null
        }
        var colorCount = 0
        val observation = JSONObject().put("name", stage)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        do {
            Thread.sleep(300)
            bitmap?.recycle()
            test.runOnMainSync { bitmap = texture(activity.findViewById(android.R.id.content))?.bitmap }
            bitmap?.let { candidate ->
                val colors = mutableSetOf<Int>()
                for (y in 0 until candidate.height step 17) for (x in 0 until candidate.width step 17) {
                    colors += candidate.getPixel(x, y)
                    if (colors.size > 64) break
                }
                colorCount = colors.size
            }
            if (colorCount > 16) break
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        val image = checkNotNull(bitmap) { "No desktop bitmap for $stage" }
        try {
            File(context.cacheDir, "rdp-monitors-$stage.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            captures.put(observation.put("sampledColors", colorCount))
            writeReport()
            check(colorCount > 16) { "Blank desktop at $stage; image colors=$colorCount" }
        } finally { image.recycle() }
    }

    private fun await(timeout: Long, description: String, ready: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (ready()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $description")
    }

    private fun flushProfiles() {
        val prefs = context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        check(prefs.edit().putString("profiles", prefs.getString("profiles", "[]")).commit())
    }

    private fun writeReport() { File(context.cacheDir, "rdp-monitor-probe.json").writeText(report.toString(2)) }
}
