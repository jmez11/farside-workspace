package com.farside.workspace

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.pow

internal class SshControllerScroll(private val scroll: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var axis = 0f
    private var remainder = 0f
    private var wheelRemainder = 0f
    private var lastTime = 0L
    private val repeat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - lastTime).coerceIn(0L, 100L) / 1000f
            lastTime = now
            val strength = ((abs(axis) - 0.2f) / 0.8f).coerceIn(0f, 1f)
            val rate = 2f + 78f * strength.pow(2)
            remainder += (if (axis > 0) 1 else -1) * rate * elapsed
            val rows = remainder.toInt()
            if (rows != 0) { scroll(rows); remainder -= rows }
            handler.postDelayed(this, 16)
        }
    }
    fun onMotion(event: MotionEvent): Boolean {
        // Horizon usually turns controller thumbstick input into ordinary wheel events.
        // Android has already routed these to the receiving session window.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val value = event.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (value.isFinite()) {
                wheelRemainder -= value * 3f
                val rows = wheelRemainder.toInt()
                if (rows != 0) { scroll(rows); wheelRemainder -= rows }
            }
            return true
        }
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        // Quest exposes the right stick as RY; conventional controllers use Y or HAT_Y.
        val next = listOf(MotionEvent.AXIS_Y, MotionEvent.AXIS_RY, MotionEvent.AXIS_HAT_Y).map { id ->
            val value = event.getAxisValue(id)
            val deadzone = maxOf(0.2f, event.device?.getMotionRange(id, event.source)?.flat ?: 0f)
            if (value.isFinite() && abs(value) > deadzone) value.coerceIn(-1f, 1f) else 0f
        }.maxBy { abs(it) }
        if (next == 0f) stop()
        else {
            if (axis == 0f || axis * next < 0f) {
                stop(); axis = next; lastTime = SystemClock.uptimeMillis(); repeat.run()
            } else axis = next
        }
        return true
    }
    fun stop() { axis = 0f; remainder = 0f; wheelRemainder = 0f; handler.removeCallbacks(repeat) }
}
