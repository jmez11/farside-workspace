package com.farside.workspace

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
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
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
        val restored = JSONArray((0 until profiles.length()).map { ConnectionProfile.fromJson(profiles.getJSONObject(it)).toJson() })
        // Instrumentation exits the process immediately; apply() can lose fixture cleanup.
        check(context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles", restored.toString()).commit())

    }

    fun run(mode: String) {
        if (mode == "capability") {
            WindowCapabilityVerification(test).run()
            return
        }
        // Keep recovery in app-private cache in case an instrumentation process crashes.
        val backupFile = File(context.cacheDir, "lifecycle-verification-backup.json")
        if (backupFile.exists()) { restore(JSONObject(backupFile.readText())); backupFile.delete() }
        val profiles = ProfileManager(context).getProfiles()
        val backup = JSONObject().put("profiles", JSONArray(profiles.map { it.toJson() }))
        backupFile.writeText(backup.toString())
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        try {
            when (mode) {
                "disconnect" -> disconnect(profiles.firstOrNull { it.type == ConnectionType.RDP && it.name == "LAPTOP-DDJV33M6" }
                    ?: profiles.first { it.type == ConnectionType.RDP })
                "placement" -> placement(profiles.first { it.type == ConnectionType.RDP })
                "launch" -> launchPlacement()
                "manipulation" -> manipulation()
                "back" -> launcherBack()
                "backFixture" -> launcherBack(fixtureOnly = true)
                "backLive" -> launcherBack(true)
                "card" -> cardActions(profiles.firstOrNull { it.name == "LAPTOP-DDJV33M6" && it.type == ConnectionType.RDP }
                    ?: error("Saved LAPTOP-DDJV33M6 profile unavailable"), false)
                "cardSynthetic" -> cardActions(ConnectionProfile("verification-card-template", "Card verification",
                    ConnectionType.RDP, "127.0.0.1", 1, "", ""), true)
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
        val activity = test.startActivitySync(SessionWindowLauncher.intent(context, profile, ordinal).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
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
    private fun cardActions(original: ConnectionProfile, synthetic: Boolean) {
        val profile = original.copy(id = "verification-card-background", name = "Card verification",
            cardBackground = null, redirectMicrophone = false, enableCamera = false)
        ProfileManager(context).addProfile(profile)
        var picture: File? = null
        try {
            val session = launch(profile.id)
            var selected = session
            if (synthetic) {
                Thread.sleep(1500)
                val second = launch(profile.id)
                Thread.sleep(1500)
                selected = second
                test.runOnMainSync {
                    second.setContent {
                        Box(Modifier.fillMaxSize().background(Color(0xFF2376B5))) {
                            Text("Card capture fixture", color = Color.White)
                        }
                    }
                    SessionCardActions.connected(session, true)
                    SessionCardActions.connected(second, true)
                    SessionCardActions.focused(session)
                    check(SessionCardActions.selectedTask(profile.id) == session.taskId)
                    SessionCardActions.focused(second)
                    check(SessionCardActions.selectedTask(profile.id) == second.taskId)
                }
            } else connected(session)
            val launcher = test.startActivitySync(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            if (synthetic) test.runOnMainSync { SessionCardActions.connected(session, true) }
            check((SessionCardActions.connectedCounts[profile.id] ?: 0) > 0) { "Session card registry did not mark the window connected" }
            repeat(12) {
                if (find("Use screenshot of Card verification as card background") != null) return@repeat
                find("Connections")?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(150)
            }
            await("Use screenshot of Card verification as card background")
            click("Use screenshot of Card verification as card background")
            var saved: String? = null
            for (attempt in 0 until 100) {
                saved = ProfileManager(context).getProfiles().firstOrNull { it.id == profile.id }?.cardBackground
                if (saved != null) break
                Thread.sleep(100)
            }
            val removeLabel = context.getString(R.string.remove_connection_photo, profile.name)
            val captureLabel = context.getString(R.string.capture_connection, profile.name)
            await(removeLabel)
            check(find(captureLabel) == null) { "Camera action still shown with a saved photo" }
            fun captureLauncher(name: String) {
                lateinit var image: Bitmap
                val ready = CountDownLatch(1)
                var result = -1
                test.runOnMainSync {
                    image = Bitmap.createBitmap(launcher.window.decorView.width, launcher.window.decorView.height, Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(launcher.window, image, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
                }
                check(ready.await(5, TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
                File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            captureLauncher("card-remove-photo.png")
            val name = checkNotNull(saved) { "Card screenshot was not saved" }
            picture = File(context.filesDir, "card-backgrounds/$name")
            check(picture.exists() && picture.length() > 1000L) { "Card background file is missing or empty" }
            val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeFile(picture.path))
            check(bitmap.width > 0 && bitmap.height > 0)
            if (synthetic) check(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff != 0)
            bitmap.recycle()
            if (synthetic) {
                test.runOnMainSync { SessionCardActions.connectedCounts[profile.id] = 0 }
                await(removeLabel)
                click(removeLabel)
                check(ProfileManager(context).getProfiles().single { it.id == profile.id }.cardBackground == null)
                check(!picture.exists()) { "Removed photo file still exists" }
                check(find(removeLabel) == null && find(captureLabel) == null) { "Disconnected card retained photo controls after removal" }
                test.runOnMainSync { SessionCardActions.connected(selected, true) }
                await(captureLabel)
                captureLauncher("card-take-photo.png")
                test.runOnMainSync {
                    selected.sshCaptureView = object : View(context) {
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            canvas.drawColor(android.graphics.Color.rgb(12, 144, 72))
                            canvas.drawText("SSH capture fixture", 20f, 40f,
                                android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f })
                        }
                    }.apply { layout(0, 0, 640, 400) }
                }
                click("Use screenshot of Card verification as card background")
                var next: String? = null
                for (attempt in 0 until 100) {
                    next = ProfileManager(context).getProfiles().firstOrNull { it.id == profile.id }?.cardBackground
                    if (next != null && next != name) break
                    Thread.sleep(100)
                }
                check(next != null && next != name) { "Terminal view capture was not saved" }
                picture = File(context.filesDir, "card-backgrounds/$next")
                val terminalImage = checkNotNull(android.graphics.BitmapFactory.decodeFile(picture.path))
                val center = terminalImage.getPixel(terminalImage.width / 2, terminalImage.height / 2)
                check(android.graphics.Color.green(center) > android.graphics.Color.red(center) + 80 &&
                    android.graphics.Color.green(center) > android.graphics.Color.blue(center) + 40)
                terminalImage.recycle()
                await(removeLabel)
                check(find(captureLabel) == null)
            }
            check(!selected.isDestroyed && selected.cardConnected)
            check(SessionCardActions.selectedTask(profile.id) == selected.taskId)
            if (synthetic) checkNotNull(picture).copyTo(File(context.cacheDir, "card-synthetic.jpg"), overwrite = true)
            File(context.cacheDir, "card-actions-results.json").writeText(JSONObject()
                .put("captured", true).put("removedAndRecaptured", synthetic).put("width", session.window.decorView.width)
                .put("task", selected.taskId).put("syntheticConnection", synthetic).toString(2))
            test.runOnMainSync { launcher.finish() }
        } finally {
            picture?.delete()
        }
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
                host = "127.0.0.1", port = listener.localPort, useCloudflare = false,
                proxyType = com.farside.workspace.data.ProxyType.NONE,
                redirectMicrophone = false, enableCamera = false, reconnectAutomatically = false)
            ProfileManager(context).addProfile(profile)
            val activity = launch(profile.id)
            check(ready.await(10, TimeUnit.SECONDS))
            connected(activity)
            check(find("Disconnected") == null)
            sockets.forEach { it.setSoLinger(true, 0); it.close() } // Real TCP loss; headset networking stays up.
            await("Disconnected")
            captureDialog("rdp-disconnected.png")
            Thread.sleep(500)
            check(!activity.isFinishing && find("Reconnect now") != null) { "Reconnect action missing" }
            check(find("Reconnect Automatically") != null) { "Automatic reconnect switch missing" }
            check(node { it.text?.toString()?.startsWith("Reconnecting in") == true } == null) { "Countdown shown while disabled" }
            click("Reconnect now")
            connected(activity)
            check(find("Disconnected") == null) { "Reconnect did not dismiss the dialog" }
            sockets.forEach { it.setSoLinger(true, 0); it.close() }
            await("Disconnected")
            click("Cancel")
            repeat(30) { if (!activity.isDestroyed) Thread.sleep(100) }
            check(activity.isDestroyed) { "Cancel did not close the session" }
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
            Log.i("LifecycleTest", "PASS: real proxy TCP reset, manual reconnect, cancellation, and normal close")
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

    private fun windowVisible(activity: Activity): Boolean {
        var visible = false
        test.runOnMainSync {
            val cls = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
            val manager = activity.getSystemService(cls)
            val token = Class.forName("horizonos.app.ActivityExt")
                .getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity)
            val info = cls.getMethod("getWindowInfo", Class.forName("horizonos.view.VolumetricWindowToken")).invoke(manager, token)
            visible = info.javaClass.getMethod("isVisible").invoke(info) as Boolean
        }
        return visible
    }

    private fun launcherBack(liveOnly: Boolean = false, fixtureOnly: Boolean = false) {
        val replaceSession = !WindowPlacementSupport.isAvailable()
        val report = JSONArray()
        var launcher: MainActivity? = null
        val latest = java.util.concurrent.atomic.AtomicReference<MainActivity?>()
        val application = context.applicationContext as android.app.Application
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) { if (activity is MainActivity) latest.set(activity) }
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) latest.set(activity) }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            for ((index, type) in (if (liveOnly) emptyList() else ConnectionType.values().toList()).withIndex()) {
                val profile = ConnectionProfile("verification-back-$type", "Back $type", type, "127.0.0.1", 1, "", "")
                ProfileManager(context).addProfile(profile)
                val session = launch(profile.id)
                Thread.sleep(1200)
                val source = if (fixtureOnly) readPose(session) else if (type == ConnectionType.VNC)
                    SavedWindowPose(index * 0.4f, 0.2f, -1.5f, 0f, 0.70710677f, 0f, 0.70710677f)
                    else SavedWindowPose(index * 0.4f, 0.2f, -1.5f, 0f, 0f, 0f, 1f)
                if (!fixtureOnly) {
                    test.uiAutomation.adoptShellPermissionIdentity()
                    move(session, source)
                    test.uiAutomation.dropShellPermissionIdentity()
                }
                check(near(readPose(session), source)) { "Fixture session placement failed" }
                val expected = if (replaceSession) source else if (type == ConnectionType.VNC) source.copy(x = source.x + 0.15f)
                    else source.copy(z = source.z + 0.15f)
                repeat(2) { attempt ->
                    test.runOnMainSync {
                        if (attempt == 0) session.onBackPressedDispatcher.onBackPressed() else check(session.onNavigateUp())
                    }
                    Thread.sleep(1500)
                    launcher = latest.get()
                    val current = checkNotNull(launcher) { "Back did not open launcher" }
                    Thread.sleep(1200)
                    val tasks = context.getSystemService(android.app.ActivityManager::class.java).appTasks
                        .filter { it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name && it.taskInfo.isRunning }
                    check(tasks.size == 1) { "Expected one launcher task, found ${tasks.size}" }
                    val actual = readPose(current)
                    val sessionVisible = windowVisible(session)
                    report.put(JSONObject().put("type", type.name).put("attempt", attempt).put("expected", expected.encode())
                        .put("actual", actual.encode()).put("launcherTask", current.taskId).put("sessionAlive", !session.isDestroyed)
                        .put("replaceSession", replaceSession).put("sessionVisible", sessionVisible)
                        .put("poseMatched", near(actual, expected)))
                    File(context.cacheDir, "launcher-back-results.json").writeText(report.toString(2))
                    if (!fixtureOnly) {
                        check(near(actual, expected)) { "Launcher did not move in front of $type: ${actual.encode()} expected ${expected.encode()}" }
                        check(kotlin.math.abs(actual.qy - expected.qy) < 0.001f && kotlin.math.abs(actual.qw - expected.qw) < 0.001f)
                    }
                    if (!fixtureOnly) check(near(readPose(session), source)) { "Back moved the session" }
                    check(!session.isDestroyed) { "Back closed session" }
                    check(sessionVisible == !replaceSession) { "Back session visibility disagrees with OS policy" }
                    if (attempt == 0) {
                        if (!fixtureOnly) {
                            test.uiAutomation.adoptShellPermissionIdentity()
                            move(current, expected.copy(x = expected.x + 0.6f))
                            test.uiAutomation.dropShellPermissionIdentity()
                        }
                        test.runOnMainSync {
                            // Android task focus alone does not restore a Horizon-hidden window.
                            // This is an owned-window API restore, not a physical Home gesture.
                            if (replaceSession) LauncherWindowLauncher.setSessionVisible(session, true)
                            context.getSystemService(android.app.ActivityManager::class.java).appTasks
                                .first { it.taskInfo.taskId == session.taskId }.moveToFront()
                        }
                        Thread.sleep(800)
                        check(windowVisible(session)) { "Minimized session did not restore when brought forward" }
                    }
                }
                close(session)
            }
            val rotated = LauncherWindowLauncher.inFront(floatArrayOf(0f, 0f, 0f, 0f, 0.70710677f, 0f, 0.70710677f))
            check(kotlin.math.abs(rotated[0] - 0.15f) < 0.001f && kotlin.math.abs(rotated[2]) < 0.001f)
            val liveProfile = ProfileManager(context).getProfiles().firstOrNull { it.type == ConnectionType.RDP && it.name.startsWith("LAPTOP-") }
            check(!liveOnly || liveProfile != null) { "The first LAPTOP connection is unavailable" }
            if (liveProfile != null && !fixtureOnly) {
                val live = launch(liveProfile.id)
                connected(live)
                val instance = live.rdpInstance
                val source = readPose(live)
                val expected = LauncherWindowLauncher.inFront(floatArrayOf(source.x, source.y, source.z, source.qx, source.qy, source.qz, source.qw), if (replaceSession) 0f else 0.15f)
                repeat(2) {
                    latest.set(null)
                    test.runOnMainSync {
                        // A desktop pointer-down gives the RDP Compose node keyboard
                        // focus, which direct dispatcher calls never exercised.
                        val touchTime = android.os.SystemClock.uptimeMillis()
                        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                            val touch = android.view.MotionEvent.obtain(touchTime, touchTime + 20, action, 40f, 40f, 0)
                            live.window.decorView.dispatchTouchEvent(touch)
                            touch.recycle()
                        }
                    }
                    Thread.sleep(150)
                    test.runOnMainSync {
                        val now = android.os.SystemClock.uptimeMillis()
                        live.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0))
                        live.dispatchKeyEvent(android.view.KeyEvent(now, now + 20, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0))
                    }
                    Thread.sleep(2000)
                    launcher = checkNotNull(latest.get()) { "Dispatched Android Back was consumed before opening the launcher" }
                    val actual = readPose(checkNotNull(launcher))
                    check(near(actual, source.copy(x = expected[0], y = expected[1], z = expected[2]))) { "Live Back launcher placement failed" }
                    check(context.getSystemService(android.app.ActivityManager::class.java).appTasks.count {
                        it.taskInfo.isRunning && it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name
                    } == 1) { "Live Back duplicated the launcher" }
                }
                connected(live)
                check(live.rdpInstance == instance && !live.isDestroyed) { "Back restarted or closed live RDP" }
                report.put(JSONObject().put("liveProfile", liveProfile.name).put("sameRdpInstance", true).put("desktopAfterBack", true)
                    .put("activityKeyDispatch", true).put("repeatedBackSingletonPlacement", true))
                File(context.cacheDir, if (liveOnly) "launcher-back-live-results.json" else "launcher-back-results.json").writeText(report.toString(2))
                close(live)
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            launcher?.let { if (!it.isDestroyed) close(it) }
        }
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

    private fun launchPlacement() {
        val profile = ConnectionProfile("verification-launch", "Launch verification", ConnectionType.RDP,
            "127.0.0.1", 1, "", "")
        val manager = ProfileManager(context)
        manager.addProfile(profile)
        val legacy = context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
        val unsafe = SavedWindowPose(50f, -20f, 50f, 0f, 1f, 0f, 0f)
        check(legacy.edit().putString("${profile.id}/0", unsafe.encode()).commit())
        val launcher = test.startActivitySync(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val created = java.util.concurrent.LinkedBlockingQueue<SessionActivity>()
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                if (activity is SessionActivity && activity.intent.getStringExtra("profileId") == profile.id) created.add(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        launcher.application.registerActivityLifecycleCallbacks(callbacks)
        try {
            repeat(12) {
                if (find(profile.name) == null) {
                    node { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(250)
                }
            }
            await(profile.name)
            val launcherPose = readPose(launcher)
            val placementAvailable = WindowPlacementSupport.isAvailable()
            click(profile.name)
            val opened = checkNotNull(created.poll(10, TimeUnit.SECONDS)) { "Click did not launch" }
            activities += opened
            Thread.sleep(1800)
            val actual = readPose(opened)
            check(launcher.isDestroyed == !placementAvailable) { "Launcher replacement disagrees with OS policy" }
            if (!placementAvailable) {
                check(context.getSystemService(android.app.ActivityManager::class.java).appTasks.none {
                    it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name
                }) { "Earlier-OS launch retained a launcher task" }
                check(near(actual, launcherPose)) { "Session did not replace launcher pose: ${actual.encode()} vs ${launcherPose.encode()}" }
            }
            check(!near(actual, unsafe)) { "Unsafe saved pose was restored" }
            check(kotlin.math.abs(actual.x) < 20 && kotlin.math.abs(actual.y) < 20 && kotlin.math.abs(actual.z) < 20)
            check(context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE).all.isEmpty()) { "Obsolete saved positions were not removed" }
            val sessionWidth = opened.window.decorView.width
            val sessionHeight = opened.window.decorView.height
            val firstSlot = opened.sessionSlot
            close(opened)
            val resumedLauncher = if (placementAvailable) {
                test.runOnMainSync { launcher.startActivity(android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                launcher
            } else test.startActivitySync(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val launchHint = context.getString(if (placementAvailable) R.string.open_or_place_hint else R.string.click_to_open_hint)
            await(launchHint)
            check(find("Click to open") == null && find("Drag to place") == null) { "Separate launch-mode controls remain" }

            lateinit var clip: android.content.ClipData
            lateinit var pending: android.app.PendingIntent
            test.runOnMainSync {
                val payload = ConnectionSpatialDrag.payload(resumedLauncher.window.decorView, profile)
                clip = payload.first
                pending = payload.second
            }
            check(clip.description.hasMimeType(ConnectionSpatialDrag.MIME_ACTIVITY))
            check(clip.itemCount == 1 && clip.getItemAt(0).intent.getParcelableExtra(
                ConnectionSpatialDrag.EXTRA_PENDING_INTENT, android.app.PendingIntent::class.java) == pending)
            check(pending.isImmutable && pending.isActivity)
            val shadowSize = android.graphics.Point()
            val shadowTouch = android.graphics.Point()
            lateinit var shadow: android.view.View.DragShadowBuilder
            test.runOnMainSync {
                shadow = ConnectionSpatialDrag.shadow(resumedLauncher.window.decorView, profile.name)
                shadow.onProvideShadowMetrics(shadowSize, shadowTouch)
            }
            check(shadowSize.x == sessionWidth && shadowSize.y == sessionHeight) {
                "Preview ${shadowSize.x}x${shadowSize.y} does not match opened window ${sessionWidth}x${sessionHeight}"
            }
            check(shadowTouch.x == shadowSize.x / 2 && shadowTouch.y == shadowSize.y / 2)
            val preview = Bitmap.createBitmap(shadowSize.x, shadowSize.y, Bitmap.Config.ARGB_8888)
            test.runOnMainSync { shadow.onDrawShadow(android.graphics.Canvas(preview)) }
            File(context.cacheDir, "window-drag-preview.png").outputStream().use {
                preview.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            preview.recycle()
            // Canceled placement must not occupy a session ordinal or create a window.
            pending.cancel()
            check(runCatching { pending.send() }.exceptionOrNull() is android.app.PendingIntent.CanceledException)
            val available = SessionWindowSlots.reserve(context, profile.id)
            check(available == firstSlot)
            SessionWindowSlots.release(profile.id, available)

            // Exercise the real entry point: unsupported OS versions must exit before
            // preparing a request/token. No shell permission identity is adopted.
            var dragStarted = false
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                "am task focus ${resumedLauncher.taskId}")).use { it.readBytes() }
            Thread.sleep(400)
            val nativeTouch = android.graphics.Rect()
            checkNotNull(find(profile.name)).getBoundsInScreen(nativeTouch)
            val touchTime = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(touchTime, touchTime, android.view.MotionEvent.ACTION_DOWN,
                nativeTouch.exactCenterX(), nativeTouch.exactCenterY(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            check(test.uiAutomation.injectInputEvent(down, true))
            down.recycle()
            test.runOnMainSync {
                val root = resumedLauncher.window.decorView
                dragStarted = ConnectionSpatialDrag.start(root, profile)
                if (!placementAvailable) check(WindowLaunchMonitor.pendingCount == 0)
                if (dragStarted) root.cancelDragAndDrop()
            }
            val up = android.view.MotionEvent.obtain(touchTime, android.os.SystemClock.uptimeMillis(),
                if (placementAvailable) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_CANCEL,
                nativeTouch.exactCenterX(), nativeTouch.exactCenterY(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            test.uiAutomation.injectInputEvent(up, true) // Cancellation may already have released the touch focus.
            up.recycle()
            check(dragStarted == placementAvailable) { "Native drag disagrees with OS support policy" }
            Thread.sleep(600)
            check(created.isEmpty()) { "Canceled native drag opened a session" }

            fun dragStarts(): Int = android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                "logcat -d --pid=${android.os.Process.myPid()} -s SessionWindow")).bufferedReader().use {
                it.readLines().count { line -> line.contains("Native connection placement drag started") }
            }
            val beforeGesture = dragStarts()
            val bounds = android.graphics.Rect()
            checkNotNull(find(profile.name)).getBoundsInScreen(bounds)
            check(!bounds.isEmpty)
            val gestureTime = android.os.SystemClock.uptimeMillis()
            fun pointer(action: Int, x: Float) {
                val event = android.view.MotionEvent.obtain(gestureTime, android.os.SystemClock.uptimeMillis(), action,
                    x, bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { test.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
            }
            pointer(android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX())
            // The card starts placement on long press; respect the device's
            // timeout before moving rather than relying on incidental test delays.
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
            pointer(android.view.MotionEvent.ACTION_MOVE, bounds.exactCenterX() + 70)
            Thread.sleep(300)
            check(dragStarts() == beforeGesture + if (placementAvailable) 1 else 0) { "Card drag disagrees with OS support policy" }
            if (placementAvailable) test.runOnMainSync { resumedLauncher.window.decorView.cancelDragAndDrop() }
            pointer(android.view.MotionEvent.ACTION_UP, bounds.exactCenterX() + 70)
            Thread.sleep(600)
            check(created.isEmpty())
            test.runOnMainSync { check(WindowLaunchMonitor.pendingCount == 0) }

            val capture = Bitmap.createBitmap(resumedLauncher.window.decorView.width, resumedLauncher.window.decorView.height, Bitmap.Config.ARGB_8888)
            val captured = CountDownLatch(1)
            var captureResult = -1
            test.runOnMainSync { android.view.PixelCopy.request(resumedLauncher.window, capture,
                { captureResult = it; captured.countDown() }, Handler(Looper.getMainLooper())) }
            check(captured.await(5, TimeUnit.SECONDS) && captureResult == android.view.PixelCopy.SUCCESS)
            File(context.cacheDir, "window-launch-card-gestures.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            capture.recycle()

            // Emulate Home's PendingIntent send with a chosen pose; surrounding lifecycle is real.
            // This verifies no delayed restore overwrites drop options, not physical ray/drop tracking.
            val nativePose = readPose(resumedLauncher).copy(x = actual.x + 0.25f, y = actual.y, z = actual.z)
            val options = android.app.ActivityOptions.makeBasic()
            val value = nativePose.platformPose("horizonos")
            Class.forName("horizonos.app.ActivityOptionsExt").getMethod("setPose", android.app.ActivityOptions::class.java, value.javaClass)
                .invoke(null, options, value)
            test.runOnMainSync {
                val payload = ConnectionSpatialDrag.payload(resumedLauncher.window.decorView, profile)
                pending = payload.second
            }
            pending.send(context, 0, null, null, null, null, options.toBundle())
            val dropped = checkNotNull(created.poll(10, TimeUnit.SECONDS)) { "Synthetic Home drop did not create an Activity" }
            activities += dropped
            Thread.sleep(2000)
            val dropActual = readPose(dropped)
            check(dropped.sessionSlot == firstSlot)
            check(!near(dropActual, unsafe))
            check(context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE).all.isEmpty()) { "Session movement/closure started saving positions again" }
            check(runCatching { pending.send() }.exceptionOrNull() is android.app.PendingIntent.CanceledException)
            File(context.cacheDir, "window-launch-results.json").writeText(JSONObject()
                .put("legacyPositionsRemoved", context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE).all.isEmpty()).put("clickPose", actual.encode())
                .put("shadowWidth", shadowSize.x).put("shadowHeight", shadowSize.y)
                .put("sessionWidth", sessionWidth).put("sessionHeight", sessionHeight)
                .put("nativeDragStarted", dragStarted).put("syntheticDropRequested", nativePose.encode())
                .put("placementAvailable", placementAvailable).put("composeDragGesture", placementAvailable)
                .put("launcherReplaced", !placementAvailable).put("launcherPose", launcherPose.encode())
                .put("syntheticDropActual", dropActual.encode()).put("homeHonoredDropOptions", near(dropActual, nativePose))
                .put("slotReused", dropped.sessionSlot == firstSlot).toString(2))
            test.runOnMainSync { resumedLauncher.finish() }
        } finally {
            launcher.application.unregisterActivityLifecycleCallbacks(callbacks)
            test.runOnMainSync { if (!launcher.isDestroyed) launcher.finish() }
        }
    }

    /** Ordinary-app capability check, with no adopted shell permission identity. */
    private fun manipulation() {
        val profile = ConnectionProfile("verification-manipulation", "Manipulation verification",
            ConnectionType.RDP, "127.0.0.1", 1, "", "")
        ProfileManager(context).addProfile(profile)
        val window = launch(profile.id)
        Thread.sleep(1500)
        val before = readPose(window)
        val requested = before.copy(x = before.x + 0.1f)
        val report = JSONObject().put("before", before.encode()).put("requested", requested.encode())
        val apiClasses = listOf("horizonos.view.WindowExt", "horizonos.view.ViewExt",
            "horizonos.app.ActivityExt", "horizonos.app.volumetricwindow.VolumetricWindowManager")
        File(context.cacheDir, "window-manipulation-api.txt").writeText(apiClasses.joinToString("\n\n") { name ->
            name + "\n" + Class.forName(name).methods.filter { it.declaringClass.name == name }
                .sortedBy { it.name }.joinToString("\n") { it.toGenericString() }
        })
        try {
            test.runOnMainSync {
                val managerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
                val manager = window.getSystemService(managerClass)
                val tokenClass = Class.forName("horizonos.view.VolumetricWindowToken")
                val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", Activity::class.java)
                    .invoke(null, window)
                val value = requested.platformPose("horizonos")
                val builderClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
                val builder = builderClass.getConstructor().newInstance()
                builderClass.getMethod("setPose", tokenClass, value.javaClass).invoke(builder, token, value)
                val transaction = builderClass.getMethod("build").invoke(builder)
                managerClass.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
            }
            report.put("requestReturned", true)
        } catch (error: Exception) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            report.put("requestError", cause.toString())
        }
        Thread.sleep(1000)
        val after = readPose(window)
        report.put("after", after.encode()).put("poseApplied", near(after, requested))
            .put("shellPermissionAdopted", false)
        File(context.cacheDir, "window-manipulation-results.json").writeText(report.toString(2))
        Log.i("LifecycleTest", "Ordinary-app manipulation capability: $report")
        close(window)
    }

    private fun placement(original: ConnectionProfile) {
        val profile = original.copy(id = "verification-placement", name = "Placement verification",
            host = "127.0.0.1", port = 1, user = "", pass = "", useCloudflare = false, redirectMicrophone = false, enableCamera = false)
        ProfileManager(context).addProfile(profile)
        val legacy = context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
        check(legacy.edit().putString("${profile.id}/0", "[50,-20,50,0,1,0,0]").commit())
        val first = launch(profile.id)
        Thread.sleep(1500)
        val second = launch(profile.id)
        Thread.sleep(1500)
        check(first.sessionSlot == 0 && second.sessionSlot == 1) { "Session slots collided: ${first.sessionSlot}/${second.sessionSlot}" }
        test.uiAutomation.adoptShellPermissionIdentity("horizonos.permission.MANAGE_VOLUMETRIC_WINDOWS")
        val base = readPose(first)
        val left = base.copy(x = base.x - 0.15f)
        val right = base.copy(x = base.x + 0.15f)
        move(first, left)
        move(second, right)
        check(near(readPose(first), left) && near(readPose(second), right))
        test.uiAutomation.dropShellPermissionIdentity()
        check(context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE).all.isEmpty()) { "Moving windows persisted positions" }
        close(first)
        val reopenedFirst = launch(profile.id)
        Thread.sleep(1800)
        check(reopenedFirst.sessionSlot == 0) { "Closed slot was not reused" }
        val actualFirst = readPose(reopenedFirst)
        check(context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE).all.isEmpty())
        close(second)
        val reopenedSecond = launch(profile.id)
        Thread.sleep(1800)
        check(reopenedSecond.sessionSlot == 1)
        val actualSecond = readPose(reopenedSecond)
        check(!near(actualFirst, left) && !near(actualSecond, right)) { "Unverified saved coordinates were restored" }
        Log.i("LifecycleTest", "PASS: independent session slots, no position persistence, fresh placement on reopen: first=$actualFirst; second=$actualSecond")
        File(context.cacheDir, "window-placement-results.json").writeText(JSONObject()
            .put("slot0Restored", near(actualFirst, left)).put("slot1Restored", near(actualSecond, right))
            .put("expected0", left.encode()).put("actual0", actualFirst.encode())
            .put("expected1", right.encode()).put("actual1", actualSecond.encode()).toString(2))
        check(context.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE).all.isEmpty()) { "Reopening windows persisted positions" }

    }
}
