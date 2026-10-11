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

    fun run(rapid: Boolean = true): String {
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
        try {
            awaitControl("Keep toolbar visible")
            click("Keep toolbar visible")
            awaitControl("Keyboard")
            click("Keyboard")
            awaitControl("123")
            Thread.sleep(2000)
            val state = checkNotNull(activity.keyboardPanelState)
            val view = checkNotNull(state.hostedView)
            fun tokenValue(): Any? = (state.javaClass.methods.single { it.name.startsWith("getToken") }
                .invoke(state) as kotlinx.coroutines.flow.StateFlow<*>).value
            val token = tokenValue()
            val width = view.width
            val height = view.height
            val density = activity.resources.displayMetrics.density
            check(kotlin.math.abs(height - 76 * density) <= 1) { "Unexpected buffer $width x $height" }
            val rows = mutableListOf("millis,label,width,height,bankHash")
            var reference: Int? = null
            // Six bursts of five complete cycles, with no long settle between toggles.
            repeat(if (rapid) 60 else 2) { index ->
                val before = if (index % 2 == 0) "123" else "ABC"
                val after = if (index % 2 == 0) "ABC" else "123"
                click(before)
                awaitControl(after)
                Thread.sleep(100)
                test.runOnMainSync {
                    check(state.hostedView === view && tokenValue() == token) { "Surface replaced" }
                    check(view.width == width && view.height == height) { "Buffer resized" }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val bankTop = 0
                    val stableWidth = (width * 0.80).toInt()
                    val pixels = IntArray(stableWidth * (height - bankTop))
                    bitmap.getPixels(pixels, 0, stableWidth, 0, bankTop, stableWidth, height - bankTop)
                    val hash = pixels.contentHashCode()
                    if (reference == null) reference = hash
                    // Paging changes text only; native surface geometry remains fixed.
                    rows += "${System.currentTimeMillis()},$after,$width,$height,$hash"
                    if (index < 2) File(activity.cacheDir, "crop-painted-$after.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                if (index % 10 == 9) Thread.sleep(600)
            }
            click("123")
            awaitControl("F1")
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
                    check(stripGap >= 80*density) { "Insufficient shortcut clearance: $stripGap" }
                    check(view.width == activity.window.decorView.width)
                } finally { test.uiAutomation.dropShellPermissionIdentity() }
            }
            Thread.sleep(700)
            measure("initial")
            awaitControl("Number Keys")
            click("Number Keys")
            awaitControl("0")
            click("Next key page")
            awaitControl("7")
            click("Navigation Keys")
            awaitControl("Left")
            click("Next key page")
            awaitControl("PgUp")
            click("Next key page")
            awaitControl("Print Screen")
            click("Function Keys")
            awaitControl("F1")
            click("Next key page")
            awaitControl("F12")
            for ((w, h) in listOf(1000 to 650, 1500 to 900, 1280 to 800)) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                    "am task resize ${activity.taskId} 0 0 $w $h")).use { it.readBytes() }
                Thread.sleep(1300)
                measure("${w}x$h")
            }
            click("ABC")
            Thread.sleep(1000)
            check(state.hostedView === view && tokenValue() == token) { "Fixed surface was replaced" }
            File(activity.cacheDir, "crop-transitions.csv").writeText(rows.joinToString("\n"))
            return "${if (rapid) 60 else 2} accessibility toggles; same surface/token/buffer; all key pages accessible; gap/width verified after three resizes. Compositor video requires separate inspection."
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }
}
