package com.farside.workspace

import android.app.Activity
import kotlin.math.abs

internal data class RdpPanelPoint(val x: Float, val y: Float)

/** Approximate rows, with touching logical edges rather than physical air gaps. */
internal object RdpSpatialLayout {
    fun arrange(monitors: List<RdpMonitorRect>, supplied: Map<Long, RdpPanelPoint>): List<RdpMonitorRect> {
        if (monitors.isEmpty() || monitors.none { it.id in supplied }) return RdpMonitorLayouts.row(monitors)
        val points = supplied.filterKeys { id -> monitors.any { it.id == id } }.toMutableMap()
        var nextX = points.values.maxOf { it.x } + 1f
        val rowY = points.values.maxOf { it.y }
        for (m in monitors) if (m.id !in points) { points[m.id] = RdpPanelPoint(nextX++,rowY) }
        val bands = mutableListOf<MutableList<RdpMonitorRect>>()
        for (m in monitors.sortedByDescending { points.getValue(it.id).y }) {
            val band = bands.lastOrNull()
            if (band == null || abs(points.getValue(band.first().id).y - points.getValue(m.id).y) > .25f)
                bands += mutableListOf(m)
            else band += m
        }
        val placed = mutableMapOf<Long, RdpMonitorRect>()
        var y = 0
        for (band in bands) {
            var x = 0
            for (m in band.sortedWith(compareBy<RdpMonitorRect> { points.getValue(it.id).x }.thenBy { it.id })) {
                val r = RdpMonitorLayouts.row(listOf(m)).single().copy(x = x, y = y)
                placed[m.id] = r; x += r.width
            }
            y += band.maxOf { placed.getValue(it.id).height }
        }
        return monitors.map { placed.getValue(it.id) }
    }
}

/** Own-window pose only. Never adopt shell/global window permission in production. */
internal data class RdpPanelPose(val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float, val parent: Any?) {
    fun project(other: RdpPanelPose): RdpPanelPoint {
        val dx = other.x - x; val dy = other.y - y; val dz = other.z - z
        // Dot with this panel's right/up axes, so a turned panel group still works.
        return RdpPanelPoint(dx * (1 - 2 * (qy*qy + qz*qz)) + dy * (2*(qx*qy + qw*qz)) + dz * (2*(qx*qz - qw*qy)),
            dx * (2*(qx*qy - qw*qz)) + dy * (1 - 2*(qx*qx + qz*qz)) + dz * (2*(qy*qz + qw*qx)))
    }
    companion object {
        fun read(activity: Activity): RdpPanelPose? {
            for (ns in listOf("horizonos", "metavr")) try {
                val cls = Class.forName("$ns.app.volumetricwindow.VolumetricWindowManager")
                val manager = activity.getSystemService(cls) ?: continue
                val token = Class.forName("$ns.app.ActivityExt").getMethod("getVolumetricWindowToken", Activity::class.java).invoke(null, activity) ?: continue
                val info = cls.getMethod("getWindowInfo", Class.forName("$ns.view.VolumetricWindowToken")).invoke(manager, token) ?: continue
                val pose = info.javaClass.getMethod("getPose").invoke(info) ?: continue
                val pos = pose.javaClass.getMethod("getPosition").invoke(pose) ?: continue
                val rot = pose.javaClass.getMethod("getOrientation").invoke(pose) ?: continue
                fun c(o: Any, n: String) = (o.javaClass.getMethod(n).invoke(o) as Number).toFloat()
                val result = RdpPanelPose(c(pos,"getX"), c(pos,"getY"), c(pos,"getZ"), c(rot,"getX"), c(rot,"getY"), c(rot,"getZ"), c(rot,"getW"),
                    info.javaClass.getMethod("getParentToken").invoke(info))
                if (listOf(result.x,result.y,result.z,result.qx,result.qy,result.qz,result.qw).all { it.isFinite() }) return result
            } catch (_: ReflectiveOperationException) { } catch (_: SecurityException) { }
            return null
        }
    }
}
