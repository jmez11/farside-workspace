package com.farside.workspace

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import metavrx.layout.window.compose.state.WindowState

/** Samples hosted frames throughout real key touches; this is not a Home compositor recording. */
internal object KeyboardTransitionVerification {
    fun verify(test: Instrumentation, activity: SessionActivity, state: WindowState, touch: (String) -> Unit) {
        fun view() = state.javaClass.methods.single { it.name.startsWith("getHostedView") }.invoke(state) as View
        fun token() = (state.javaClass.methods.single { it.name.startsWith("getToken") }.invoke(state) as StateFlow<*>).value
        val strip = view()
        val originalToken = token()
        val width = strip.width
        val height = strip.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val x = (60 * activity.resources.displayMetrics.density).toInt()
        val y = (20 * activity.resources.displayMetrics.density).toInt()
        var originalPixel = 0
        test.runOnMainSync { strip.draw(canvas); originalPixel = bitmap.getPixel(x, y) }
        val rows = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var sampling = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!sampling) return
                strip.draw(canvas)
                val pixel = bitmap.getPixel(x, y)
                rows += "$time,${strip.width},${strip.height},$pixel"
                if (view() !== strip || token() != originalToken || strip.width != width || strip.height != height || pixel != originalPixel)
                    errors += "Strip changed during bank transition at $time"
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        test.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        try {
            repeat(4) {
                touch("123")
                check(activity.keyboardBankState?.lifecycle?.value == metavrx.layout.window.compose.state.WindowLifecycle.Active) { "Bank did not reopen" }
                val openView = checkNotNull(activity.keyboardBankState).let { bank -> bank.javaClass.methods.single { it.name.startsWith("getHostedView") }.invoke(bank) as View }
                check((openView.layoutParams as android.view.WindowManager.LayoutParams).flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) { "Reopened bank cannot receive rays" }
                touch("ABC")
            }
        } finally {
            test.runOnMainSync { sampling = false; Choreographer.getInstance().removeFrameCallback(callback) }
            File(activity.cacheDir, "keyboard-transition-frames.csv").writeText("time,width,height,stripPixel\n" + rows.joinToString("\n"))
            bitmap.recycle()
        }
        check(rows.size >= 20) { "Too few transition frames sampled: ${rows.size}" }
        check(errors.isEmpty()) { errors.take(5).joinToString() }
        val bankView = checkNotNull(activity.keyboardBankState).let { bank -> bank.javaClass.methods.single { it.name.startsWith("getHostedView") }.invoke(bank) as View }
        val flags = (bankView.layoutParams as android.view.WindowManager.LayoutParams).flags
        check(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) { "Closed bank can intercept rays" }
        val token = checkNotNull((checkNotNull(activity.keyboardBankState).javaClass.methods.single { it.name.startsWith("getToken") }.invoke(activity.keyboardBankState) as StateFlow<*>).value)
        val id = token.javaClass.getMethod("getPersistableToken").invoke(token) as String
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("dumpsys volumetric_window")).bufferedReader().use { it.readText() }
        File(activity.cacheDir, "keyboard-native-windows.txt").writeText(dump)
        val bankRecord = dump.substringAfter("persistableToken = $id").substringBefore("interactionStructure:")
        // Home's unworn presenter does not acknowledge visibility/alpha changes to this
        // broker snapshot. Check the actual hosted content and input surface independently.
        val bankImage = Bitmap.createBitmap(bankView.width, bankView.height, Bitmap.Config.ARGB_8888)
        test.runOnMainSync {
            bankView.draw(Canvas(bankImage))
            val params = bankView.layoutParams as android.view.WindowManager.LayoutParams
            check(params.format == android.graphics.PixelFormat.TRANSLUCENT) { "Bank surface cannot display transparent pixels" }
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(bankView)
            val attrs = root.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }.get(root) as android.view.WindowManager.LayoutParams
            check(attrs.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) { "Native bank ViewRoot can intercept rays" }
        }
        val committed = java.util.concurrent.CountDownLatch(1)
        val commitCallback = Runnable { committed.countDown() }
        test.runOnMainSync { bankView.viewTreeObserver.registerFrameCommitCallback(commitCallback); bankView.invalidate() }
        try { check(committed.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "Transparent bank hardware frame was not submitted" } }
        finally { test.runOnMainSync { bankView.viewTreeObserver.unregisterFrameCommitCallback(commitCallback) } }
        val pixels = IntArray(bankImage.width * bankImage.height)
        bankImage.getPixels(pixels, 0, bankImage.width, 0, 0, bankImage.width, bankImage.height)
        File(activity.cacheDir, "keyboard-bank-hidden.png").outputStream().use { bankImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bankImage.recycle()
        check(pixels.all { android.graphics.Color.alpha(it) == 0 }) { "Collapsed bank still paints an opaque pixel" }
        File(activity.cacheDir, "keyboard-transition-results.txt").writeText("PASS: ${rows.size} stable strip frames; bank transparent at every pixel; native ViewRoot NOT_TOUCHABLE; translucent surface; transparent hardware frame submitted.\nHome broker snapshot (unworn, compositor confirmation remains manual):\n$bankRecord")
    }
    fun focus(test: Instrumentation, state: WindowState) {
        val host = checkNotNull(state.javaClass.methods.single { it.name.startsWith("getSurfaceControlViewHost") }.invoke(state))
        val token = host.javaClass.getMethod("getFocusGrantToken").invoke(host)
        val session = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowSession").invoke(null)
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW")
        try {
            session.javaClass.getMethod("grantEmbeddedWindowFocus", Class.forName("android.view.IWindow"), android.os.IBinder::class.java,
                Boolean::class.javaPrimitiveType).invoke(session, null, token, true)
        } finally { test.uiAutomation.dropShellPermissionIdentity() }
        Thread.sleep(150)
    }
}
