package com.multidesk.explorer

import android.view.MotionEvent
import kotlin.math.hypot

/** Horizon delivers controller trigger and hand pinch as Android touch down/up. */
internal class RdpPointerGesture(
    private val touchSlop: Float,
    private val send: (Float, Float, Int) -> Unit,
    private val showHold: (Pair<Float, Float>?, Long) -> Unit
) {
    private var active = false
    private var dragging = false
    private var button = FreeRDPBridge.PTR_FLAGS_BUTTON1
    private var startX = 0f
    private var startY = 0f
    private var started = 0L
    private var lastX = 0f
    private var lastY = 0f

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                active = true
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                started = event.eventTime
                button = if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0)
                    FreeRDPBridge.PTR_FLAGS_BUTTON2 else FreeRDPBridge.PTR_FLAGS_BUTTON1
                // Mouse buttons retain immediate down/up; touch waits to distinguish hold from tap.
                dragging = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || button == FreeRDPBridge.PTR_FLAGS_BUTTON2
                if (dragging) send(startX, startY, button or FreeRDPBridge.PTR_FLAGS_DOWN)
                else showHold(startX to startY, started)
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                lastX = event.x
                lastY = event.y
                if (!dragging && hypot(event.x - startX, event.y - startY) > touchSlop) {
                    dragging = true
                    showHold(null, 0L)
                    send(startX, startY, button or FreeRDPBridge.PTR_FLAGS_DOWN)
                }
                if (dragging) send(event.x, event.y, FreeRDPBridge.PTR_FLAGS_MOVE)
            }
            MotionEvent.ACTION_UP -> if (active) {
                showHold(null, 0L)
                if (dragging) send(event.x, event.y, button)
                else {
                    val click = if (event.eventTime - started >= 1000L)
                        FreeRDPBridge.PTR_FLAGS_BUTTON2 else FreeRDPBridge.PTR_FLAGS_BUTTON1
                    send(startX, startY, click or FreeRDPBridge.PTR_FLAGS_DOWN)
                    send(startX, startY, click)
                }
                active = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
    }

    fun cancel() {
        if (active && dragging) send(lastX, lastY, button)
        active = false
        dragging = false
        showHold(null, 0L)
    }
}
