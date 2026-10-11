@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File

/** Ordinary-app crop transitions; external screenrecord checks actual Home presentation. */
internal class KeyboardCropVerification(private val test: Instrumentation) {
    private fun find(label: String): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { found -> return found } }
            return null
        }
        val windows = test.uiAutomation.windowsOnAllDisplays
        for (i in 0 until windows.size()) for (window in windows.valueAt(i)) {
            window.root?.let { if (it.packageName == test.targetContext.packageName) search(it)?.let { found -> return found } }
        }
        return null
    }

    private fun click(label: String) {
        var node = checkNotNull(find(label)) { "Missing $label" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
    }

    private fun awaitControl(label: String) {
        repeat(100) { if (find(label) != null) return; Thread.sleep(100) }
        error("Timed out waiting for $label")
    }

    fun run(rapid: Boolean = true, fadeOnly: Boolean = false): String {
        val info = test.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = info
        val profile = ProfileManager(test.targetContext).getProfiles().first {
            it.type == ConnectionType.RDP && it.host == "172.16.0.54"
        }
        val activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
            putExtra("profileId", profile.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as SessionActivity
        val fixtureHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var simulatedIme = false
        val fixturePump = object : Runnable {
            override fun run() {
                if (!activity.isDestroyed) {
                    val decor = activity.window.decorView
                    decor.dispatchApplyWindowInsets(android.view.WindowInsets.Builder(decor.rootWindowInsets)
                        .setVisible(android.view.WindowInsets.Type.ime(), true).build())
                    fixtureHandler.postDelayed(this, 100)
                }
            }
        }
        val nativeGeometry = KeyboardWindowGeometry(activity)
        try {
            awaitControl("Keep toolbar visible")
            click("Keep toolbar visible")
            awaitControl("Keyboard")
            click("Keyboard")
            simulatedIme = !activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
            if (simulatedIme) fixtureHandler.post(fixturePump)
            awaitControl("Backspace")
            val state = checkNotNull(activity.keyboardPanelState)
            val view = checkNotNull(state.hostedView)
            fun tokenValue(): Any? = (state.javaClass.methods.single { it.name.startsWith("getToken") }
                .invoke(state) as kotlinx.coroutines.flow.StateFlow<*>).value
            val token = tokenValue()
            val density = activity.resources.displayMetrics.density
            fun alpha(label: String): Int {
                var value = 0
                test.runOnMainSync {
                    val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(frame))
                    value = android.graphics.Color.alpha(frame.getPixel(30, 30))
                    if (label == "full") File(activity.cacheDir, "keyboard-full.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frame.recycle()
                }
                return value
            }
            val opening = (0 until 16).map { alpha("open-$it").also { Thread.sleep(100) } }
            check(opening.last() == 255 && opening.any { it in 1..254 }) { "Opening did not fade: $opening" }
            check(kotlin.math.abs(view.height-251.2*density) <= 1)
            check(find("Show more") == null && find("Show less") == null && find("Keypad Enter") == null && find("Menu") == null && find("Terminal settings") == null)
            fun rect(label: String) = android.graphics.Rect().also { checkNotNull(find(label)).getBoundsInScreen(it) }
            check(rect("Backspace").top < rect("F1").top) { "Backspace not in modifier row" }
            check(rect("/").bottom <= rect("Number Keys").top && rect("*").bottom <= rect("Number Keys").top) { "Arithmetic keys are not above Number Keys" }
            check(find("Enter") != null && find("Print Screen") != null && find("Up") != null)
            val initial = checkNotNull(nativeGeometry.read(checkNotNull(token)))
            repeat(if (rapid) 60 else 10) { i ->
                click(if (i % 2 == 0) "Number Keys" else "Function Keys")
                awaitControl(if (i % 2 == 0) "7" else "F12")
                check(state.hostedView === view && tokenValue() == token && nativeGeometry.read(checkNotNull(token)) == initial) { "Bank switch changed surface or geometry" }
                check(alpha("switch-$i") == 255) { "Bank switch faded keyboard" }
            }
            alpha("full")
            val geometry = mutableListOf<String>()
            fun measure(label: String) {
                test.uiAutomation.adoptShellPermissionIdentity()
                try {
                    val cls = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
                    val manager = activity.getSystemService(cls)
                    val infos = mutableListOf<Any>()
                    fun collect(list: List<*>) { for (info in list.filterNotNull()) {
                        infos += info
                        collect(info.javaClass.getMethod("getChildren").invoke(info) as List<*>)
                    } }
                    collect(cls.getMethod("getWindows").invoke(manager) as List<*>)
                    fun id(token: Any) = Regex("persistableToken = ([a-zA-Z0-9-]+)").find(token.toString())!!.groupValues[1]
                    fun info(token: Any) = infos.first { id(it.javaClass.getMethod("getVolumetricWindowToken").invoke(it)) == id(token) }
                    val mainToken = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", android.app.Activity::class.java).invoke(null, activity)
                    val main = info(mainToken)
                    val strip = info(checkNotNull(tokenValue()))
                    fun pose(i: Any) = SavedWindowPose.read(i.javaClass.getMethod("getPose").invoke(i))
                    fun extent(i: Any, which: String, component: String): Double {
                        val value = i.javaClass.getMethod(which).invoke(i)
                        return (value.javaClass.getMethod(component).invoke(value) as Number).toDouble()
                    }
                    val p = pose(main)
                    val up = doubleArrayOf(2.0 * (p.qx*p.qy-p.qz*p.qw), 1.0-2.0*(p.qx*p.qx+p.qz*p.qz), 2.0*(p.qy*p.qz+p.qx*p.qw))
                    fun down(i: Any): Double { val q = pose(i); return -((q.x-p.x)*up[0]+(q.y-p.y)*up[1]+(q.z-p.z)*up[2]) }
                    fun height(i: Any) = extent(i, "getBounds", "getHeight")
                    val scale = height(main) / extent(main, "getWindowSize2d", "getHeight")
                    val stripGap = (down(strip)-(height(main)+height(strip))/2)/scale
                    geometry += "$label stripGapPx=$stripGap parentWidth=${activity.window.decorView.width} stripWidth=${view.width}"
                    geometry += "stripLayout=${strip.javaClass.getMethod("getLayoutParams").invoke(strip)}"
                    File(activity.cacheDir, "keyboard-geometry.txt").writeText(geometry.joinToString("\n"))
                    check(stripGap >= -3) { "Keyboard overlaps desktop: $stripGap" }
                    check(view.width == maxOf(activity.window.decorView.width, (KEYBOARD_MIN_WIDTH_DP*density).toInt())) { "Incorrect minimum width ${view.width}" }
                } finally { test.uiAutomation.dropShellPermissionIdentity() }
            }
            Thread.sleep(700)
            measure("initial")
            if (!fadeOnly) for ((w, h) in listOf(500 to 650, 1500 to 900, 1500 to 800, 1280 to 800)) {
                test.uiAutomation.adoptShellPermissionIdentity()
                try { test.runOnMainSync {
                    val mc = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
                    val manager = activity.getSystemService(mc)
                    val tc = Class.forName("horizonos.view.VolumetricWindowToken")
                    val main = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", android.app.Activity::class.java).invoke(null, activity)
                    val bc = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowTransaction\$Builder")
                    val builder = bc.getConstructor().newInstance()
                    bc.getMethod("setSize", tc, android.util.Size::class.java).invoke(builder, main, android.util.Size(w, h))
                    val transaction = bc.getMethod("build").invoke(builder)
                    mc.getMethod("applyTransaction", transaction.javaClass).invoke(manager, transaction)
                } } finally { test.uiAutomation.dropShellPermissionIdentity() }
                Thread.sleep(1300)
                measure("${w}x$h")
            }
            check(kotlin.math.abs(view.height-251.2*density)<=1)
            click("Keyboard")
            val closing = (0 until 10).map { alpha("close-$it").also { Thread.sleep(100) } }
            File(activity.cacheDir, "fade-alpha.txt").writeText("open=$opening\nclose=$closing")
            check(closing.first() > 240 && closing.last() == 0 && closing.any { it in 1..254 }) { "Closing did not fade: $closing" }
            return "Full 251.2dp keyboard; opening/closing fade; ${if (rapid) 60 else 10} bank swaps retain native geometry/surface/token and opaque pixels; Backspace/Enter/arithmetic placement and removed controls verified. Native parent resize checks=${!fadeOnly}. Synthetic IME=$simulatedIme."
        } finally {
            nativeGeometry.close()
            fixtureHandler.removeCallbacks(fixturePump)
            test.runOnMainSync { activity.finish() }
        }
    }
}
