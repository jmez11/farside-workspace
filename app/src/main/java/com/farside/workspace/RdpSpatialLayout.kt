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
        val placed = mutableMapOf<Long,RdpMonitorRect>()
        var y = 0
        var previous = emptyList<RdpMonitorRect>()
        for (band in bands) {
            val local = RdpMonitorLayouts.row(band.sortedWith(
                compareBy<RdpMonitorRect> { points.getValue(it.id).x }.thenBy { it.id }))
            // Align the closest horizontal pair across adjacent rows. Packing every
            // row from x=0 incorrectly moved a panel above the right desktop to the
            // far left of the host layout. Use a full-height upper panel so the rows
            // share a real edge even when monitor heights differ.
            val pair = previous.filter { it.y + it.height == y }.flatMap { upper ->
                local.map { lower -> upper to lower }
            }.minByOrNull { (upper,lower) -> abs(points.getValue(upper.id).x-points.getValue(lower.id).x) }
            val start = pair?.let { (upper,lower) ->
                val delta = points.getValue(lower.id).x-points.getValue(upper.id).x
                val shift = if (abs(delta) <= .25f) 0 else
                    (kotlin.math.sign(delta)*minOf(upper.width,lower.width)*.75f).toInt()
                upper.x + (upper.width-lower.width)/2 + shift - lower.x
            } ?: 0
            // Windows can silently reject a pair meeting only at one corner.
            // Slightly stagger rows so every contact is an edge with nonzero span.
            val offsets = (0..monitors.size*2).flatMap { if (it == 0) listOf(0) else listOf(it*2,-it*2) }
            val offset = offsets.firstOrNull { n ->
                val candidate = local.map { it.copy(x=it.x+start+n,y=y) }
                val connected = pair?.let { (upper,lower) ->
                    val next = candidate.first { it.id == lower.id }
                    next.x < upper.x+upper.width && upper.x < next.x+next.width
                } ?: true
                connected && candidate.none { lower -> placed.values.any { upper ->
                    (lower.x == upper.x+upper.width || upper.x == lower.x+lower.width) &&
                        (lower.y == upper.y+upper.height || upper.y == lower.y+lower.height)
                } }
            } ?: 0
            previous = local.map { it.copy(x=it.x+start+offset,y=y) }
            previous.forEach { placed[it.id] = it }
            y += previous.maxOf { it.height }
        }
        val left = placed.values.minOf { it.x }
        return monitors.map { placed.getValue(it.id).let { r -> r.copy(x=r.x-left) } }

    }
}

/** Own-window pose only. Never adopt shell/global window permission in production. */
internal data class RdpPanelPose(val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float, val parent: Any?, val visible: Boolean? = null) {
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
                    info.javaClass.getMethod("getParentToken").invoke(info), info.javaClass.getMethod("isVisible").invoke(info) as? Boolean)
                if (listOf(result.x,result.y,result.z,result.qx,result.qy,result.qz,result.qw).all { it.isFinite() }) return result
            } catch (_: ReflectiveOperationException) { } catch (_: SecurityException) { }
            return null
        }
    }
}

/** A minimization animation can move a panel before its visible flag changes. Only
 * settled visible poses replace its room placement; hidden tray poses never do. */
internal class RdpPanelPoseTracker {
    private data class Candidate(val pose: RdpPanelPose, val since: Long)
    private val candidates = mutableMapOf<Long,Candidate>()
    private val settled = mutableMapOf<Long,RdpPanelPose>()
    fun retain(ids: Set<Long>) { candidates.keys.retainAll(ids); settled.keys.retainAll(ids) }
    fun update(id: Long, pose: RdpPanelPose?, shown: Boolean, now: Long) {
        if (pose == null || pose.visible == false || !shown) { candidates.remove(id); return }
        val previous = candidates[id]
        val changed = previous == null || previous.pose.parent != pose.parent ||
            kotlin.math.sqrt((pose.x-previous.pose.x)*(pose.x-previous.pose.x)+(pose.y-previous.pose.y)*(pose.y-previous.pose.y)+
                (pose.z-previous.pose.z)*(pose.z-previous.pose.z)) > .025f ||
            kotlin.math.abs(pose.qx*previous.pose.qx+pose.qy*previous.pose.qy+pose.qz*previous.pose.qz+pose.qw*previous.pose.qw) < .9999f
        if (changed) candidates[id] = Candidate(pose,now)
        if (!changed && now-checkNotNull(previous).since >= 750) settled[id] = pose
    }
    fun poses(ids: List<Long>): List<RdpPanelPose>? = ids.map { settled[it] ?: return null }
}
