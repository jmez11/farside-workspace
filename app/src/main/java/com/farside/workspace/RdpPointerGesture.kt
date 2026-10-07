package com.farside.workspace

import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Wait half a second before drawing the one-second clockwise hold indicator. */
internal fun rightClickBoxProgress(elapsedMillis: Long): Float =
    ((elapsedMillis - 500L) / 1000f).coerceIn(0f, 1f)

/**
 * Invert Compose graphicsLayer scale and pan to map raw screen pointer events
 * back to the underlying remote desktop pixel coordinates.
 */
internal fun mapScreenToDesktop(
    eventX: Float,
    eventY: Float,
    viewWidth: Float,
    viewHeight: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    actualWidth: Int,
    actualHeight: Int
): Pair<Int, Int> {
    val w = viewWidth.coerceAtLeast(1f)
    val h = viewHeight.coerceAtLeast(1f)
    val z = zoom.coerceAtLeast(0.01f)
    val tx = (eventX - panX - w / 2f) / z + w / 2f
    val ty = (eventY - panY - h / 2f) / z + h / 2f
    val rdpX = (tx * actualWidth / w).toInt().coerceIn(0, actualWidth - 1)
    val rdpY = (ty * actualHeight / h).toInt().coerceIn(0, actualHeight - 1)
    return rdpX to rdpY
}

/** Horizon delivers controller trigger and hand pinch as Android touch down/up. */
internal class RdpPointerGesture(
    var touchSlop: Float,
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
    private var previousTapTime = -1L
    private var previousTapX = 0f
    private var previousTapY = 0f

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (active) cancel()
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
                    previousTapTime = -1L
                    dragging = true
                    showHold(null, 0L)
                    send(startX, startY, button or FreeRDPBridge.PTR_FLAGS_DOWN)
                }
                if (dragging) send(event.x, event.y, FreeRDPBridge.PTR_FLAGS_MOVE)
            }
            MotionEvent.ACTION_UP -> if (active) {
                showHold(null, 0L)
                if (dragging) {
                    previousTapTime = -1L
                    send(event.x, event.y, button)
                }
                else {
                    val click = if (event.eventTime - started >= 1000L)
                        FreeRDPBridge.PTR_FLAGS_BUTTON2 else FreeRDPBridge.PTR_FLAGS_BUTTON1
                    val nearbySecondTap = click == FreeRDPBridge.PTR_FLAGS_BUTTON1 && previousTapTime >= 0L &&
                        event.eventTime - previousTapTime in 0L..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                        hypot(startX - previousTapX, startY - previousTapY) <= touchSlop * 2f
                    val clickX = if (nearbySecondTap) previousTapX else startX
                    val clickY = if (nearbySecondTap) previousTapY else startY
                    send(clickX, clickY, click or FreeRDPBridge.PTR_FLAGS_DOWN)
                    send(clickX, clickY, click)
                    previousTapTime = if (click == FreeRDPBridge.PTR_FLAGS_BUTTON1 && !nearbySecondTap) event.eventTime else -1L
                    previousTapX = clickX
                    previousTapY = clickY
                }
                active = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
    }

    fun cancel() {
        previousTapTime = -1L
        if (active && dragging) send(lastX, lastY, button)
        active = false
        dragging = false
        showHold(null, 0L)
    }
}
