package com.multidesk.explorer

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.multidesk.explorer.data.ConnectionType
import com.multidesk.explorer.data.ProfileManager
import java.io.File

/** On-device regression check: saved RDP profile, actual task resizes, full-frame captures. */
class RdpResizeInstrumentation : Instrumentation() {
    private var lifecycleMode: String? = null
    private var certificateMode: String? = null
    private var audioProbe: String? = null
    private var audioMode: String? = null
    private var inputOnly = false
    private var toolbarOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        lifecycleMode = arguments?.getString("lifecycleMode")
        certificateMode = arguments?.getString("certificateMode")
        audioProbe = arguments?.getString("audioProbe")
        audioMode = arguments?.getString("audioMode")
        inputOnly = arguments?.getString("inputOnly") == "true"
        toolbarOnly = arguments?.getString("toolbarOnly") == "true"
        start()
    }

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    override fun onStart() {
        val results = Bundle()
        try {
            lifecycleMode?.let {
                SessionLifecycleVerification(this).run(it)
                results.putString("stream", "PASS: session lifecycle $it.\n")
                finish(0, results)
                return
            }
            certificateMode?.let {
                RdpCertificateVerification(this).run(it)
                results.putString("stream", "PASS: RDP certificates $it.\n")
                finish(0, results)
                return
            }
            audioMode?.let {
                RdpAudioVerification(this).run(it, audioProbe)
                results.putString("stream", "PASS: RDP audio $it.\n")
                finish(0, results)
                return
            }
            verifyPointerGestures()
            verifyDoubleTapTolerance()
            verifyControllerScroll()
            if (inputOnly) {
                results.putString("stream", "PASS: pointer gestures, double-tap position tolerance and four-direction controller wheel/deadzone/repeat/stop.\n")
                finish(0, results)
                return
            }
            val profile = ProfileManager(targetContext).getProfiles().first { it.type == ConnectionType.RDP }
            val activity = startActivitySync(Intent(targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", profile.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Thread.sleep(6000)
            if (toolbarOnly) {
                verifyToolbar(activity)
                runOnMainSync { activity.finish() }
                results.putString("stream", "PASS: toolbar offset, full fade, real panel pose change, pin, expanded keyboard/modifier/numeric controls, keyboard focus through RDP tap/hold/drag, dismissal/reopening.\n")
                finish(0, results)
                return
            }
            val report = StringBuilder()
            for ((width, height) in listOf(1280 to 800, 1000 to 600, 1600 to 900, 800 to 1000, 1280 to 800)) {
                uiAutomation.executeShellCommand("am task resize ${activity.taskId} 0 0 $width $height").close()
                Thread.sleep(3000)
                // Flush a pending TextureView layer update before copying its GPU image.
                runOnMainSync {
                    texture(activity.window.decorView)?.let {
                        it.bitmap?.recycle()
                        it.invalidate()
                    }
                }
                Thread.sleep(750)
                var image: Bitmap? = null
                var failure: Throwable? = null
                runOnMainSync {
                    try {
                        val content = activity.findViewById<View>(android.R.id.content)
                        val view = checkNotNull(texture(content)) { "Missing TextureView" }
                        val contentPosition = IntArray(2).also { content.getLocationInWindow(it) }
                        val texturePosition = IntArray(2).also { view.getLocationInWindow(it) }
                        check(contentPosition.contentEquals(texturePosition)) { "Texture offset inside content" }
                        check(view.width == content.width && view.height == content.height) {
                            "Texture ${view.width}x${view.height} does not fill content ${content.width}x${content.height}"
                        }
                        check(view.width == width && view.height == height) {
                            "Task resize not applied: ${view.width}x${view.height}, expected ${width}x${height}"
                        }
                        image = checkNotNull(view.bitmap) { "Missing rendered texture" }
                        report.append("${view.width}x${view.height}: fills content; ")
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
                failure?.let { throw it }
                var bitmap = checkNotNull(image)
                fun hasContent(candidate: Bitmap): Boolean {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until candidate.height step 17)
                        for (x in 0 until candidate.width step 17) colors.add(candidate.getPixel(x, y))
                    return colors.size > 16
                }
                // A server can take several seconds to repaint after a resolution change.
                for (attempt in 0 until 20) {
                    if (hasContent(bitmap)) break
                    Thread.sleep(500)
                    bitmap.recycle()
                    runOnMainSync { image = texture(activity.window.decorView)?.bitmap }
                    bitmap = checkNotNull(image)
                }
                File(targetContext.cacheDir, "rdp-verification-${width}x${height}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                check(hasContent(bitmap)) { "Blank/flat frame at ${width}x${height}" }
                bitmap.recycle()
            }
            runOnMainSync { activity.finish() }
            Thread.sleep(1500) // Exercise native worker shutdown after an active GFX session.
            results.putString("stream", "PASS: $report\nPNG captures in target app cache; session closed.\n")
            finish(0, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(1, results)
        }
    }
    private fun verifyToolbar(activity: android.app.Activity) {
        val serviceInfo = uiAutomation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        uiAutomation.serviceInfo = serviceInfo
        fun find(label: String): android.view.accessibility.AccessibilityNodeInfo? {
            uiAutomation.clearCache()
            fun search(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    search(child)?.let { return it }
                }
                return null
            }
            val displays = uiAutomation.windowsOnAllDisplays
            for (index in 0 until displays.size()) {
                for (window in displays.valueAt(index)) window.root?.let { if (it.packageName == targetContext.packageName) search(it)?.let { found -> return found } }
            }
            return null
        }
        fun click(label: String) {
            var node = checkNotNull(find(label)) { "Missing control: $label" }
            while (!node.isClickable && node.parent != null) node = node.parent
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
            Thread.sleep(500)
        }
        check(find("Keep toolbar visible") == null) { "Toolbar did not completely fade" }
        check(find("Show session toolbar") == null) { "Minimized menu still exists" }

        // Change only the app's own panel pose, then restore it. This exercises the real
        // Horizon listener without resizing, changing focus, or synthesizing a pointer move.
        uiAutomation.adoptShellPermissionIdentity("horizonos.permission.MANAGE_VOLUMETRIC_WINDOWS")
        val managerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
        val manager = activity.getSystemService(managerClass)
        val tokenClass = Class.forName("horizonos.view.VolumetricWindowToken")
        val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", android.app.Activity::class.java).invoke(null, activity)
        val info = managerClass.getMethod("getWindowInfo", tokenClass).invoke(manager, token)
        val pose = info.javaClass.getMethod("getPose").invoke(info)
        val poseClass = pose.javaClass
        val position = poseClass.getMethod("getPosition").invoke(pose)
        val orientation = poseClass.getMethod("getOrientation").invoke(pose)
        val vectorClass = position.javaClass
        fun component(name: String) = vectorClass.getMethod(name).invoke(position) as Float
        val shiftedPosition = vectorClass.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(component("getX") + 0.05f, component("getY"), component("getZ"))
        val shiftedPose = poseClass.getConstructor(orientation.javaClass, vectorClass).newInstance(orientation, shiftedPosition)
        fun applyPose(value: Any) {
            val builderClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            builderClass.getMethod("setPose", tokenClass, poseClass).invoke(builder, token, value)
            val transaction = builderClass.getMethod("build").invoke(builder)
            managerClass.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
        }
        try {
            applyPose(shiftedPose)
            Thread.sleep(800)
            val pin = checkNotNull(find("Keep toolbar visible")) { "3D movement did not reveal toolbar" }
            val rect = android.graphics.Rect().also { pin.getBoundsInScreen(it) }
            check(rect.top > 0) { "Toolbar is flush with top" }
            click("Keep toolbar visible")
            check(find("Unpin toolbar") != null) { "Pin action did not toggle toolbar" }
            Thread.sleep(4500)
            check(find("Unpin toolbar") != null) { "Pinned toolbar faded (display ${activity.display?.displayId}; pin=${find("Keep toolbar visible")})" }
            click("Keyboard")
            Thread.sleep(1500)
            val simulatedIme = find("ESC") == null
            fun imeVisibility(visible: Boolean) {
                runOnMainSync {
                    val decor = activity.window.decorView
                    decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                        .setVisible(android.view.WindowInsets.Type.ime(), visible).build())
                }
                Thread.sleep(500)
            }
            if (simulatedIme) {
                // Horizon can reject automated keyboard show while the headset is unworn.
                // Dispatch real Android inset objects to exercise the Compose visibility path.
                android.util.Log.i("ToolbarTest", "Horizon rejected automated IME show; testing inset transitions")
                imeVisibility(true)
            }
            check(find("ESC") != null) { "Keyboard modifier controls missing" }
            for (label in listOf("WIN", "SHIFT", "CTRL", "ALT", "AltGr")) {
                fun state(): String? {
                    var node = checkNotNull(find(label))
                    while (node.stateDescription == null && node.parent != null) node = node.parent
                    return node.stateDescription?.toString()
                }
                check(state() == "Released") { "$label starts held" }
                click(label)
                check(state() == "Held") { "$label did not latch" }
                click(label)
                check(state() == "Released") { "$label did not release" }
            }
            for (label in listOf("DEL", "TAB")) click(label)
            check(find("Up") == null && find("F1") == null) { "Expanded keys visible in first level" }
            click("CTRL")
            click("123")
            check(find("ABC") != null && find("123") == null) { "Expanded close label missing" }
            var heldCtrl = checkNotNull(find("CTRL"))
            while (heldCtrl.stateDescription == null && heldCtrl.parent != null) heldCtrl = heldCtrl.parent
            check(heldCtrl.stateDescription?.toString() == "Held") { "Expansion released CTRL" }
            fun captureKeyboard(name: String) {
                val image = Bitmap.createBitmap(activity.window.decorView.width,
                    activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                val done = java.util.concurrent.CountDownLatch(1)
                var result = -1
                runOnMainSync {
                    android.view.PixelCopy.request(activity.window, image, { result = it; done.countDown() },
                        android.os.Handler(android.os.Looper.getMainLooper()))
                }
                check(done.await(5, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS) { "Keyboard capture failed: $result" }
                File(targetContext.cacheDir, "keyboard-$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            captureKeyboard("expanded")
            click("CTRL")
            for (n in 1..12) click("F$n")
            for (label in listOf("Home", "Up", "PgUp", "Left", "Right", "End", "Down", "PgDn",
                "Insert", "Delete", "Enter", "Backspace", "Keypad Enter", "/", "*", "-", "+", "Print Screen")) click(label)
            click("Right click")
            click("ESC") // Close any resulting remote context menu.
            click("Number Keys")
            check(find("F1") == null && find("Function Keys") != null) { "Function bank did not switch to numbers" }
            for (label in listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "0", ".")) click(label)
            captureKeyboard("numbers")
            click("Function Keys")
            check(find("F1") != null && find("1") == null) { "Function bank did not restore" }
            click("ABC")
            check(find("123") != null && find("F1") == null && find("Up") == null) { "ABC did not close extras" }
            click("123")
            check(find("F12") != null) { "Extras could not reopen" }
            click("ABC")
            captureKeyboard("collapsed")
            click("CTRL") // Dismissal must release a held modifier.

            fun keyboardRetainsFocus() {
                check(find("Remote keyboard input") != null) { "Missing keyboard input" }
                // Horizon's headless accessibility window does not expose IME input focus.
                // Observe Compose's actual onFocusChanged callback, scoped to this process.
                val focusLog = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    uiAutomation.executeShellCommand("logcat -d --pid=${android.os.Process.myPid()} -s RdpKeyboard")
                ).bufferedReader().use { reader -> reader.readLines().lastOrNull { it.contains("editor focused=") } }
                check(focusLog?.contains("editor focused=true hasFocus=true") == true) {
                    "Remote touch stole focus from keyboard input: $focusLog"
                }
                check(find("ESC") != null) { "Remote touch dismissed keyboard modifier controls" }
            }
            keyboardRetainsFocus()
            fun remoteTouch(action: Int, downTime: Long, eventTime: Long, dx: Float = 0f) {
                runOnMainSync {
                    val view = checkNotNull(texture(activity.window.decorView))
                    val event = android.view.MotionEvent.obtain(downTime, eventTime, action,
                        view.width * 0.5f + dx, view.height * 0.65f, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(view.dispatchTouchEvent(event)) { "Remote touch not handled" } }
                    finally { event.recycle() }
                }
                Thread.sleep(200)
                keyboardRetainsFocus()
            }
            for ((duration, movement) in listOf(80L to 0f, 1200L to 0f, 150L to 60f)) {
                val start = android.os.SystemClock.uptimeMillis()
                remoteTouch(android.view.MotionEvent.ACTION_DOWN, start, start)
                if (movement != 0f) remoteTouch(android.view.MotionEvent.ACTION_MOVE, start, start + duration / 2, movement)
                remoteTouch(android.view.MotionEvent.ACTION_UP, start, start + duration, movement)
            }
            android.util.Log.i("ToolbarTest", "PASS: keyboard input focus survived RDP tap, hold, and drag; simulatedIme=$simulatedIme")

            if (simulatedIme) imeVisibility(false)
            else {
                runOnMainSync {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                Thread.sleep(1000)
            }
            check(find("ESC") == null) { "Keyboard modifier controls survived dismissal" }
            click("Keyboard")
            if (simulatedIme) imeVisibility(true) else Thread.sleep(1500)
            check(find("ESC") != null) { "Keyboard could not be reopened after dismissal" }
            var ctrl = checkNotNull(find("CTRL"))
            while (ctrl.stateDescription == null && ctrl.parent != null) ctrl = ctrl.parent
            check(ctrl.stateDescription?.toString() == "Released") { "CTRL remained held after dismissal" }
            if (simulatedIme) imeVisibility(false)
            else runOnMainSync {
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            Thread.sleep(500)
            click("Unpin toolbar")
            Thread.sleep(4500)
            check(find("Keep toolbar visible") == null && find("Show session toolbar") == null)
        } finally {
            applyPose(pose)
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun verifyControllerScroll() {
        val sent = mutableListOf<Int>()
        lateinit var scroll: RdpControllerScroll
        fun motion(x: Float, y: Float, source: Int = android.view.InputDevice.SOURCE_JOYSTICK) {
            val coords = android.view.MotionEvent.PointerCoords().apply {
                setAxisValue(android.view.MotionEvent.AXIS_X, x)
                setAxisValue(android.view.MotionEvent.AXIS_Y, y)
            }
            val event = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_MOVE, 1,
                arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
            try { check(scroll.onMotion(event) == (source == android.view.InputDevice.SOURCE_JOYSTICK)) }
            finally { event.recycle() }
        }
        runOnMainSync {
            scroll = RdpControllerScroll { sent.add(it) }
            motion(0.1f, -0.1f)
            check(sent.isEmpty())
            for ((x, y, expected) in listOf(Triple(0f, -1f, 0x278), Triple(0f, 1f, 0x388),
                Triple(-1f, 0f, 0x588), Triple(1f, 0f, 0x478))) {
                motion(x, y)
                check(sent.last() == expected) { "Incorrect wheel direction: ${sent.last()}" }
                motion(0f, 0f)
            }
            motion(1f, -1f)
        }
        Thread.sleep(250)
        var stoppedCount = 0
        runOnMainSync {
            check(sent.size >= 10) { "Held stick did not repeat both axes" }
            scroll.stop()
            stoppedCount = sent.size
            motion(1f, 1f, android.view.InputDevice.SOURCE_TOUCHSCREEN)
        }
        Thread.sleep(200)
        runOnMainSync { check(sent.size == stoppedCount) { "Scroll continued after stop" } }
    }

    private fun verifyDoubleTapTolerance() {
        val sent = mutableListOf<Triple<Float, Float, Int>>()
        val gesture = RdpPointerGesture(8f, { x, y, flags -> sent.add(Triple(x, y, flags)) }, { _, _ -> })
        fun event(action: Int, time: Long, x: Float, y: Float = 30f) {
            val motion = android.view.MotionEvent.obtain(0, time, action, x, y, 0)
            try { gesture.onTouch(motion) } finally { motion.recycle() }
        }
        fun pair(x: Float, y: Float, delay: Long, expectedX: Float, expectedY: Float) {
            gesture.cancel()
            sent.clear()
            event(android.view.MotionEvent.ACTION_DOWN, 1000, 30f)
            event(android.view.MotionEvent.ACTION_UP, 1050, 30f)
            event(android.view.MotionEvent.ACTION_DOWN, 1050 + delay, x, y)
            event(android.view.MotionEvent.ACTION_UP, 1100 + delay, x, y)
            check(sent.size == 4 && sent[2].first == expectedX && sent[2].second == expectedY) {
                "Incorrect double-tap snapping: $sent"
            }
        }
        pair(42f, 38f, 100, 30f, 30f) // Diagonal jitter beyond ordinary drag slop, within double-tap radius.
        pair(46f, 30f, 100, 30f, 30f) // Inclusive radius boundary.
        pair(47f, 30f, 100, 47f, 30f)
        pair(42f, 38f, android.view.ViewConfiguration.getDoubleTapTimeout().toLong(), 42f, 38f)
        gesture.cancel()
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 2000, 30f)
        event(android.view.MotionEvent.ACTION_UP, 2050, 30f)
        gesture.cancel()
        event(android.view.MotionEvent.ACTION_DOWN, 2100, 42f)
        event(android.view.MotionEvent.ACTION_UP, 2150, 42f)
        check(sent[2].first == 42f) { "Cancellation retained a double-tap candidate" }
    }

    private fun verifyPointerGestures() {
        for ((elapsed, expected) in listOf(0L to 0f, 499L to 0f, 500L to 0f,
            750L to 0.25f, 1000L to 0.5f, 1500L to 1f, 2000L to 1f)) {
            check(rightClickBoxProgress(elapsed) == expected) { "Wrong right-click box progress at $elapsed" }
        }
        val sent = mutableListOf<Int>()
        var indicator = false
        val gesture = RdpPointerGesture(8f, { _, _, flags -> sent.add(flags) }, { point, _ -> indicator = point != null })
        fun event(action: Int, elapsed: Long, x: Float = 30f) {
            val motion = android.view.MotionEvent.obtain(100L, 100L + elapsed, action, x, 30f, 0)
            gesture.onTouch(motion)
            motion.recycle()
        }
        val left = FreeRDPBridge.PTR_FLAGS_BUTTON1
        val right = FreeRDPBridge.PTR_FLAGS_BUTTON2
        val down = FreeRDPBridge.PTR_FLAGS_DOWN
        for (duration in listOf(100L, 999L, 1000L, 1500L)) {
            sent.clear()
            event(android.view.MotionEvent.ACTION_DOWN, 0)
            check(sent.isEmpty() && indicator) { "Hold must not send left down" }
            event(android.view.MotionEvent.ACTION_UP, duration)
            val button = if (duration >= 1000L) right else left
            check(sent == listOf(button or down, button) && !indicator) { "Wrong click at $duration: $sent" }
        }
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_MOVE, 100, 50f)
        event(android.view.MotionEvent.ACTION_UP, 1500, 60f)
        check(sent == listOf(left or down, FreeRDPBridge.PTR_FLAGS_MOVE, left) && !indicator)
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_CANCEL, 1500)
        check(sent.isEmpty() && !indicator)
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_MOVE, 100, 50f)
        event(android.view.MotionEvent.ACTION_CANCEL, 200)
        check(sent.last() == left && !indicator) { "Cancel left a pressed button" }
        sent.clear()
        event(android.view.MotionEvent.ACTION_DOWN, 0)
        event(android.view.MotionEvent.ACTION_POINTER_DOWN, 100)
        event(android.view.MotionEvent.ACTION_UP, 1500)
        check(sent.isEmpty() && !indicator)
        val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply {
            id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
        })
        val coordinates = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 30f; y = 30f })
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val motion = android.view.MotionEvent.obtain(100L, 200L, action, 1, properties, coordinates,
                0, if (action == android.view.MotionEvent.ACTION_DOWN) android.view.MotionEvent.BUTTON_SECONDARY else 0,
                1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
            gesture.onTouch(motion)
            motion.recycle()
        }
        check(sent == listOf(right or down, right)) { "Secondary button release lost its identity" }
    }

}
