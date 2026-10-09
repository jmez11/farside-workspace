package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager

class SshVerification(private val test: Instrumentation) {
    private fun nodes(): List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        val displays = test.uiAutomation.windowsOnAllDisplays
        return (0 until displays.size()).flatMap { index -> displays.valueAt(index).flatMap { it.root?.let(::visit).orEmpty() } }
    }

    private fun find(label: String, retries: Int = 20): AccessibilityNodeInfo? {
        repeat(retries) {
            test.uiAutomation.clearCache()
            nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private fun click(label: String) {
        val node = checkNotNull(find(label)) { "Cannot find clickable control: $label" }
        var target: AccessibilityNodeInfo = node
        while (!target.isClickable && target.parent != null) target = target.parent
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click control: $label" }
        Thread.sleep(300)
    }

    fun run() {
        Log.i("SshVerification", "Starting SshVerification run")
        val serviceInfo = test.uiAutomation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = serviceInfo

        val profileManager = ProfileManager(test.targetContext)
        val originalProfiles = profileManager.getProfiles()
        val testProfile = ConnectionProfile(
            id = "test-ssh-verify-profile",
            name = "SSH Term Test",
            host = "127.0.0.1",
            port = 2222,
            user = "user",
            pass = "secret",
            type = ConnectionType.SSH
        )
        check(testProfile.autoCopySelection) { "Default autoCopySelection must be true" }
        val testJson = testProfile.toJson()
        check(testJson.optBoolean("autoCopySelection", false)) { "toJson missing autoCopySelection" }
        check(ConnectionProfile.fromJson(testJson).autoCopySelection) { "fromJson did not preserve autoCopySelection" }
        profileManager.addProfile(testProfile)

        Log.i("SshVerification", "Launching SessionActivity")
        val intent = Intent(test.targetContext, SessionActivity::class.java).apply {
            putExtra("profileId", testProfile.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = test.startActivitySync(intent) as SessionActivity
        Thread.sleep(2500)

        val fixtureHandler = Handler(Looper.getMainLooper())
        var fixtureVisible = false
        var fixturePump: Runnable? = null

        try {
            // 1. Verify Floating Toolbar appears with connection name, host, pin, zoom, and keyboard toggle
            Log.i("SshVerification", "Step 1: Finding toolbar controls")
            checkNotNull(find("SSH Term Test")) { "Missing connection name in floating toolbar" }
            checkNotNull(find("127.0.0.1")) { "Missing connection host in floating toolbar" }
            checkNotNull(find("Keep toolbar visible")) { "Missing pin button in toolbar" }
            checkNotNull(find("Zoom")) { "Missing zoom button in toolbar" }
            checkNotNull(find("Transparency")) { "Missing transparency button in toolbar" }
            checkNotNull(find("Keyboard")) { "Missing keyboard toggle in toolbar" }

            // 2. Verify Zoom (Font Size) slider and Reset Zoom button
            Log.i("SshVerification", "Step 2: Testing Zoom")
            click("Zoom")
            checkNotNull(find("14sp")) { "Missing 14sp default font size indicator" }
            test.runOnMainSync {
                activity.setZoom?.invoke(1.5f)
            }
            Thread.sleep(400)
            checkNotNull(find("Reset Zoom")) { "Missing Reset Zoom button after font size change" }
            checkNotNull(find("21sp")) { "Font size was not scaled to 21sp" }
            click("Reset Zoom")
            Thread.sleep(300)
            checkNotNull(find("14sp")) { "Font size was not reset to 14sp" }
            click("Zoom") // Close zoom slider

            // 2b. Verify Transparency slider
            Log.i("SshVerification", "Step 2b: Testing Transparency")
            click("Transparency")
            checkNotNull(find("100%")) { "Missing 100% default transparency indicator" }
            click("Transparency") // Close transparency slider

            // 3. Verify Pinning toolbar and disappearing behavior
            Log.i("SshVerification", "Step 3: Testing Pinning")
            click("Keep toolbar visible")
            checkNotNull(find("Unpin toolbar")) { "Toolbar pin toggle did not change to Unpin" }
            click("Unpin toolbar")
            checkNotNull(find("Keep toolbar visible")) { "Toolbar pin toggle did not change to Keep toolbar visible" }
            Thread.sleep(4000)
            check(find("Keep toolbar visible", retries = 3) == null) { "Toolbar did not fade away automatically when unpinned" }

            Log.i("SshVerification", "Step 3b: Revealing toolbar")
            test.runOnMainSync {
                val decor = activity.window.decorView
                val now = android.os.SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, decor.width * 0.5f, decor.height * 0.5f, 0)
                decor.dispatchTouchEvent(event)
                event.recycle()
            }
            Thread.sleep(500)
            checkNotNull(find("Keep toolbar visible")) { "Interacting with window did not reveal floating toolbar" }
            click("Keep toolbar visible") // Keep pinned for subsequent keyboard test steps
            checkNotNull(find("Unpin toolbar"))

            // 4. Verify main content window origin before keyboard is toggled
            val initialContentLocation = IntArray(2)
            val initialContentWidth: Int
            val initialContentHeight: Int
            val content = activity.findViewById<View>(android.R.id.content)
            run {
                content.getLocationInWindow(initialContentLocation)
                initialContentWidth = content.width
                initialContentHeight = content.height
            }

            // 5. Verify Keyboard toggle and Extra Keys (RemoteKeyboardPanel)
            Log.i("SshVerification", "Step 5: Testing Keyboard and Extra Keys")
            click("Keyboard")

            val keyboardState = checkNotNull(activity.keyboardPanelState) { "Keyboard state not assigned to activity" }
            val simulatedIme = !activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            fun imeVisibility(visible: Boolean) {
                fixtureVisible = visible
                test.runOnMainSync {
                    val decor = activity.window.decorView
                    decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                        .setVisible(android.view.WindowInsets.Type.ime(), visible).build())
                }
                Thread.sleep(if (visible) 500 else 1000)
            }
            if (simulatedIme) {
                Log.i("SshVerification", "Horizon unworn: simulating IME insets")
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

            repeat(20) {
                if (keyboardState.lifecycle.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) return@repeat
                Thread.sleep(100)
            }
            check(keyboardState.lifecycle.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) {
                "Keyboard panel window not active: ${keyboardState.lifecycle.value}"
            }

            fun panelView(): View = checkNotNull(keyboardState.javaClass.methods.single {
                it.name.startsWith("getHostedView") }.invoke(keyboardState) as? View)

            fun touchKey(label: String) {
                val node = checkNotNull(find(label)) { "Missing touch key: $label" }
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                test.runOnMainSync {
                    val panel = panelView()
                    val origin = IntArray(2).also { panel.getLocationOnScreen(it) }
                    val started = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(started, started + if (action == MotionEvent.ACTION_UP) 50 else 0,
                            action, bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1], 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { check(panel.dispatchTouchEvent(event)) { "$label touch was not handled" } }
                        finally { event.recycle() }
                    }
                }
                Thread.sleep(300)
            }

            checkNotNull(find("WIN")) { "Missing WIN key" }
            checkNotNull(find("SHIFT")) { "Missing SHIFT key" }
            checkNotNull(find("CTRL")) { "Missing CTRL key" }
            checkNotNull(find("ALT")) { "Missing ALT key" }
            checkNotNull(find("DEL")) { "Missing DEL key" }
            checkNotNull(find("ESC")) { "Missing ESC key" }
            checkNotNull(find("TAB")) { "Missing TAB key" }
            checkNotNull(find("123")) { "Missing 123 extra keys expansion key" }

            val collapsedHeight = panelView().height

            // Verify main content window did NOT move or shift
            val contentLocationWithKeys = IntArray(2)
            content.getLocationInWindow(contentLocationWithKeys)
            check(initialContentLocation.contentEquals(contentLocationWithKeys) &&
                    content.width == initialContentWidth && content.height == initialContentHeight) {
                "Main content window moved or shifted when opening extra keys panel: before=${initialContentLocation.toList()} after=${contentLocationWithKeys.toList()}"
            }

            // 6. Verify Extra Keys expansion ("123" -> expands panel to 314dp with full keys)
            Log.i("SshVerification", "Step 6: Testing 123 expansion")
            touchKey("123")
            Thread.sleep(600)
            checkNotNull(find("ABC")) { "123 button did not switch to ABC on expansion" }
            val expandedHeight = panelView().height
            check(expandedHeight > collapsedHeight * 2) { "123 did not expand the keyboard panel: expanded=$expandedHeight collapsed=$collapsedHeight" }

            checkNotNull(find("F1")) { "Missing F1 key on expanded panel" }
            checkNotNull(find("F12")) { "Missing F12 key on expanded panel" }
            checkNotNull(find("Home")) { "Missing Home key on expanded panel" }
            checkNotNull(find("End")) { "Missing End key on expanded panel" }
            checkNotNull(find("Up")) { "Missing Up arrow on expanded panel" }
            checkNotNull(find("Down")) { "Missing Down arrow on expanded panel" }
            checkNotNull(find("Left")) { "Missing Left arrow on expanded panel" }
            checkNotNull(find("Right")) { "Missing Right arrow on expanded panel" }
            checkNotNull(find("Backspace")) { "Missing Backspace on expanded panel" }
            checkNotNull(find("Keypad Enter")) { "Missing Keypad Enter on expanded panel" }

            touchKey("Number Keys")
            Thread.sleep(300)
            checkNotNull(find("Function Keys")) { "Number Keys did not swap to Function Keys button" }
            checkNotNull(find("7")) { "Missing 7 key in numbers bank" }
            checkNotNull(find("0")) { "Missing 0 key in numbers bank" }
            touchKey("Function Keys")
            Thread.sleep(300)
            checkNotNull(find("F1")) { "Function Keys did not restore F1 key" }

            // 7. Verify Contraction on close ("ABC" -> contracts window back to compact 76dp)
            Log.i("SshVerification", "Step 7: Testing ABC contraction")
            touchKey("ABC")
            Thread.sleep(600)
            checkNotNull(find("123")) { "ABC button did not contract and switch back to 123" }
            check(panelView().height == collapsedHeight) { "Keyboard panel did not contract to collapsed height: ${panelView().height} vs $collapsedHeight" }
            check(find("F1", retries = 2) == null) { "Expanded keys still visible after contracting" }

            // Verify main content window still did not move or shift
            val contentLocationAfterCollapse = IntArray(2)
            content.getLocationInWindow(contentLocationAfterCollapse)
            check(initialContentLocation.contentEquals(contentLocationAfterCollapse)) {
                "Main content window moved or shifted after contracting extra keys panel"
            }

            // 8. Close keyboard panel
            Log.i("SshVerification", "Step 8: Closing keyboard")
            fixtureVisible = false
            click("Keyboard")
            Thread.sleep(600)
            check(find("123", retries = 2) == null) { "Extra keys panel did not close" }
            Log.i("SshVerification", "All steps completed successfully!")
        } catch (e: Throwable) {
            Log.e("SshVerification", "Exception during SshVerification", e)
            throw e
        } finally {
            Log.i("SshVerification", "Cleaning up activity and profiles")
            fixtureVisible = false
            fixturePump?.let { fixtureHandler.removeCallbacks(it) }
            profileManager.deleteProfile(testProfile.id)
        }
    }
}
