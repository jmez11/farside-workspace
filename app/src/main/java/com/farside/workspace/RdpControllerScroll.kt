package com.farside.workspace

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/** Wheel input only: never changes the remote pointer or keyboard focus. */
internal class RdpControllerScroll(private val send: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var hScrollAccum = 0f
    private var vScrollAccum = 0f
    private val repeat = object : Runnable {
        override fun run() {
            emit(x, y)
            val magnitude = kotlin.math.hypot(x.toDouble(), y.toDouble()).toFloat().coerceIn(0f, 1f)
            val defl = ((magnitude - 0.2f) / 0.8f).coerceIn(0f, 1f)
            val delay = (800f - (725f * defl)).toLong().coerceIn(75L, 800L)
            handler.postDelayed(this, delay)
        }
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (h.isFinite() && h != 0f) {
                hScrollAccum += h
                while (abs(hScrollAccum) >= 1.0f) {
                    val dir = if (hScrollAccum > 0) 1 else -1
                    send(FreeRDPBridge.PTR_FLAGS_HWHEEL or ((if (dir > 0) 120 else -120) and 0x01ff))
                    hScrollAccum -= dir
                }
            }
            if (v.isFinite() && v != 0f) {
                vScrollAccum += v
                while (abs(vScrollAccum) >= 1.0f) {
                    val dir = if (vScrollAccum > 0) 1 else -1
                    send(FreeRDPBridge.PTR_FLAGS_WHEEL or ((if (dir > 0) 120 else -120) and 0x01ff))
                    vScrollAccum -= dir
                }
            }
            return true
        }
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        fun axis(id: Int): Float {
            val value = event.getAxisValue(id)
            val flat = maxOf(0.2f, event.device?.getMotionRange(id, event.source)?.flat ?: 0f)
            return if (value.isFinite() && abs(value) > flat) value.coerceIn(-1f, 1f) else 0f
        }
        val wasActive = x != 0f || y != 0f
        x = axis(MotionEvent.AXIS_X)
        y = -axis(MotionEvent.AXIS_Y) // Android Y is positive down; wheel is positive up.
        if (x == 0f && y == 0f) handler.removeCallbacks(repeat)
        else if (!wasActive) repeat.run()
        return true
    }

    private fun emit(horizontal: Float, vertical: Float) {
        fun wheel(value: Float, flag: Int) {
            if (!value.isFinite() || value == 0f) return
            // RDP carries a signed nine-bit rotation, including the negative bit.
            val delta = if (value > 0) 120 else -120
            send(flag or (delta and 0x01ff))
        }
        wheel(horizontal, FreeRDPBridge.PTR_FLAGS_HWHEEL)
        wheel(vertical, FreeRDPBridge.PTR_FLAGS_WHEEL)
    }

    fun stop() {
        x = 0f
        y = 0f
        hScrollAccum = 0f
        vScrollAccum = 0f
        handler.removeCallbacks(repeat)
    }
}
