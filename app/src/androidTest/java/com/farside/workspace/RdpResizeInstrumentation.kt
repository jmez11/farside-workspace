package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File

/** On-device regression check: saved RDP profile, actual task resizes, full-frame captures. */
class RdpResizeInstrumentation : Instrumentation() {
    private var lifecycleMode: String? = null
    private var cloudflaredOnly = false
    private var cloudflaredHost: String? = null
    private var cloudflaredCredential: String? = null
    private var certificateMode: String? = null
    private var audioProbe: String? = null
    private var audioMode: String? = null
    private var inputOnly = false
    private var credentialsOnly = false
    private var proxiesOnly = false
    private var toolbarOnly = false
    private var zoomTest = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cloudflaredOnly = arguments?.getString("cloudflaredOnly") == "true"
        cloudflaredHost = arguments?.getString("cloudflaredHost")
        cloudflaredCredential = arguments?.getString("cloudflaredCredential")
        lifecycleMode = arguments?.getString("lifecycleMode")
        certificateMode = arguments?.getString("certificateMode")
        audioProbe = arguments?.getString("audioProbe")
        audioMode = arguments?.getString("audioMode")
        inputOnly = arguments?.getString("inputOnly") == "true"
        credentialsOnly = arguments?.getString("credentialsOnly") == "true"
        proxiesOnly = arguments?.getString("proxiesOnly") == "true"
        toolbarOnly = arguments?.getString("toolbarOnly") == "true"
        zoomTest = arguments?.getString("zoomTest") == "true"
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
            if (zoomTest) {
                val report = verifyZoomTouchCoordinates()
                results.putString("stream", "PASS:\n$report\n")
                finish(0, results)
                return
            }
            if (proxiesOnly) {
                ProxyVerification(this).run()
                results.putString("stream", "PASS: four proxy choices, selection/legacy handling and unsupported proxy rejection without direct TCP fallback.\n")
                finish(0, results)
                return
            }
            if (credentialsOnly) {
                CredentialUiVerification(this).run()
                results.putString("stream", "PASS: optional credential PIN setup/change/removal, protected edit/delete and session startup, wrong/cancel/correct PIN, persistent backoff, password encryption/IME and stable links.\n")
                finish(0, results)
                return
            }
            cloudflaredHost?.let {
                CloudflaredVerification(this).runLive(it, cloudflaredCredential)
                results.putString("stream", "PASS: live Cloudflared RDP and cached reconnect.\n")
                finish(0, results)
                return
            }
            if (cloudflaredOnly) {
                CloudflaredVerification(this).run()
                results.putString("stream", "PASS: Cloudflared security and packaged process/authentication fixtures.\n")
                finish(0, results)
                return
            }
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
            verifyZoomedPointerMapping()
            if (inputOnly) {
                results.putString("stream", "PASS: pointer gestures, double-tap position tolerance, four-direction controller wheel/deadzone/repeat/stop, and zoomed pointer coordinate mapping.\n")
                finish(0, results)
                return
            }
            val profile = ProfileManager(targetContext).getProfiles().first { it.type == ConnectionType.RDP }
            // A test-only window session can emulate Home's unhosted embedded-window focus grants.
            // Drop identity before any keyboard UI/input code runs. No app permission is added.
            if (toolbarOnly) uiAutomation.adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW")
            val activity = try { startActivitySync(Intent(targetContext, SessionActivity::class.java).apply {
                putExtra("profileId", profile.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) } finally { if (toolbarOnly) uiAutomation.dropShellPermissionIdentity() }
            Thread.sleep(6000)
            if (toolbarOnly) {
                verifyToolbar(activity)
                runOnMainSync { activity.finish() }
                results.putString("stream", "PASS: toolbar offset, full fade, real panel pose change, pin, expanded keyboard/modifier/numeric controls, unchanged desktop/pose, real key-window focus handoff and key touch events, keyboard focus through RDP tap/hold/drag, dismissal/reopening.\n")
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
        val fixtureHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var fixtureVisible = false
        var fixturePump: Runnable? = null
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
            applyPose(pose)
            Thread.sleep(500)
            uiAutomation.dropShellPermissionIdentity() // All keyboard placement/resize checks use app permissions.
            val desktop = checkNotNull(texture(activity.window.decorView))
            val desktopWidth = desktop.width
            val desktopHeight = desktop.height
            val desktopOrigin = IntArray(2).also { desktop.getLocationInWindow(it) }.toList()
            val sessionActivity = activity as SessionActivity
            val keyboardState = checkNotNull(sessionActivity.keyboardPanelState)
            val desktopBridge = FreeRDPBridge()
            check(sessionActivity.rdpInstance != 0L) { "No live desktop for keyboard geometry verification" }
            val remoteWidth = desktopBridge.getDesktopWidth(sessionActivity.rdpInstance)
            val remoteHeight = desktopBridge.getDesktopHeight(sessionActivity.rdpInstance)
            fun parentPose(): SavedWindowPose {
                val current = managerClass.getMethod("getWindowInfo", tokenClass).invoke(manager, token)
                return SavedWindowPose.read(current.javaClass.getMethod("getPose").invoke(current))
            }
            val desktopPose = parentPose()
            fun panelView(): View = checkNotNull(keyboardState.javaClass.methods.single {
                it.name.startsWith("getHostedView") }.invoke(keyboardState) as? View)
            fun verifyKeyboardGeometry(): Int {
                check(keyboardState.lifecycle.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) { "Key window not placed: ${keyboardState.lifecycle.value}" }
                check(desktop.width == desktopWidth && desktop.height == desktopHeight) { "Keyboard changed desktop texture bounds" }
                check(IntArray(2).also { desktop.getLocationInWindow(it) }.toList() == desktopOrigin) { "Keyboard shifted the desktop content" }
                check(desktopBridge.getDesktopWidth(sessionActivity.rdpInstance) == remoteWidth &&
                    desktopBridge.getDesktopHeight(sessionActivity.rdpInstance) == remoteHeight) { "Keyboard changed the server desktop resolution" }
                check(parentPose() == desktopPose) { "Keyboard moved the desktop panel" }
                val panel = panelView()
                check(panel.width == desktopWidth) { "Key window width differs from desktop" }
                android.util.Log.i("KeyboardPanelTest", "fixed desktop=${desktop.width}x${desktop.height}, keys=${panel.width}x${panel.height}, pose=$desktopPose")
                return panel.height
            }
            click("Keyboard")
            val simulatedIme = !activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            fun imeVisibility(visible: Boolean) {
                fixtureVisible = visible
                runOnMainSync {
                    val decor = activity.window.decorView
                    decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                        .setVisible(android.view.WindowInsets.Type.ime(), visible).build())
                }
                Thread.sleep(if (visible) 500 else 1000)
            }
            if (simulatedIme) {
                // Horizon can reject automated keyboard show while the headset is unworn.
                // Dispatch real Android inset objects to exercise the Compose visibility path.
                android.util.Log.i("ToolbarTest", "Horizon rejected automated IME show; testing inset transitions")
                imeVisibility(true)
                fixturePump = object : Runnable {
                    override fun run() {
                        if (fixtureVisible && !activity.isDestroyed) {
                            val decor = activity.window.decorView
                            decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                                .setVisible(android.view.WindowInsets.Type.ime(), true).build())
                        }
                        fixtureHandler.postDelayed(this, 100)
                    }
                }
                fixtureHandler.post(checkNotNull(fixturePump))
            } else Thread.sleep(1000)
            check(find("ESC") != null) { "Keyboard modifier controls missing" }
            fun keyboardRetainsFocus() {
                check(find("Remote keyboard input") != null) { "Missing keyboard input" }
                // Observe the current Compose focus callback, rather than a stale/evicted log line.
                check(activity.keyboardEditorHasFocus || activity.keyboardPanelEditorHasFocus) {
                    "Pointer touch stole focus from keyboard input"
                }
                check(find("ESC") != null) { "Remote touch dismissed keyboard modifier controls" }
            }
            fun nativeFocus(panel: Boolean): Boolean {
                try {
                    val host = keyboardState.javaClass.methods.find { it.name.startsWith("getSurfaceControlViewHost") }
                        ?.invoke(keyboardState) as? android.view.SurfaceControlViewHost ?: return false
                    val target = try {
                        host.javaClass.getMethod("getFocusGrantToken").invoke(host)
                    } catch (e: Throwable) {
                        null
                    } ?: return false
                    val nativeSession = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowSession").invoke(null)
                    val windowClass = Class.forName("android.view.IWindow")
                    uiAutomation.adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW")
                    try {
                        nativeSession.javaClass.getMethod("grantEmbeddedWindowFocus", windowClass, android.os.IBinder::class.java,
                            Boolean::class.javaPrimitiveType).invoke(nativeSession, null, target, panel)
                    } finally { uiAutomation.dropShellPermissionIdentity() }
                    Thread.sleep(100)
                    val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("dumpsys input"))
                        .bufferedReader().use { it.readText() }
                    File(targetContext.cacheDir, "keyboard-native-focus.txt").writeText(input)
                    val focused = input.substringAfter("FocusedWindows:").substringBefore("FocusRequests:")
                    check(focused.contains("Embedded{SurfaceControlViewHost}") == panel) { "Native focus handoff not exercised: $focused" }
                    return true
                } catch (e: NoSuchMethodException) {
                    android.util.Log.i("KeyboardTest", "grantEmbeddedWindowFocus not supported on this OS: ${e.message}")
                    return false
                }
            }
            fun touchKey(label: String, nativeFocus: Boolean = false) {
                val actions = checkNotNull(sessionActivity.keyboardPanelEditorActions)
                val actionCounts = listOf(actions.focusRequests, actions.focusResets, actions.restarts, actions.showRequests)
                val exercisedNativeFocus = if (nativeFocus) nativeFocus(true) else false
                val node = checkNotNull(find(label)) { "Missing touch key: $label" }
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                runOnMainSync {
                    val panel = panelView()
                    val origin = IntArray(2).also { panel.getLocationOnScreen(it) }
                    val started = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(started, started + if (action == android.view.MotionEvent.ACTION_UP) 50 else 0,
                            action, bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1], 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { check(panel.dispatchTouchEvent(event)) { "$label touch was not handled" } }
                        finally { event.recycle() }
                    }
                }
                // Unworn Horizon rejects automatic IME show. Keep only its visibility synthetic;
                // the native child activation and parent-focus restoration above remain real.
                if (simulatedIme && nativeFocus) imeVisibility(true) else Thread.sleep(500)
                if (exercisedNativeFocus) {
                    val editor = checkNotNull(sessionActivity.keyboardPanelEditorView)
                    check(sessionActivity.keyboardPanelEditorHasFocus) { "Key window did not focus its IME editor" }
                    val input = editor.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    check(input.isActive(editor)) { "Key window does not own Android's active InputConnection" }
                    check(editor.onCheckIsTextEditor() && input.isAcceptingText) { "Key window has no accepting text InputConnection" }
                    check(listOf(actions.focusRequests, actions.focusResets, actions.restarts, actions.showRequests) == actionCounts) {
                        "Prepared key editor restarted focus/IME during native handoff"
                    }
                }
                keyboardRetainsFocus()
                verifyKeyboardGeometry()
            }
            val collapsedHeight = verifyKeyboardGeometry()
            for (label in listOf("WIN", "SHIFT", "CTRL", "ALT", "AltGr")) {
                fun state(): String? {
                    var node = checkNotNull(find(label))
                    while (node.stateDescription == null && node.parent != null) node = node.parent
                    return node.stateDescription?.toString()
                }
                check(state() == "Released") { "$label starts held" }
                touchKey(label)
                check(state() == "Held") { "$label did not latch" }
                touchKey(label)
                check(state() == "Released") { "$label did not release" }
            }
            for (label in listOf("DEL", "TAB")) touchKey(label)
            check(find("Up") == null && find("F1") == null) { "Expanded keys visible in first level" }
            touchKey("CTRL", nativeFocus = true)
            touchKey("123", nativeFocus = true)
            check(find("ABC") != null && find("123") == null) { "Expanded close label missing" }
            val expandedHeight = verifyKeyboardGeometry()
            check(expandedHeight > collapsedHeight * 3) { "123 did not extend the bottom panel" }
            var heldCtrl = checkNotNull(find("CTRL"))
            while (heldCtrl.stateDescription == null && heldCtrl.parent != null) heldCtrl = heldCtrl.parent
            check(heldCtrl.stateDescription?.toString() == "Held") { "Expansion released CTRL" }
            fun captureKeyboard(name: String) {
                val panel = panelView()
                val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                runOnMainSync { panel.draw(android.graphics.Canvas(image)) }
                File(targetContext.cacheDir, "keyboard-$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            captureKeyboard("expanded")
            click("CTRL")
            for (n in 1..12) if (n == 1) touchKey("F$n") else click("F$n")
            for (label in listOf("Home", "Up", "PgUp", "Left", "Right", "End", "Down", "PgDn",
                "Insert", "Delete", "Enter", "Backspace", "Keypad Enter", "/", "*", "-", "+", "Print Screen")) click(label)
            click("Right click")
            click("ESC") // Close any resulting remote context menu.
            touchKey("Number Keys")
            check(find("F1") == null && find("Function Keys") != null) { "Function bank did not switch to numbers" }
            for (label in listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "0", ".")) click(label)
            captureKeyboard("numbers")
            click("Function Keys")
            check(find("F1") != null && find("1") == null) { "Function bank did not restore" }
            val committedBeforeCollapse = KeyboardPanelResize.committedCompactions
            touchKey("ABC", nativeFocus = true)
            check(find("123") != null && find("F1") == null && find("Up") == null) { "ABC did not close extras" }
            check(verifyKeyboardGeometry() == collapsedHeight) { "ABC did not restore collapsed panel height" }
            check(KeyboardPanelResize.committedCompactions > committedBeforeCollapse &&
                KeyboardPanelResize.committedHeight == collapsedHeight) { "Compact key buffer was not committed before reveal" }
            click("123")
            check(find("F12") != null) { "Extras could not reopen" }
            click("ABC")
            captureKeyboard("collapsed")
            click("CTRL") // Dismissal must release a held modifier.

            keyboardRetainsFocus()
            fun remoteTouch(action: Int, downTime: Long, eventTime: Long, dx: Float = 0f) {
                val actions = checkNotNull(sessionActivity.keyboardEditorActions)
                val actionCounts = listOf(actions.focusRequests, actions.focusResets, actions.restarts, actions.showRequests)
                if (action == android.view.MotionEvent.ACTION_DOWN) nativeFocus(false)
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
                check(listOf(actions.focusRequests, actions.focusResets, actions.restarts, actions.showRequests) == actionCounts) {
                    "Prepared desktop editor restarted focus/IME during native handoff"
                }
            }
            for ((duration, movement) in listOf(80L to 0f, 1200L to 0f, 150L to 60f)) {
                val start = android.os.SystemClock.uptimeMillis()
                remoteTouch(android.view.MotionEvent.ACTION_DOWN, start, start)
                if (movement != 0f) remoteTouch(android.view.MotionEvent.ACTION_MOVE, start, start + duration / 2, movement)
                remoteTouch(android.view.MotionEvent.ACTION_UP, start, start + duration, movement)
            }
            android.util.Log.i("ToolbarTest", "PASS: keyboard input focus survived RDP tap, hold, and drag; simulatedIme=$simulatedIme")

            nativeFocus(false)
            if (simulatedIme) imeVisibility(false)
            else {
                runOnMainSync {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                Thread.sleep(1000)
            }
            check(find("ESC") == null) { "Keyboard modifier controls survived dismissal" }
            check(keyboardState.lifecycle.value != metavrx.layout.window.compose.state.WindowLifecycle.Active) { "Key window survived dismissal" }
            check(desktop.width == desktopWidth && desktop.height == desktopHeight && parentPose() == desktopPose)
            check(desktopBridge.getDesktopWidth(sessionActivity.rdpInstance) == remoteWidth &&
                desktopBridge.getDesktopHeight(sessionActivity.rdpInstance) == remoteHeight) { "Keyboard dismissal changed the server desktop resolution" }
            click("Keyboard")
            if (simulatedIme) imeVisibility(true) else Thread.sleep(1500)
            check(find("ESC") != null) { "Keyboard could not be reopened after dismissal" }
            check(verifyKeyboardGeometry() == collapsedHeight) { "Reopened keyboard retained expanded height" }
            touchKey("SHIFT", nativeFocus = true)
            touchKey("SHIFT", nativeFocus = true)
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
            fixturePump?.let { fixtureHandler.removeCallbacks(it) }
            uiAutomation.adoptShellPermissionIdentity("horizonos.permission.MANAGE_VOLUMETRIC_WINDOWS")
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


    private fun verifyZoomedPointerMapping() {
        val viewW = 1280f
        val viewH = 800f
        val actualW = 1280
        val actualH = 800

        // 1. Identity transform: 100% zoom, 0 pan
        check(mapScreenToDesktop(0f, 0f, viewW, viewH, 1f, 0f, 0f, actualW, actualH) == 0 to 0)
        check(mapScreenToDesktop(640f, 400f, viewW, viewH, 1f, 0f, 0f, actualW, actualH) == 640 to 400)
        check(mapScreenToDesktop(1280f, 800f, viewW, viewH, 1f, 0f, 0f, actualW, actualH) == (actualW - 1) to (actualH - 1))

        // 2. 142% zoom (user scenario: zoom = 1.42f, centered)
        val zoom = 1.42f
        check(mapScreenToDesktop(640f, 400f, viewW, viewH, zoom, 0f, 0f, actualW, actualH) == 640 to 400)

        for (tx in listOf(100f, 300f, 640f, 900f, 1100f)) {
            for (ty in listOf(100f, 250f, 400f, 600f, 750f)) {
                val screenX = (tx - viewW / 2f) * zoom + viewW / 2f
                val screenY = (ty - viewH / 2f) * zoom + viewH / 2f
                val (rdpX, rdpY) = mapScreenToDesktop(screenX, screenY, viewW, viewH, zoom, 0f, 0f, actualW, actualH)
                check(kotlin.math.abs(rdpX - tx.toInt()) <= 1) { "142% zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
                check(kotlin.math.abs(rdpY - ty.toInt()) <= 1) { "142% zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
            }
        }

        // 3. High zoom (2.0f and 3.0f) with pan
        for (z in listOf(2.0f, 3.0f)) {
            val panX = 120f
            val panY = -80f
            for (tx in listOf(500f, 640f, 800f)) {
                for (ty in listOf(300f, 400f, 500f)) {
                    val screenX = (tx - viewW / 2f) * z + viewW / 2f + panX
                    val screenY = (ty - viewH / 2f) * z + viewH / 2f + panY
                    val (rdpX, rdpY) = mapScreenToDesktop(screenX, screenY, viewW, viewH, z, panX, panY, actualW, actualH)
                    check(kotlin.math.abs(rdpX - tx.toInt()) <= 1) { "$z zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
                    check(kotlin.math.abs(rdpY - ty.toInt()) <= 1) { "$z zoom mapping error: got ($rdpX, $rdpY), expected (${tx.toInt()}, ${ty.toInt()})" }
                }
            }
        }

        // 4. Clamping bounds check
        check(mapScreenToDesktop(-500f, -500f, viewW, viewH, 1.42f, 0f, 0f, actualW, actualH) == 0 to 0)
        check(mapScreenToDesktop(5000f, 5000f, viewW, viewH, 1.42f, 0f, 0f, actualW, actualH) == (actualW - 1) to (actualH - 1))
    }


    private fun verifyZoomTouchCoordinates(): String {
        val profile = ProfileManager(targetContext).getProfiles().first { it.type == ConnectionType.RDP }
        val activity = startActivitySync(Intent(targetContext, SessionActivity::class.java).apply {
            putExtra("profileId", profile.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as SessionActivity
        val sb = StringBuilder()
        Thread.sleep(6000)

        val touchesReceived = mutableListOf<Triple<Float, Float, Float>>()
        activity.onTouchDebug = { event, z, _, _ ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                touchesReceived.add(Triple(z, event.x, event.y))
                sb.append("onTouchDebug z=$z: event=(${event.x}, ${event.y})\n")
            }
        }

        for (z in listOf(1.0f, 1.25f, 1.42f, 1.5f, 2.0f, 3.0f)) {
            runOnMainSync {
                activity.setZoom?.invoke(z)
            }
            Thread.sleep(300)
            runOnMainSync {
                val downTime = android.os.SystemClock.uptimeMillis()
                val event1 = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, 800f, 500f, 0)
                event1.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(event1)
                event1.recycle()
            }
            Thread.sleep(200)
        }
        runOnMainSync { activity.finish() }
        check(touchesReceived.size == 6) { "Expected 6 touch events across all zoom levels, got ${touchesReceived.size}: $touchesReceived" }
        for ((z, x, y) in touchesReceived) {
            check(x == 800f && y == 500f) { "At zoom $z: expected unscaled screen coords (800, 500), got ($x, $y)" }
        }
        sb.append("ALL 6 ZOOM LEVELS (1.0x to 3.0x) PASSED: Screen coordinates are unscaled (800, 500) and delivered properly!\n")
        return sb.toString()
    }

}
