package com.farside.workspace

import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.DragEvent
import android.view.View
import androidx.compose.runtime.*
import java.util.UUID

/** One held remote mouse button, transported across Android document windows. Main thread only. */
internal class RdpSessionDrag(private val session: RdpMonitorSession,
    private val startNative: (View, ClipData) -> Boolean = { view,clip ->
        view.startDragAndDrop(clip, object : View.DragShadowBuilder(view) {
            override fun onProvideShadowMetrics(size: Point, touch: Point) { size.set(16,16); touch.set(8,8) }
            override fun onDrawShadow(canvas: Canvas) { canvas.drawCircle(8f,8f,5f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }) }
        }, null, View.DRAG_FLAG_GLOBAL)
    }) {
    companion object { const val MIME = "application/vnd.farside.rdp-pointer" }
    var active by mutableStateOf(false)
        private set
    var armed by mutableStateOf(false)
        private set
    private var clickCarry by mutableStateOf(false)
    val carrying get() = active && clickCarry
    private var swallowRelease: Long? = null
    private var source: RdpMonitor? = null
    fun toggleCarry() {
        if (armed || active) cancel() else if (session.inputReady && !session.busy) armed = true
    }
    /** Explicit two-click carry uses fresh Home gestures on each panel. */
    fun pointer(monitor: RdpMonitor, localX: Int, localY: Int, flags: Int): Boolean {
        if (monitor !in session.monitors) return false
        val down = flags and FreeRDPBridge.PTR_FLAGS_DOWN != 0
        val primary = flags and FreeRDPBridge.PTR_FLAGS_BUTTON1 != 0
        if (primary && down) swallowRelease=null
        if (swallowRelease == monitor.id && primary && !down) { swallowRelease=null; return true }
        if (!armed && !carrying) return false
        if (!session.acceptsInput(monitor)) return true
        if (flags and FreeRDPBridge.PTR_FLAGS_BUTTON2 != 0) { cancel(); return true }
        if (armed && primary && down) {
            armed=false; clickCarry=true; active=true; source=monitor
            val p=RdpMonitorLayouts.pointer(monitor.rect,localX,localY); x=p.first; y=p.second
            send(FreeRDPBridge.PTR_FLAGS_MOVE)
            send(FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN)
            arm(); Log.i("RdpDrag","Picked up content monitor=${monitor.id}")
            return true
        }
        if (carrying) {
            if (primary && down) {
                move(monitor,localX,localY)
                cancel(false); swallowRelease=monitor.id
                Log.i("RdpDrag","Dropped content monitor=${monitor.id}")
            } else if (!primary) move(monitor,localX,localY)
            return true
        }
        return false
    }
    private var token = ""
    val position get() = x to y
    private var x = 0
    private var y = 0
    private val main = Handler(Looper.getMainLooper())
    private val expiry = Runnable { cancel() }
    private fun arm() { main.removeCallbacks(expiry); main.postDelayed(expiry, 60_000) }
    private fun send(flags: Int) {
        val handle = session.instance.value
        if (handle != 0L) session.bridge.sendMouseEvent(handle, x, y, flags)
    }
    fun start(view: View, monitor: RdpMonitor, localX: Int, localY: Int): Boolean {
        if (armed || active || !session.inputReady || session.busy || session.monitors.size < 2 || monitor !in session.monitors) return false
        source = monitor; token = UUID.randomUUID().toString(); active = true
        val p = RdpMonitorLayouts.pointer(monitor.rect, localX, localY); x = p.first; y = p.second
        val clip = ClipData(token, arrayOf(MIME), ClipData.Item(token))
        val started = try { startNative(view,clip) } catch (error: RuntimeException) {
            Log.w("RdpDrag", "Cross-window drag unavailable", error); false
        }
        if (!started) { active = false; source = null; return false }
        // Mouse already sent DOWN; controller/pinch begins its remote drag here.
        val alreadyDown = monitor.takePrimaryButton()
        if (!alreadyDown) send(FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN)
        arm(); Log.i("RdpDrag", "Started session drag monitor=${monitor.id}")
        return true
    }
    fun accepts(monitor: RdpMonitor, mime: Boolean, label: String?) =
        active && monitor in session.monitors && mime && label == token
    fun move(monitor: RdpMonitor, localX: Int, localY: Int) {
        if (!active || monitor !in session.monitors || !session.inputReady) return
        val p = RdpMonitorLayouts.pointer(monitor.rect, localX, localY); x = p.first; y = p.second
        send(FreeRDPBridge.PTR_FLAGS_MOVE); arm()
    }
    fun cancelFor(monitor: RdpMonitor) { if (armed || source === monitor) cancel() }
    fun cancel(cancelNative: Boolean = true) {
        armed=false; swallowRelease=null
        if (!active) return
        send(FreeRDPBridge.PTR_FLAGS_BUTTON1)
        val previous = source
        val native = !clickCarry
        active = false; clickCarry=false; source = null; swallowRelease=null; main.removeCallbacks(expiry)
        if (cancelNative && native) runCatching { previous?.host?.get()?.window?.decorView?.cancelDragAndDrop() }
        Log.i("RdpDrag", "Released session drag")
    }
    fun event(monitor: RdpMonitor, event: DragEvent, point: (Float, Float) -> Pair<Int,Int>): Boolean {
        if (carrying) return false
        Log.d("RdpDrag","Native event monitor=${monitor.id} action=${event.action} active=$active")
        if (event.action == DragEvent.ACTION_DRAG_STARTED)
            return accepts(monitor, event.clipDescription?.hasMimeType(MIME) == true, event.clipDescription?.label?.toString())
        if (!active || monitor !in session.monitors) return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DROP -> {
                val p = point(event.x,event.y); move(monitor,p.first,p.second)
                if (event.action == DragEvent.ACTION_DROP) cancel(false)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> { if (source === monitor) cancel(false); true }
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_EXITED -> true
            else -> false
        }
    }
}
