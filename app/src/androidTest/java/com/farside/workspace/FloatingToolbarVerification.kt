package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farside.workspace.data.*
import com.farside.workspace.ui.ProfileCard
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Production shared toolbar, synthetic touch dispatch; no network or saved-profile edits. */
internal class FloatingToolbarVerification(private val test: Instrumentation) {
    fun run(): String {
        val context = test.targetContext
        val ui = SetupPhaseOneVerification(test)
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = checkNotNull(test.waitForMonitorWithTimeout(monitor, 15000)) as MainActivity
        test.removeMonitor(monitor)
        val state = FloatingToolbarState(32f)
        var clicks = 0
        var interaction = 0
        var toolbarBounds = androidx.compose.ui.geometry.Rect.Zero
        var movement by mutableLongStateOf(0L)
        var driveMovement by mutableStateOf(false)
        fun showToolbar() = test.runOnMainSync {
            activity.setContent { MaterialTheme {
                LaunchedEffect(driveMovement) {
                    while (driveMovement) { movement++; delay(16) }
                }
                val moving by rememberToolbarMoving(movement)
                FloatingSessionToolbar(state, 1f, { interaction++ }, onBounds = { toolbarBounds = it },
                    moving = moving, movementLabel = "Desktop 2 - Owned computer") {
                    Row(Modifier.padding(8.dp)) {
                        Text("Owned toolbar", Modifier.padding(12.dp))
                        Button(onClick = { clicks++ }) { Text("Owned control") }
                    }
                }
            } }
        }
        fun rect(label: String) = android.graphics.Rect().also { ui.find(label).getBoundsInScreen(it) }
        fun event(action: Int, x: Float, y: Float, down: Long) {
            test.runOnMainSync {
                val decor = activity.window.decorView
                val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x - origin[0], y - origin[1], 0)
                try { decor.dispatchTouchEvent(motion) } finally { motion.recycle() }
            }
        }
        fun tap(x: Float, y: Float) {
            val down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, x, y, down); Thread.sleep(40)
            event(MotionEvent.ACTION_UP, x, y, down)
        }
        fun doubleTap(label: String, settle: Long = 400) {
            val b = rect(label)
            tap(b.exactCenterX(), b.exactCenterY()); Thread.sleep(90)
            tap(b.exactCenterX(), b.exactCenterY()); Thread.sleep(settle)
        }
        fun drag(label: String, dx: Float, dy: Float) {
            val b = rect(label)
            val down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, b.exactCenterX(), b.exactCenterY(), down)
            for (i in 1..12) {
                Thread.sleep(20)
                event(MotionEvent.ACTION_MOVE, b.exactCenterX() + dx * i / 12, b.exactCenterY() + dy * i / 12, down)
            }
            event(MotionEvent.ACTION_UP, b.exactCenterX() + dx, b.exactCenterY() + dy, down)
            Thread.sleep(350)
        }
        fun capture(name: String, noEarlyArrow: Boolean = false) {
            val decor = activity.window.decorView
            val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = -1
            test.runOnMainSync { android.view.PixelCopy.request(activity.window, image, { result = it; done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
            check(done.await(5, TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
            if (noEarlyArrow) {
                // At the start of collapse the bar is still well inside the window.
                // A prematurely rendered nub would put its white arrow in this strip.
                val y = state.nubY.toInt().coerceAtLeast(0)
                val bottom = (y + toolbarBounds.height.toInt()).coerceAtMost(image.height)
                check((y until bottom).none { row -> (0 until 48.coerceAtMost(image.width)).any { col ->
                    val pixel = image.getPixel(col, row)
                    android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220
                } }) { "Restore arrow appeared before the toolbar was almost offscreen" }
            }
            File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            showToolbar()
            val original = rect("Owned toolbar")
            val control = rect("Owned control")
            tap(control.exactCenterX(), control.exactCenterY()); Thread.sleep(350)
            check(clicks == 1 && !state.collapsed) { "Single button action changed" }
            drag("Owned toolbar", -90f, 130f)
            check(state.offsetX < -30f && state.offsetY > 70f) { "Expanded drag failed" }
            val moved = rect("Owned toolbar")
            check(moved != original)
            val savedX = state.offsetX; val savedY = state.offsetY
            val savedHeight = toolbarBounds.height
            doubleTap("Owned toolbar", settle = 30)
            check(state.collapsed) { "Double click did not collapse" }
            check(!state.dockRight) { "A toolbar left of center must minimize to the left" }
            capture("toolbar-collapse-start.png", noEarlyArrow = true)
            Thread.sleep(350)
            val restore = context.getString(R.string.restore_toolbar)
            val nub = rect(restore)
            check(nub.width() >= 40 && nub.height() >= 40)
            check(kotlin.math.abs(nub.height() - savedHeight) <= 2f) { "Nub height must match the toolbar: ${nub.height()} vs $savedHeight" }
            check(ui.nodes().none { it.isVisibleToUser && it.text?.toString() == "Owned control" }) { "Hidden controls remain accessible" }
            capture("toolbar-collapsed.png")
            Thread.sleep(3500)
            rect(restore) // The nub outlives the existing auto-fade interval.
            drag(restore, 12f, 140f)
            check(rect(restore).left == nub.left && state.nubY > savedY + 50f) { "Vertical nub drag did not stay docked: y=${state.nubY}, savedY=$savedY, before=$nub, after=${rect(restore)}" }
            drag(restore, 240f, 0f)
            check(rect(restore).left == nub.left) { "Horizontal drag detached the nub from the side" }
            capture("toolbar-nub-docked.png")
            // The actual Home pose observer feeds this same signal in production.
            test.runOnMainSync { driveMovement = true }
            Thread.sleep(450)
            rect("Desktop 2 - Owned computer")
            check(state.collapsed) { "Movement must retain the collapsed preference" }
            check(ui.nodes().none { it.isVisibleToUser && it.text?.toString() == "Owned control" }) {
                "Movement peek exposed controls"
            }
            val peekArrow = rect(restore)
            check(peekArrow.left == nub.left && peekArrow.width() > nub.width()) { "Name peek must stay docked and retain the restore arrow" }
            drag(restore, 240f, 0f)
            check(rect(restore).left == nub.left) { "Horizontal drag detached the name peek" }
            capture("toolbar-moving-name.png")
            test.runOnMainSync { driveMovement = false }
            // Pose-quiet detection plus the same 260ms slide, with no visibility hold.
            Thread.sleep(450)
            rect(restore)
            check(ui.nodes().none { it.isVisibleToUser && it.text?.toString() == "Desktop 2 - Owned computer" })
            capture("toolbar-movement-settled.png")
            val restoreY = state.nubY
            val nubTop = toolbarBounds.top
            doubleTap(restore)
            check(!state.collapsed && state.offsetX == savedX && state.offsetY == restoreY) { "Restore ignored the moved nub height" }
            check(kotlin.math.abs(toolbarBounds.top - nubTop) <= 2f) { "Restored toolbar and nub top edges differ" }
            val restored = rect("Owned toolbar")
            check(kotlin.math.abs(restored.left - moved.left) <= 2 && kotlin.math.abs(restored.top - moved.top - (restoreY - savedY)) <= 2)
            check(ui.nodes().none { it.isVisibleToUser && it.contentDescription?.toString() == restore })
            capture("toolbar-restored.png")
            // Move the same toolbar across the midpoint and exercise the mirror.
            drag("Owned toolbar", 280f, -40f)
            check(state.offsetX > 0f)
            val rightX = state.offsetX
            val rightY = state.offsetY
            val rightMoved = rect("Owned toolbar")
            doubleTap("Owned toolbar")
            check(state.collapsed && state.dockRight) { "A toolbar right of center must minimize to the right" }
            val rightNub = rect(restore)
            val windowRight = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }[0] + activity.window.decorView.width
            check(kotlin.math.abs(rightNub.right - windowRight) <= 2) { "Right nub is not flush with the window edge: $rightNub vs $windowRight" }
            check(rightNub.height() == nub.height())
            capture("toolbar-right-collapsed.png")
            drag(restore, -240f, 70f)
            check(rect(restore).right == rightNub.right && state.nubY > rightY + 20f) { "Right nub detached or did not move vertically" }
            test.runOnMainSync { driveMovement = true }
            Thread.sleep(450)
            rect("Desktop 2 - Owned computer")
            val rightPeek = rect(restore)
            check(rightPeek.right == rightNub.right && rightPeek.width() > rightNub.width())
            check(ui.nodes().none { it.isVisibleToUser && it.text?.toString() == "Owned control" })
            drag(restore, -240f, 0f)
            check(rect(restore).right == rightNub.right) { "Right name peek detached from the edge" }
            capture("toolbar-right-moving-name.png")
            test.runOnMainSync { driveMovement = false }
            Thread.sleep(450)
            check(rect(restore).right == rightNub.right)
            check(ui.nodes().none { it.isVisibleToUser && it.text?.toString() == "Desktop 2 - Owned computer" })
            capture("toolbar-right-settled.png")
            val rightRestoreY = state.nubY
            val rightNubTop = toolbarBounds.top
            doubleTap(restore)
            check(!state.collapsed && state.offsetX == rightX && state.offsetY == rightRestoreY)
            check(kotlin.math.abs(toolbarBounds.top - rightNubTop) <= 2f)
            val rightRestored = rect("Owned toolbar")
            check(kotlin.math.abs(rightRestored.left - rightMoved.left) <= 2 &&
                kotlin.math.abs(rightRestored.top - rightMoved.top - (rightRestoreY - rightY)) <= 2)
            capture("toolbar-right-restored.png")
            check(interaction > 3)
            check(!FloatingToolbarState().collapsed && FloatingToolbarState().offsetX == 0f)
            // Drive the expanded bar against all edges; it must respond immediately
            // when the direction reverses, without accumulating offscreen offsets.
            test.runOnMainSync {
                state.dragExpanded(-10000f, -10000f, 1000f, 700f, 300f, 60f)
                check(state.offsetX == -350f && state.offsetY == 0f)
                state.dragExpanded(10f, 10f, 1000f, 700f, 300f, 60f)
                check(state.offsetX == -340f && state.offsetY == 10f)
                state.dragExpanded(10000f, 10000f, 1000f, 700f, 300f, 60f)
                check(state.offsetX == 350f && state.offsetY == 640f)
            }

            // Render the actual launcher card for each authentication/protocol variant.
            for (type in ConnectionType.entries) for (auth in VncAuthMode.entries) {
                val p = ConnectionProfile("owned", "Owned label", type, "127.0.0.1", user = "fixture", pass = "fixture", vncAuthMode = auth)
                test.runOnMainSync { activity.setContent { MaterialTheme { ProfileCard(p, {}, {}, {}) } } }
                val expected = context.getString(if (type == ConnectionType.VNC && auth == VncAuthMode.MAC_USER) R.string.protocol_mac else type.labelResId)
                ui.find(expected)
                if (type == ConnectionType.VNC && auth == VncAuthMode.MAC_USER) {
                    check(ui.nodes().none { it.isVisibleToUser && it.text?.toString() == context.getString(R.string.protocol_vnc) })
                    capture("launcher-mac-label.png")
                }
            }
            return "PASS left/right midpoint docking, constrained dragging, matching nub height, mirrored name/desktop peek with restore arrow, smooth rehide and restore at moved nub height on both sides; controls and launcher labels. Synthetic touches/pose signals only."
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }
}
