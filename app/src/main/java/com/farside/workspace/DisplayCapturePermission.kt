package com.farside.workspace

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One consent decision per RDP session. Native waits on its channel thread, never the UI. */
internal class DisplayCapturePermission(private val activity: ComponentActivity) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    private var asked = false
    private var denied = false
    private var closed = false
    private var token: Intent? = null
    private var pending: Request? = null
    private var bound = false
    private var foreground = false
    internal var displayCreations = 0
        private set
    internal var consentRequests = 0
        private set
    internal val streamRequests = java.util.concurrent.atomic.AtomicInteger(0)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || denied) { releaseSession(); return }
            try {
                (binder as DisplayCaptureService.CaptureBinder).promote()
                foreground = true
                pending?.let { open(it) }
            } catch (error: Exception) {
                Log.w(TAG, "Cannot start projection foreground service", error)
                denied = true
                pending?.let { finish(it, false) }
                releaseSession()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { close() }
        override fun onBindingDied(name: ComponentName) { close() }
        override fun onNullBinding(name: ComponentName) { close() }
    }

    private class Request(val surface: Surface?) {
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        @Volatile var granted = false
        fun complete(success: Boolean) {
            if (finished.compareAndSet(false, true)) { granted = success; done.countDown() }
        }
    }

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        if (closed || denied || request == null || request.finished.get()) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            denied = true
            Log.i(TAG, "Display consent denied; no further prompts in this session")
            finish(request, false)
        } else {
            token = result.data
            try {
                bound = activity.applicationContext.bindService(Intent(activity, DisplayCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) { denied = true; finish(request, false) }
            } catch (error: Exception) {
                Log.w(TAG, "Cannot bind projection foreground service", error)
                denied = true
                finish(request, false)
            }
        }
    }

    fun start(next: Surface?): Boolean {
        streamRequests.incrementAndGet()
        check(Looper.myLooper() != Looper.getMainLooper()) { "Display start must run on the channel thread" }
        val request = Request(next)
        main.post {
            if (closed || denied || pending != null || request.finished.get()) { finish(request, false); return@post }
            pending = request
            when {
                token != null -> open(request)
                asked -> finish(request, false)
                else -> {
                    asked = true
                    consentRequests++
                    Log.i(TAG, "Requesting display consent")
                    try { launcher.launch(manager.createScreenCaptureIntent()) }
                    catch (error: Exception) { Log.w(TAG, "Cannot launch display consent", error); denied = true; finish(request, false) }
                }
            }
        }
        try {
            if (!request.done.await(30, TimeUnit.SECONDS)) {
                request.complete(false)
                main.post { denied = true; if (pending === request) pending = null; releaseSession() }
                Log.w(TAG, "Display consent timed out after 30 seconds")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            request.complete(false)
            main.post { denied = true; if (pending === request) pending = null; releaseSession() }
        }
        return request.granted
    }

    private fun open(request: Request) {
        if (request.finished.get()) { finish(request, false); return }
        try {
            check(foreground) { "Projection foreground service is not ready" }
            Hub.granted.add(this)
            Hub.active.add(this)
            request.surface?.let { next ->
                if (Hub.projection == null) {
                    val capture = manager.getMediaProjection(Activity.RESULT_OK, checkNotNull(token))
                    Hub.projection = capture
                    capture.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (Hub.projection !== capture) return
                            Hub.projection = null
                            val owners = Hub.granted.toList()
                            Hub.release()
                            owners.forEach { owner ->
                                owner.denied = true
                                owner.token = null
                                owner.pending?.let { owner.finish(it, false) }
                                owner.releaseSession()
                            }
                        }
                    }, main)
                    Hub.display = capture.createVirtualDisplay("Farside Headset Display", 1280, 720,
                        activity.resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        next, null, main)
                    displayCreations++
                } else Hub.display?.surface = next
                Hub.surface?.release()
                Hub.surface = next
                Log.i(TAG, "Display producer attached: 1280x720")
            }
            finish(request, true)
        } catch (error: Exception) {
            Log.w(TAG, "Display capture unavailable", error)
            denied = true
            releaseSession()
            finish(request, false)
        }
    }

    private fun finish(request: Request, success: Boolean) {
        request.complete(success)
        if (pending === request) pending = null
        if (!request.granted) request.surface?.release()
    }

    // Drain stop on the main thread before native deletes the reader's window.
    fun stopStream() {
        if (Looper.myLooper() == Looper.getMainLooper()) { releaseStream(); return }
        val done = CountDownLatch(1)
        main.post { try { releaseStream() } finally { done.countDown() } }
        try { done.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun releaseStream() {
        Hub.active.remove(this)
        if (Hub.active.isEmpty()) Hub.detach()
    }

    private fun releaseSession() {
        releaseStream()
        Hub.granted.remove(this)
        if (Hub.granted.isEmpty()) Hub.release()
        token = null
        foreground = false
        if (bound) { bound = false; activity.applicationContext.unbindService(connection) }
    }

    /** Horizon has one compositor producer. Consent decisions remain per session. */
    private object Hub {
        val granted = mutableSetOf<DisplayCapturePermission>()
        val active = mutableSetOf<DisplayCapturePermission>()
        var projection: MediaProjection? = null
        var display: VirtualDisplay? = null
        var surface: Surface? = null
        fun detach() { display?.surface = null; surface?.release(); surface = null }
        fun release() {
            detach(); display?.release(); display = null
            val previous = projection; projection = null
            previous?.stop()
            granted.clear(); active.clear()
        }
    }

    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { close() }; return }
        closed = true
        pending?.let { finish(it, false) }
        releaseSession()
    }

    private companion object { const val TAG = "DisplayCapture" }
}

@androidx.annotation.Keep
internal object QuestDisplayVerification {
    @JvmStatic external fun verifyConversion(): Boolean
}
