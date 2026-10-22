package com.farside.workspace

import kotlin.math.abs

/** Apple's discrete wheel ticks need a larger floor than RDP wheel rotations. */
internal object MacJoystickScroll {
    fun steps(value:Float):Int {
        if(!value.isFinite() || abs(value)<=0.2f) return 0
        val deflection=((abs(value)-0.2f)/0.8f).coerceIn(0f,1f)
        return 10*(6+(10f*deflection*deflection).toInt())
    }
    fun delayMs(x:Float,y:Float):Long {
        val magnitude=kotlin.math.hypot(x.toDouble(),y.toDouble()).toFloat().coerceIn(0f,1f)
        val deflection=((magnitude-0.2f)/0.8f).coerceIn(0f,1f)
        return (80f-60f*deflection).toLong().coerceIn(20L,80L)
    }
}
