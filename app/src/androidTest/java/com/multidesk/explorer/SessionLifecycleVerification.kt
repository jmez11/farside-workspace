package com.multidesk.explorer

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.window.DialogWindowProvider
import com.multidesk.explorer.data.ConnectionProfile
import com.multidesk.explorer.data.ConnectionType
import com.multidesk.explorer.data.ProfileManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class SessionLifecycleVerification(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val activities = mutableListOf<SessionActivity>()
    private fun restore(backup: JSONObject) {
        val profiles = backup.getJSONArray("profiles")
        ProfileManager(context).saveProfiles((0 until profiles.length()).map { ConnectionProfile.fromJson(profiles.getJSONObject(it)) })
        val prefs = context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
        val edit = prefs.edit().clear()
        val saved = backup.getJSONObject("positions")
        saved.keys().forEach { key -> edit.putString(key, saved.getString(key)) }
        check(edit.commit())
    }

    fun run(mode: String) {
        // Keep recovery in app-private cache in case an instrumentation process crashes.
        val backupFile = File(context.cacheDir, "lifecycle-verification-backup.json")
        if (backupFile.exists()) { restore(JSONObject(backupFile.readText())); backupFile.delete() }
        val profiles = ProfileManager(context).getProfiles()
        val prefs = context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
        val backup = JSONObject().put("profiles", JSONArray(profiles.map { it.toJson() }))
            .put("positions", JSONObject(prefs.all))
        backupFile.writeText(backup.toString())
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        try {
            when (mode) {
                "disconnect" -> disconnect(profiles.first { it.type == ConnectionType.RDP })
                "placement" -> placement(profiles.first { it.type == ConnectionType.RDP })
                else -> error("Unknown lifecycle mode $mode")
            }
        } finally {
            activities.forEach { activity -> test.runOnMainSync { if (!activity.isDestroyed) activity.finish() } }
            Thread.sleep(1000)
            restore(backup)
            backupFile.delete()
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun launch(profile: String, slot: Int? = null): SessionActivity {
        val ordinal = slot ?: SessionWindowSlots.reserve(context, profile)
        val activity = test.startActivitySync(SessionWindowLauncher.intent(context, profile, ordinal).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            SessionWindowLauncher.options(SessionWindowPositionStore(context).load(profile, ordinal))) as SessionActivity
        activities += activity
        return activity
    }

    private fun close(activity: Activity) {
        test.runOnMainSync { activity.finish() }
        repeat(30) { if (activity.isDestroyed) return; Thread.sleep(100) }
        error("Session window did not close")
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        fun search(value: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(value)) return value
            for (i in 0 until value.childCount) value.getChild(i)?.let { search(it)?.let { found -> return found } }
            return null
        }
        val windows = test.uiAutomation.windowsOnAllDisplays
        for (i in 0 until windows.size()) for (window in windows.valueAt(i))
            window.root?.let { if (it.packageName == context.packageName) search(it)?.let { found -> return found } }
        return null
    }
    private fun find(label: String) = node { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun click(label: String) {
        var control = checkNotNull(find(label)) { "Missing $label" }
        while (!control.isClickable && control.parent != null) control = control.parent
        check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        Thread.sleep(250)
    }
    private fun await(label: String) {
        repeat(100) { if (find(label) != null) return; Thread.sleep(200) }
        error("Missing $label")
    }
    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun connected(activity: Activity) {
        repeat(100) {
            if (find("Certificate can't be verified") != null) click("Connect") // Session-only test acceptance.
            var content = false
            test.runOnMainSync {
                texture(activity.window.decorView)?.bitmap?.let { bitmap ->
                    val first = bitmap.getPixel(0, 0)
                    content = (1..8).any { i -> bitmap.getPixel(bitmap.width * i / 9, bitmap.height * i / 9) != first }
                    bitmap.recycle()
                }
            }
            if (content && (activity as SessionActivity).rdpInstance != 0L) return
            Thread.sleep(200)
        }
        error("No connected desktop")
    }
    private fun captureDialog(name: String) {
        fun window(view: View): android.view.Window? {
            if (view is DialogWindowProvider) return view.window
            if (view is ViewGroup) for (i in 0 until view.childCount) window(view.getChildAt(i))?.let { return it }
            return null
        }
        var target: android.view.Window? = null
        test.runOnMainSync { target = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { window(it) } }
        val dialog = checkNotNull(target)
        val bitmap = Bitmap.createBitmap(dialog.decorView.width, dialog.decorView.height, Bitmap.Config.ARGB_8888)
        val ready = CountDownLatch(1)
        var result = -1
        test.runOnMainSync { android.view.PixelCopy.request(dialog, bitmap, { result = it; ready.countDown() }, Handler(Looper.getMainLooper())) }
        check(ready.await(5, TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun disconnect(original: ConnectionProfile) {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val workers = Executors.newCachedThreadPool()
        val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        val ready = CountDownLatch(1)
        try {
            workers.execute {
                while (!listener.isClosed) try {
                    val client = listener.accept().also { sockets += it }
                    val server = Socket().apply { connect(java.net.InetSocketAddress(original.host, original.port), 5000) }.also { sockets += it }
                    ready.countDown()
                    workers.execute { try { client.getInputStream().copyTo(server.getOutputStream()) } catch (_: Exception) {} }
                    workers.execute { try { server.getInputStream().copyTo(client.getOutputStream()) } catch (_: Exception) {} }
                } catch (error: Exception) {
                    if (!listener.isClosed) Log.e("LifecycleTest", "Proxy failed", error)
                    ready.countDown()
                    break
                }
            }
            val profile = original.copy(id = "verification-disconnect", name = "Disconnect verification",
                host = "127.0.0.1", port = listener.localPort, useCloudflare = false, redirectMicrophone = false, enableCamera = false)
            ProfileManager(context).addProfile(profile)
            val activity = launch(profile.id)
            check(ready.await(10, TimeUnit.SECONDS))
            connected(activity)
            check(find("Disconnected") == null)
            sockets.forEach { it.setSoLinger(true, 0); it.close() } // Real TCP loss; headset networking stays up.
            await("Disconnected")
            val message = checkNotNull(node { it.text?.toString()?.startsWith("The RDP session has disconnected.") == true }).text.toString()
            check(message.contains("\n\n") && message.substringAfter("\n\n").isNotBlank()) { "No disconnect reason: $message" }
            captureDialog("rdp-disconnected.png")
            Thread.sleep(500)
            check(!activity.isFinishing && find("OK") != null) { "Session closed before acknowledgment" }
            click("OK")
            repeat(30) { if (!activity.isDestroyed) Thread.sleep(100) }
            check(activity.isDestroyed) { "OK did not close the session" }
            fun remoteNotifications(): Int {
                return android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                    "logcat -d --pid=${android.os.Process.myPid()} -s FreeRDP-JNI"
                )).bufferedReader().use { it.readLines().count { line -> line.contains("RDP_DISCONNECTED: established=1") } }
            }
            val normal = launch(profile.id)
            connected(normal)
            val before = remoteNotifications()
            close(normal)
            Thread.sleep(700)
            check(remoteNotifications() == before) { "Normal window closure emitted a remote disconnect" }
            Log.i("LifecycleTest", "PASS: real proxy TCP reset, disconnect reason, OK closes window, normal close has no remote-disconnect notification")
        } finally {
            listener.close()
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            workers.shutdownNow()
        }
    }

    private fun readPose(activity: Activity): SavedWindowPose {
        var result: SavedWindowPose? = null
        test.runOnMainSync {
            val managerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(managerClass)
            val tokenClass = Class.forName("horizonos.view.VolumetricWindowToken")
            val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity)
            val info = managerClass.getMethod("getWindowInfo", tokenClass).invoke(manager, token)
            result = SavedWindowPose.read(info.javaClass.getMethod("getPose").invoke(info))
        }
        return checkNotNull(result)
    }
    private fun move(activity: Activity, pose: SavedWindowPose) {
        test.runOnMainSync {
            val managerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(managerClass)
            val tokenClass = Class.forName("horizonos.view.VolumetricWindowToken")
            val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity)
            val value = pose.platformPose("horizonos")
            val builderClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            builderClass.getMethod("setPose", tokenClass, value.javaClass).invoke(builder, token, value)
            val transaction = builderClass.getMethod("build").invoke(builder)
            managerClass.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
        }
        Thread.sleep(800)
    }
    private fun near(actual: SavedWindowPose, expected: SavedWindowPose): Boolean =
        kotlin.math.abs(actual.x - expected.x) < 0.02f && kotlin.math.abs(actual.y - expected.y) < 0.02f && kotlin.math.abs(actual.z - expected.z) < 0.02f

    private fun placement(original: ConnectionProfile) {
        val profile = original.copy(id = "verification-placement", name = "Placement verification",
            host = "127.0.0.1", port = 1, user = "", pass = "", useCloudflare = false, redirectMicrophone = false, enableCamera = false)
        ProfileManager(context).addProfile(profile)
        val store = SessionWindowPositionStore(context)
        val first = launch(profile.id)
        Thread.sleep(1500)
        val second = launch(profile.id)
        Thread.sleep(1500)
        check(first.positionSlot == 0 && second.positionSlot == 1) { "Session slots collided: ${first.positionSlot}/${second.positionSlot}" }
        test.uiAutomation.adoptShellPermissionIdentity("horizonos.permission.MANAGE_VOLUMETRIC_WINDOWS")
        val base = readPose(first)
        val left = base.copy(x = base.x - 0.15f)
        val right = base.copy(x = base.x + 0.15f)
        move(first, left)
        move(second, right)
        check(near(readPose(first), left) && near(readPose(second), right))
        test.uiAutomation.dropShellPermissionIdentity()
        check(store.load(profile.id, 0)?.let { near(it, left) } == true)
        check(store.load(profile.id, 1)?.let { near(it, right) } == true)
        close(first)
        val reopenedFirst = launch(profile.id)
        Thread.sleep(1800)
        check(reopenedFirst.positionSlot == 0) { "Closed slot was not reused" }
        val actualFirst = readPose(reopenedFirst)
        check(store.load(profile.id, 1)?.let { near(it, right) } == true) { "Reopening first overwrote second slot" }
        close(second)
        val reopenedSecond = launch(profile.id)
        Thread.sleep(1800)
        check(reopenedSecond.positionSlot == 1)
        val actualSecond = readPose(reopenedSecond)
        Log.i("LifecycleTest", "Unprivileged restore: slot0=${near(actualFirst, left)} slot1=${near(actualSecond, right)}; first=$actualFirst expected=$left; second=$actualSecond expected=$right")
        File(context.cacheDir, "window-placement-results.json").writeText(JSONObject()
            .put("slot0Restored", near(actualFirst, left)).put("slot1Restored", near(actualSecond, right))
            .put("expected0", left.encode()).put("actual0", actualFirst.encode())
            .put("expected1", right.encode()).put("actual1", actualSecond.encode()).toString(2))
        // Record OS-denied placement separately from storage/slot correctness.
        check(SavedWindowPose.decode("[0,0,0,0,0,0,0]") == null)
        check(SavedWindowPose.decode("bad json") == null)
        check(SessionWindowPositionStore(context).load("other-profile", 0) == null)
    }
}
