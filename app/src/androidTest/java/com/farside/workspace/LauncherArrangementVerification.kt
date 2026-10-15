package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.LauncherArrangementStore
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.LauncherScreen
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixture interactions use no remote sessions, no saved profiles, and a dedicated arrangement preference file. */
internal class LauncherArrangementVerification(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val preferenceName = "verification_launcher_arrangement"
    private fun nodes(): List<AccessibilityNodeInfo> {
        test.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        val displays = test.uiAutomation.windowsOnAllDisplays
        return (0 until displays.size()).flatMap { i -> displays.valueAt(i).flatMap { it.root?.let(::visit).orEmpty() } }
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(40) {
            val current = nodes()
            (current.firstOrNull { it.contentDescription?.toString() == label }
                ?: current.firstOrNull { it.text?.toString() == label })?.let { return it }
            Thread.sleep(100)
        }
        error("Missing $label")
    }
    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(250)
    }
    private fun bounds(label: String): Rect = Rect().also { find(label).getBoundsInScreen(it); check(!it.isEmpty) }

    fun run(): String {
        val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        val store = LauncherArrangementStore(context, preferenceName)
        val backgroundFile = File(context.filesDir, "card-backgrounds/verification-launcher-crop.jpg")
        backgroundFile.parentFile?.mkdirs()
        Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888).also { sample ->
            sample.eraseColor(android.graphics.Color.rgb(200, 40, 60))
            backgroundFile.outputStream().use { sample.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            sample.recycle()
        }
        val fixture = ('A'..'F').map { letter -> ConnectionProfile(letter.toString(), "Fixture $letter",
            if (letter == 'F') ConnectionType.SSH else ConnectionType.RDP, "127.0.0.1", 1, "", "") }
            .map { when (it.id) {
                "A" -> it.copy(cardBackground = backgroundFile.name)
                "F" -> it.copy(enableSftp = true)
                else -> it
            } }
        check(ConnectionProfile.fromJson(fixture[0].copy(cardBackground = "fixture.jpg").toJson()).cardBackground == "fixture.jpg")
        fun ids(profiles: List<ConnectionProfile> = fixture) = LauncherArrangementStore(context, preferenceName).load().arrange(profiles).map { it.id }
        check(ids() == listOf("A", "B", "C", "D", "E", "F"))
        store.toggleStar("C")
        store.toggleStar("E")
        check(ids() == listOf("C", "E", "A", "B", "D", "F"))
        store.move(fixture, "E", "C")
        val protectedOrder = ids()
        store.move(fixture, "F", "C")
        check(ids() == protectedOrder) { "An ordinary connection crossed the starred group" }
        store.move(fixture, "missing", "C")
        check(ids() == protectedOrder)
        val added = fixture + fixture[0].copy(id = "G", name = "Fixture G")
        check(store.load().arrange(added).last().id == "G")
        store.remove("E")
        check("E" !in store.load().starred && "E" !in store.load().order)
        check(prefs.edit().clear().commit())

        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val opens = CopyOnWriteArrayList<String>()
        val captures = CopyOnWriteArrayList<String>()
        val shown = mutableStateOf(fixture)
        try {
            test.runOnMainSync { SessionCardActions.connectedCounts["A"] = 1 }
            test.runOnMainSync {
                activity.setContent {
                    var arrangement by remember { mutableStateOf(store.load()) }
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        LauncherScreen(profiles = arrangement.arrange(shown.value), onAddProfile = {}, onDeleteProfile = {},
                            onConnectProfile = { opens.add(it) }, profileManager = ProfileManager(context),
                            starredIds = arrangement.starred,
                            onToggleStar = { arrangement = store.toggleStar(it) },
                            onMoveProfile = { source, target -> arrangement = store.move(shown.value, source, target) },
                            onCaptureProfile = { captures.add(it) })
                        }
                    }
                }
            }
            find("Click to open, or drag into space to place a window.")
            var lastHover: Rect? = null
            fun hover(targetLabel: String, tooltipLabel: String = targetLabel) {
                val target = bounds(targetLabel)
                test.runOnMainSync {
                    val decor = activity.window.decorView
                    val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                    fun send(action: Int, area: Rect) {
                        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                            action, area.exactCenterX() - origin[0], area.exactCenterY() - origin[1], 0)
                            .apply { source = android.view.InputDevice.SOURCE_MOUSE }
                        try { decor.dispatchGenericMotionEvent(event) } finally { event.recycle() }
                    }
                    lastHover?.let { send(MotionEvent.ACTION_HOVER_EXIT, it) }
                    send(MotionEvent.ACTION_HOVER_ENTER, target)
                }
                lastHover = target
                Thread.sleep(650)
                check(nodes().any { it.text?.toString() == tooltipLabel }) { "Hover tooltip missing for $tooltipLabel" }
            }
            hover("Star Fixture A", "Favorite")
            hover("Use screenshot of Fixture A as card background")
            if (QuestWindowManager.isAvailable(context)) hover("Window Manager")
            hover("Files", "SFTP Browser")
            hover("Settings")
            hover("Add Connection")
            click("Add Connection")
            find("Mac/VNC")
            find("Click here to find local PCs")
            val addFormImage = Bitmap.createBitmap(activity.window.decorView.width,
                activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            val addFormCopied = CountDownLatch(1)
            var addFormResult = -1
            test.runOnMainSync { PixelCopy.request(activity.window, addFormImage,
                { addFormResult = it; addFormCopied.countDown() }, Handler(Looper.getMainLooper())) }
            check(addFormCopied.await(5, TimeUnit.SECONDS) && addFormResult == PixelCopy.SUCCESS)
            File(context.cacheDir, "launcher-add-form.png").outputStream().use {
                addFormImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            addFormImage.recycle()
            fun scrollTo(label: String, direction: Int) {
                repeat(16) {
                    if (nodes().any { it.contentDescription?.toString() == label || it.text?.toString() == label }) return
                    val scroller = nodes().firstOrNull { it.isScrollable }
                        ?: error("Missing scrollable profile form")
                    check(scroller.performAction(direction)) { "Could not scroll to $label" }
                    Thread.sleep(120)
                }
                error("Missing $label after scrolling")
            }
            fun reconnectChecked(): Boolean {
                scrollTo("Reconnect Automatically", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return find("Reconnect Automatically").isChecked
            }
            fun selectType(label: String) {
                scrollTo(label, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                click(label)
            }
            scrollTo("Redirect Microphone", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            check(!find("Redirect Microphone").isChecked) { "New RDP profile should default microphone redirection off" }
            check(!reconnectChecked())
            click("Reconnect Automatically")
            check(reconnectChecked())
            selectType("SSH")
            check(reconnectChecked()) { "SSH profile form lost Reconnect Automatically" }
            selectType("Mac/VNC")
            check(reconnectChecked()) { "Mac/VNC profile form lost Reconnect Automatically" }
            scrollTo("Click here to find local Macs", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            fun macDiscoveryEnabled() = generateSequence(find("Click here to find local Macs")) { it.parent }.all { it.isEnabled }
            check(macDiscoveryEnabled()) { "Mac discovery should default to macOS user authentication" }
            check(bounds("macOS user").centerX() < bounds("VNC password").centerX()) { "macOS user should be left of VNC password" }
            click("VNC password")
            check(!macDiscoveryEnabled()) { "Mac discovery should disable for VNC password" }
            click("macOS user")
            check(macDiscoveryEnabled()) { "Mac discovery should re-enable for macOS user" }
            scrollTo("Reconnect Automatically", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            click("Reconnect Automatically")
            check(!reconnectChecked())
            scrollTo("Close", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            click("Close")
            check(nodes().none { it.contentDescription?.toString() == "Open another Fixture A window here" })
            val actionsImage = Bitmap.createBitmap(activity.window.decorView.width,
                activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            val actionsCopied = CountDownLatch(1)
            var actionsResult = -1
            test.runOnMainSync { PixelCopy.request(activity.window, actionsImage,
                { actionsResult = it; actionsCopied.countDown() }, Handler(Looper.getMainLooper())) }
            check(actionsCopied.await(5, TimeUnit.SECONDS) && actionsResult == PixelCopy.SUCCESS)
            File(context.cacheDir, "launcher-actions.png").outputStream().use {
                actionsImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val nameNode = find("Fixture A")
            val cardNode = generateSequence(nameNode) { it.parent }.first { it.isClickable && it != nameNode }
            val card = Rect().also { cardNode.getBoundsInScreen(it) }
            val edge = actionsImage.getPixel(card.left + 4, card.bottom - 5)
            check(android.graphics.Color.red(edge) > android.graphics.Color.green(edge) + 35) {
                "Card background did not cover the bottom edge (pixel=$edge, card=$card)"
            }
            actionsImage.recycle()
            click("Use screenshot of Fixture A as card background")
            check(captures.toList() == listOf("A") && opens.isEmpty())
            test.runOnMainSync { SessionCardActions.connectedCounts["A"] = 0 }
            Thread.sleep(200)
            check(nodes().none { it.contentDescription?.toString() == "Use screenshot of Fixture A as card background" })
            check(nodes().none { it.text?.toString() in listOf("Click to open", "Drag to place") })
            click("Star Fixture C")
            click("Star Fixture E")
            hover("Unstar Fixture C", "Unfavorite")
            check(ids().take(2) == listOf("C", "E") && opens.isEmpty())
            check(bounds("Fixture C").centerY() <= bounds("Fixture A").centerY())

            // Direct touch includes the same pointer recognizers that also handle spatial drags.
            fun gesture(source: String, target: String? = null, jitter: Boolean = false) {
                val from = bounds(source)
                val to = target?.let(::bounds) ?: from
                val downTime = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float, y: Float) {
                    test.runOnMainSync {
                        val decor = activity.window.decorView
                        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                        val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x - origin[0], y - origin[1], 0)
                        try { decor.dispatchTouchEvent(motion) } finally { motion.recycle() }
                    }
                    Thread.sleep(80)
                }
                event(MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
                event(MotionEvent.ACTION_MOVE, to.exactCenterX() + if (jitter) 1 else 0, to.exactCenterY())
                event(MotionEvent.ACTION_UP, to.exactCenterX(), to.exactCenterY())
                Thread.sleep(250)
            }
            gesture("Fixture A", jitter = true)
            check(opens.toList() == listOf("A")) { "A small-motion click did not open exactly once" }
            gesture("Unstar Fixture C")
            check("C" !in store.load().starred && opens.size == 1) { "Star touch also opened a connection" }
            click("Star Fixture C")
            click("Reorder")
            find("Done reordering")
            gesture("Fixture A")
            check(opens.size == 1) { "A card tap in reorder mode opened a connection" }
            val firstArrow = generateSequence(find("Move Fixture C earlier")) { it.parent }.toList()
            check(firstArrow.any { !it.isEnabled }) {
                "First starred arrow has no disabled button ancestor; order=${ids()} nodes=${firstArrow.map { "${it.className}:${it.isEnabled}:${it.isClickable}" }}"
            }
            val beforeDisabledTap = ids()
            gesture("Move Fixture C earlier")
            check(ids() == beforeDisabledTap)
            click("Move Fixture E earlier")
            check(ids().take(2) == listOf("E", "C"))
            click("Move Fixture A later")
            check(ids() == listOf("E", "C", "B", "A", "D", "F"))
            // A single large MOVE must reorder the card pressed at DOWN, not the target card.
            gesture("Fixture F", "Fixture B")
            check(ids() == listOf("E", "C", "F", "B", "A", "D")) { "Grid drag did not move the original card" }
            gesture("Fixture D", "Fixture E")
            check(ids() == listOf("E", "C", "F", "B", "A", "D"))
            check(opens.size == 1)
            check(prefs.edit().commit()) // Flush fixture metadata before inspecting/reloading it.

            val capture = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            val captured = CountDownLatch(1)
            var result = -1
            test.runOnMainSync { PixelCopy.request(activity.window, capture, { result = it; captured.countDown() }, Handler(Looper.getMainLooper())) }
            check(captured.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
            File(context.cacheDir, "launcher-reorder.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            capture.recycle()
            click("Done reordering")
            gesture("Fixture B")
            check(opens.toList() == listOf("A", "B"))

            // Hold at the grid edge with many connections; the pointer must remain captured
            // while rows scroll and keyed items move, without starting a spatial launch.
            test.runOnMainSync { shown.value = fixture + (0 until 40).map {
                fixture[0].copy(id = "extra-$it", name = "Offscreen fixture $it")
            } }
            click("Reorder")
            fun lastVisibleExtra(): Int = nodes().mapNotNull {
                it.text?.toString()?.takeIf { text -> text.startsWith("Offscreen fixture ") }
                    ?.substringAfterLast(' ')?.toIntOrNull()
            }.maxOrNull() ?: -1
            val visibleBefore = lastVisibleExtra()
            val source = bounds("Fixture F")
            val edgeY = bounds("Connections").bottom - 8 * activity.resources.displayMetrics.density
            val edgeTime = SystemClock.uptimeMillis()
            fun edgeEvent(action: Int, y: Float) {
                test.runOnMainSync {
                    val decor = activity.window.decorView
                    val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                    val event = MotionEvent.obtain(edgeTime, SystemClock.uptimeMillis(), action,
                        source.exactCenterX() - origin[0], y - origin[1], 0)
                    try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            edgeEvent(MotionEvent.ACTION_DOWN, source.exactCenterY())
            Thread.sleep(80)
            edgeEvent(MotionEvent.ACTION_MOVE, edgeY)
            Thread.sleep(1500)
            val visibleAfter = lastVisibleExtra()
            edgeEvent(MotionEvent.ACTION_UP, edgeY)
            Thread.sleep(200)
            check(visibleAfter > visibleBefore) { "Reorder edge drag did not scroll to more connections" }
            check(ids(shown.value).take(2) == listOf("E", "C") && opens.size == 2)
            File(context.cacheDir, "launcher-arrangement-results.json").writeText(JSONObject()
                .put("starred", JSONArray(store.load().starred.toList())).put("order", JSONArray(ids(shown.value)))
                .put("opens", JSONArray(opens.toList())).put("visibleBeforeEdgeScroll", visibleBefore).put("visibleAfterEdgeScroll", visibleAfter)
                .put("isolatedArrangementPreferences", true).toString(2))
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground) {
                            Box(Modifier.width(520.dp).height(620.dp)) {
                                LauncherScreen(profiles = fixture, onAddProfile = {}, onDeleteProfile = {},
                                    onConnectProfile = {}, profileManager = ProfileManager(context))
                            }
                        }
                    }
                }
            }
            Thread.sleep(500)
            val first = bounds("Fixture A")
            val second = bounds("Fixture B")
            check(first.centerY() == second.centerY() && second.left > first.right) {
                "Compact launcher did not use two readable columns"
            }
            fun cardBounds(name: String): Rect {
                val label = find(name)
                val card = generateSequence(label) { it.parent }.first { it.isClickable && it != label }
                return Rect().also { card.getBoundsInScreen(it) }
            }
            check(cardBounds("Fixture A").height() == cardBounds("Fixture B").height()) {
                "Compact cards have different heights"
            }
            check(bounds("Farside Connections").right <= bounds("Add Connection").left) {
                "Compact launcher title overlaps its actions"
            }
            if (QuestWindowManager.isAvailable(context)) {
                check(bounds("Add Connection").right <= bounds("Window Manager").left) {
                    "Add Connection is not left of Window Manager"
                }
                check(bounds("Window Manager").right <= bounds("Files").left) {
                    "Window Manager button overlaps Files"
                }
            } else {
                check(bounds("Add Connection").right <= bounds("Files").left)
            }
            check(nodes().none { it.text?.toString() == "New Connection" })
            val compactImage = Bitmap.createBitmap(activity.window.decorView.width,
                activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            val compactCopied = CountDownLatch(1)
            var compactResult = -1
            test.runOnMainSync { PixelCopy.request(activity.window, compactImage,
                { compactResult = it; compactCopied.countDown() }, Handler(Looper.getMainLooper())) }
            check(compactCopied.await(5, TimeUnit.SECONDS) && compactResult == PixelCopy.SUCCESS)
            File(context.cacheDir, "launcher-compact.png").outputStream().use {
                compactImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compactImage.recycle()
            click("Add Connection")
            find("Mac/VNC")
            click("Close")
            if (QuestWindowManager.isAvailable(context)) {
                click("Window Manager")
            }
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            LauncherScreen(profiles = emptyList(), onAddProfile = {}, onDeleteProfile = {},
                                onConnectProfile = {}, profileManager = ProfileManager(context))
                        }
                    }
                }
            }
            find("Add Connection")
            fun launcherNodes(): List<AccessibilityNodeInfo> {
                val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
                val window = Rect(origin[0], origin[1], origin[0] + activity.window.decorView.width,
                    origin[1] + activity.window.decorView.height)
                return nodes().filter { node ->
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    window.contains(bounds.centerX(), bounds.centerY())
                }
            }
            fun hasEmptyStateControls() = launcherNodes().any { it.text?.toString() == "Click to open, or drag into space to place a window." ||
                it.text?.toString() == "Reorder" || it.contentDescription?.toString() == "Files" }
            var emptyWaits = 0
            while (hasEmptyStateControls() && emptyWaits++ < 40) Thread.sleep(50)
            check(!hasEmptyStateControls()) {
                "Empty launcher shows connection controls or Files"
            }
            val addButton = generateSequence(find("Add Connection")) { it.parent }.first { it.isClickable }
            val buttonRect = Rect().also { addButton.getBoundsInScreen(it) }
            val decorOrigin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
            buttonRect.offset(-decorOrigin[0], -decorOrigin[1])
            fun captureEmpty(): Bitmap {
                val image = Bitmap.createBitmap(activity.window.decorView.width,
                    activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                var result = -1
                test.runOnMainSync { PixelCopy.request(activity.window, image,
                    { result = it; copied.countDown() }, Handler(Looper.getMainLooper())) }
                check(copied.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
                return image
            }
            Thread.sleep(400)
            val firstPulse = captureEmpty()
            Thread.sleep(900)
            val secondPulse = captureEmpty()
            var changedPixels = 0
            for (y in buttonRect.top.coerceAtLeast(0) until buttonRect.bottom.coerceAtMost(firstPulse.height)) {
                for (x in buttonRect.left.coerceAtLeast(0) until buttonRect.right.coerceAtMost(firstPulse.width)) {
                    if (firstPulse.getPixel(x, y) != secondPulse.getPixel(x, y)) changedPixels++
                }
            }
            check(changedPixels > 20) { "Add Connection circle color did not pulse in the empty launcher: $changedPixels changed pixels" }
            File(context.cacheDir, "launcher-empty-pulse.png").outputStream().use {
                secondPulse.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            firstPulse.recycle()
            secondPulse.recycle()
            hover("Add Connection")
            val firstHover = captureEmpty()
            Thread.sleep(900)
            val secondHover = captureEmpty()
            var changedHoverPixels = 0
            for (y in buttonRect.top.coerceAtLeast(0) until buttonRect.bottom.coerceAtMost(firstHover.height)) {
                for (x in buttonRect.left.coerceAtLeast(0) until buttonRect.right.coerceAtMost(firstHover.width)) {
                    if (firstHover.getPixel(x, y) != secondHover.getPixel(x, y)) changedHoverPixels++
                }
            }
            check(changedHoverPixels < 20) { "Hovered Add Connection button kept pulsing: $changedHoverPixels changed pixels" }
            File(context.cacheDir, "launcher-empty-hover.png").outputStream().use {
                secondHover.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            firstHover.recycle()
            secondHover.recycle()
            click("Add Connection")
            find("Mac/VNC")
            click("Close")
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            LauncherScreen(profiles = fixture.take(1), onAddProfile = {}, onDeleteProfile = {},
                                onConnectProfile = {}, profileManager = ProfileManager(context))
                        }
                    }
                }
            }
            find("Fixture A")
            find("Click to open, or drag into space to place a window.")
            check(launcherNodes().none { it.text?.toString() == "Reorder" || it.contentDescription?.toString() == "Files" }) {
                "Single non-SFTP connection shows Reorder or Files"
            }
            return "launcher arrangement, compact actions, empty color pulse ($changedPixels pixels), and 0/1 connection controls"
        } finally {
            test.runOnMainSync { SessionCardActions.connectedCounts.remove("A") }
            test.runOnMainSync { activity.finish() }
            check(prefs.edit().clear().commit())
            backgroundFile.delete()
        }
    }
}
