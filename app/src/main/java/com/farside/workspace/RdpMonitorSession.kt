package com.farside.workspace

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.widget.Toast
import androidx.compose.runtime.*
import com.farside.workspace.data.ConnectionProfile
import java.lang.ref.WeakReference
import java.util.UUID

/** A connection is independent of its document Activities and their transient surfaces. Main thread only. */
internal object RdpMonitorSessions {
    const val EXTRA_SESSION = "rdpMonitorSession"
    const val EXTRA_MONITOR = "rdpMonitorId"
    private val sessions = mutableMapOf<String, RdpMonitorSession>()
    fun create(context: Context, profile: ConnectionProfile): RdpMonitor =
        RdpMonitorSession(context.applicationContext, profile).also { sessions[it.id] = it }.first()
    fun find(session: String, monitor: Long): RdpMonitor? = sessions[session]?.monitors?.firstOrNull { it.id == monitor }
    fun remove(session: RdpMonitorSession) { sessions.remove(session.id) }
}

internal data class RdpMonitorRect(val id: Long, val x: Int, val y: Int, val width: Int, val height: Int)

internal object RdpMonitorLayouts {
    const val MAX_PIXELS = 32L * 1024 * 1024
    fun row(monitors: List<RdpMonitorRect>): List<RdpMonitorRect> {
        var x = 0
        return monitors.map { m -> m.copy(x = x, y = 0,
            width = (m.width.coerceIn(200, 8192) and -2), height = m.height.coerceIn(200, 8192)).also { x += it.width } }
    }
    private fun touches(a: RdpMonitorRect, b: RdpMonitorRect): Boolean =
        ((a.x + a.width == b.x || b.x + b.width == a.x) && maxOf(a.y,b.y) <= minOf(a.y+a.height,b.y+b.height)) ||
        ((a.y + a.height == b.y || b.y + b.height == a.y) && maxOf(a.x,b.x) <= minOf(a.x+a.width,b.x+b.width))
    private fun connectedOrder(row: List<RdpMonitorRect>): List<RdpMonitorRect> {
        if (row.isEmpty()) return row
        val order = mutableListOf(row.first()); val remaining = row.drop(1).toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { candidate -> order.any { touches(it,candidate) } } ?: break
            order += next; remaining.remove(next)
        }
        return order
    }
    fun wire(row: List<RdpMonitorRect>) = row.flatMapIndexed { i,m ->
        listOf(m.x-row.first().x,m.y-row.first().y,m.width,m.height,if (i == 0) 1 else 0) }.toIntArray()
    fun normalized(raw: LongArray): LongArray {
        val count = raw.getOrNull(9)?.toInt() ?: return raw
        if (count !in 1..16 || raw.size < 10 + count * 5) return raw
        val x = (0 until count).minOf { raw[10 + it * 5] }; val y = (0 until count).minOf { raw[11 + it * 5] }
        return raw.copyOf().also { out -> repeat(count) { val p = 10 + it * 5
            out[p] -= x; out[p+2] -= x; out[p+1] -= y; out[p+3] -= y } }
    }
    fun valid(row: List<RdpMonitorRect>, maximum: Int, areaA: Long, areaB: Long): Boolean {
        if (row.isEmpty() || row.size > minOf(maximum, 16) || maximum <= 0 || areaA <= 0 || areaB <= 0) return false
        val budget = runCatching { Math.multiplyExact(Math.multiplyExact(maximum.toLong(), areaA), areaB) }.getOrNull() ?: return false
        val width = row.maxOf { it.x.toLong() + it.width }; val height = row.maxOf { it.y.toLong() + it.height }
        return row.minOf { it.x } == 0 && row.minOf { it.y } == 0 && width <= 32766 && height <= 32766 &&
            row.all { it.width in 200..8192 && it.width % 2 == 0 && it.height in 200..8192 } &&
            row.withIndex().all { (i, a) -> row.drop(i + 1).none { b -> a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height } } &&
            connectedOrder(row).size == row.size && width * height <= MAX_PIXELS && row.sumOf { it.width.toLong() * it.height } <= minOf(budget, MAX_PIXELS)
    }
    fun pointer(rect: RdpMonitorRect, x: Int, y: Int) =
        (rect.x + x.coerceIn(0, rect.width - 1)) to (rect.y + y.coerceIn(0, rect.height - 1))
    fun matches(raw: LongArray, row: List<RdpMonitorRect>): Boolean = row.isNotEmpty() && raw.size >= 10 + row.size * 5 && raw[9] == row.size.toLong() &&
        raw[4] == row.maxOf { it.x.toLong()+it.width } && raw[5] == row.maxOf { it.y.toLong()+it.height } &&
        row.withIndex().all { (i, m) -> (row.indices).any { j -> val p = 10 + j * 5
            raw[p] == m.x.toLong() && raw[p + 1] == m.y.toLong() && raw[p + 2] == (m.x + m.width - 1).toLong() &&
                raw[p + 3] == (m.y + m.height - 1).toLong() && (raw[p + 4].toInt() and 1 != 0) == (i == 0) } }

}

internal class RdpMonitor(val session: RdpMonitorSession, val id: Long) : DesktopSessionConnection {
    var rect by mutableStateOf(RdpMonitorRect(id, 0, 0, 1920, 1080))
    var requestedWidth = 1920
    var requestedHeight = 1080
    private val heldKeys = mutableSetOf<Int>()
    private val heldUnicode = mutableSetOf<Int>()
    internal val hasButtons get() = buttons != 0
    private var buttons = 0
    internal fun takePrimaryButton(): Boolean {
        val down = buttons and 0x1000 != 0
        buttons = buttons and 0x1000.inv()
        return down
    }
    private var pointer = 0 to 0
    fun releaseInput(mouseOnly: Boolean = false) {
        val handle = session.instance.value
        if (handle != 0L) {
            for (flag in listOf(0x1000, 0x2000, 0x4000)) if (buttons and flag != 0)
                session.bridge.sendMouseEvent(handle, pointer.first, pointer.second, flag)
            if (!mouseOnly) {
                heldKeys.toList().forEach { bridge.sendKeyEvent(handle, it, false) }
                heldUnicode.toList().forEach { bridge.sendUnicodeKeyEvent(handle, it, false) }
            }
        }
        buttons = 0
        if (!mouseOnly) { heldKeys.clear(); heldUnicode.clear() }
    }
    private fun key(code: Int, down: Boolean, unicode: Boolean) {
        val handle = session.instance.value
        if (handle == 0L || down && !session.inputReady) return
        val held = if (unicode) heldUnicode else heldKeys
        val wasHeld = code in held
        if (down) held.add(code) else held.remove(code)
        val heldElsewhere = session.monitors.any { it !== this && code in (if (unicode) it.heldUnicode else it.heldKeys) }
        if (down && (!heldElsewhere || wasHeld) || !down && wasHeld && !heldElsewhere) {
            if (unicode) session.bridge.sendUnicodeKeyEvent(handle, code, down) else session.bridge.sendKeyEvent(handle, code, down)
        }
    }
    var visible = true
    var hadSurface = false
    var surface: Surface? = null
    var host = WeakReference<SessionActivity>(null)
    var presented = false
    var launchDeadline = 0L
    var launchRequest: String? = null
    var launchSlot: Int? = null
    val bridge = object : DesktopSessionBridge {
        private val native get() = session.bridge
        private val handle get() = session.instance.value
        override var clipboardListener: ((String) -> Unit)?
            get() = null
            set(value) { /* The session owns clipboard synchronization. */ }
        override fun sendClipboardText(instance: Long, text: String) = handle != 0L && native.sendClipboardText(handle, text)
        override fun sendMouseEvent(instance: Long, x: Int, y: Int, flags: Int) {
            if (flags and 0x8000 != 0) android.util.Log.d("RdpInput",
                "Pointer monitor=$id ready=${session.inputReady} visible=$visible accepted=${session.acceptsInput(this@RdpMonitor)} busy=${session.busy} drag=${session.drag.active}")
            if (handle != 0L && session.acceptsInput(this@RdpMonitor) && !session.drag.active) {
                val (remoteX, remoteY) = RdpMonitorLayouts.pointer(rect, x, y)
                pointer = remoteX to remoteY
                val button = flags and 0x7000
                if (button != 0) buttons = if (flags and 0x8000 != 0) buttons or button else buttons and button.inv()
                native.sendMouseEvent(handle, remoteX, remoteY, flags)
            }
        }
        override fun sendKeyEvent(instance: Long, scancode: Int, isDown: Boolean) = key(scancode, isDown, false)
        override fun sendUnicodeKeyEvent(instance: Long, unicodeChar: Int, isDown: Boolean) = key(unicodeChar, isDown, true)
        override fun getDesktopWidth(instance: Long) = rect.width
        override fun getDesktopHeight(instance: Long) = rect.height
        override fun updateDisplay(instance: Long, width: Int, height: Int, zoom: Float) = session.resize(this@RdpMonitor, width, height)
        override fun setAudioMuted(instance: Long, speakerMuted: Boolean, micMuted: Boolean) { if (handle != 0L) native.setAudioMuted(handle, speakerMuted, micMuted) }
        override fun isMicrophoneRecording(instance: Long) = handle != 0L && native.isMicrophoneRecording(handle)
        override fun setCameraMuted(instance: Long, muted: Boolean) { if (handle != 0L) native.setCameraMuted(handle, muted) }
        override fun isCameraRecording(instance: Long) = handle != 0L && native.isCameraRecording(handle)
        override fun isCameraChannelConnected(instance: Long) = handle != 0L && native.isCameraChannelConnected(handle)
    }
    fun windowConnection() = object : DesktopSessionConnection {
        private var attached: Surface? = null
        override fun attach(next: Surface) { attached = next; this@RdpMonitor.attach(next) }
        override fun detach() {
            if (surface === attached) this@RdpMonitor.detach()
            attached = null
        }
        override fun close() = detach()
    }
    override fun attach(next: Surface) = session.attach(this, next)
    override fun detach() = session.detachSurface(this)
    override fun close() = detach() // Composition/surface lifetime is shorter than monitor lifetime.
}

internal class RdpMonitorSession(private val context: Context, val profile: ConnectionProfile) {
    val id = UUID.randomUUID().toString()
    val monitors = mutableStateListOf<RdpMonitor>()
    var bridge by mutableStateOf(FreeRDPBridge())
        private set
    val instance = mutableStateOf(0L)
    val certificateRequest = mutableStateOf<RdpCertificateRequest?>(null)
    val connectionFailed = mutableStateOf(false)
    val failureReason = mutableStateOf("")
    val disconnectedReason = mutableStateOf<String?>(null)
    val hasConnected = mutableStateOf(false)
    val tunnelStatus = mutableStateOf("")
    val speakerMuted = mutableStateOf(false)
    val micMuted = mutableStateOf(false)
    val cameraMuted = mutableStateOf(false)
    val autoReconnect = mutableStateOf(profile.reconnectAutomatically)
    val keyboardOwner = mutableStateOf<Long?>(null)
    var supportsAdditionalMonitors by mutableStateOf(false)
        private set
    var canAdd by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var inputReady = true
        private set
    val drag = RdpSessionDrag(this)
    private var panelPoints: Map<Long, RdpPanelPoint> = emptyMap()
    private var nextPoseRead = 0L
    private var poseCandidate: List<RdpMonitorRect>? = null
    private var poseSince = 0L
    private var posePoints: Map<Long, RdpPanelPoint> = emptyMap()
    private val visiblePoses = RdpPanelPoseTracker()
    var layoutNeedsRetry by mutableStateOf(false)
        private set
    val canRetryLayout get() = instance.value != 0L && disconnectedReason.value == null && !busy && !drag.active
    private var forceSync = false
    private var retryBlocked = false
    private var automaticRetries = 0
    private var rejectedTarget: List<RdpMonitorRect>? = null
    private var fallbackTarget: List<RdpMonitorRect>? = null
    private var recoveringLayout = false
    internal var layoutSubmissions = 0
        private set
    fun acceptsInput(monitor: RdpMonitor) = inputReady && monitor.visible &&
        (committed.any { it == monitor.rect } || committed.isEmpty() && isOwner(monitor))
    /** Re-read physical placement and reconcile both the remote topology and local outputs. */
    fun followPanelLayout() {
        if (closed || instance.value == 0L || busy || drag.active) return
        nextPoseRead = 0; poseCandidate = null
        observePanels(SystemClock.elapsedRealtime(), immediate = true)
        retryBlocked = false; automaticRetries = 0; forceSync = true
        rejectedTarget = null; fallbackTarget = null; recoveringLayout = false
        busy = true; canAdd = false
        monitors.forEach { it.releaseInput(mouseOnly = true) }
        resizeAt = SystemClock.elapsedRealtime() + 300
    }
    private fun targetLayout(ready: List<RdpMonitor>) = RdpSpatialLayout.arrange(
        ready.map { RdpMonitorRect(it.id, 0, 0, it.requestedWidth, it.requestedHeight) }, panelPoints)
    private fun observePanels(now: Long, immediate: Boolean = false) {
        if ((!immediate && now < nextPoseRead) || drag.active) return
        nextPoseRead = now + 500
        val ids = monitors.map { it.id }
        visiblePoses.retain(ids.toSet())
        for (monitor in monitors) monitor.host.get()?.let { activity ->
            val pose = RdpPanelPose.read(activity)
            val shown = pose?.visible != false && activity.window.decorView.isShown
            if (monitor.visible != shown) {
                monitor.visible = shown
                if (!shown) { drag.cancelFor(monitor); monitor.releaseInput() }
                applySurface(monitor)
            }
            visiblePoses.update(monitor.id,pose,shown,now)
        }
        if (monitors.size < 2) return
        // Home moves minimized panels into a lower tray before hiding them. Keep their
        // last settled room pose until a visible restoration supplies a replacement.
        val poses = visiblePoses.poses(ids) ?: return
        if (poses.any { it.parent != poses.first().parent }) return
        val points = monitors.mapIndexed { i, m -> m.id to poses.first().project(poses[i]) }.toMap()
        // Identity/local poses on some Home versions do not describe a shared arrangement.
        if (points.values.toList().withIndex().any { (i,a) -> points.values.drop(i+1).any { b -> kotlin.math.hypot(a.x-b.x,a.y-b.y) < .05f } }) return
        val target = RdpSpatialLayout.arrange(monitors.map { RdpMonitorRect(it.id,0,0,it.requestedWidth,it.requestedHeight) }, points)
        if (immediate) { panelPoints = points; poseCandidate = target; posePoints = points; poseSince = now; return }
        if (target != poseCandidate || points.keys != posePoints.keys || points.any { (id,p) ->
            posePoints[id]?.let { kotlin.math.hypot(p.x-it.x,p.y-it.y) > .025f } != false }) {
            poseCandidate = target; posePoints = points; poseSince = now; return
        }
        if (now - poseSince >= 750 && points != panelPoints) { panelPoints = points; resizeAt = now + 300 }
    }
    private var nextId = 1L
    private val main = Handler(Looper.getMainLooper())
    private var connection: RdpSessionConnection? = null
    private var effectiveProfile: ConnectionProfile? = null
    val configuredProfile get() = effectiveProfile
    private var reconnecting = false
    private var capture: DisplayCapturePermission? = null
    private var committed = emptyList<RdpMonitorRect>()
    private var inFlight: List<RdpMonitorRect>? = null
    private var sentAt = 0L
    private var revision = 0L
    private var resizeAt = 0L
    private var layoutActive = false
    private var closed = false
    private var lastRaw = longArrayOf()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var lastClip: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { sendClipboard() }
    private val poll = object : Runnable { override fun run() { if (!closed) { tick(); main.postDelayed(this, 100) } } }

    init { monitors += RdpMonitor(this, nextId++); main.post(poll) }
    fun first() = monitors.first()
    fun isOwner(monitor: RdpMonitor) = monitors.firstOrNull() === monitor
    fun configure(profile: ConnectionProfile) { if (effectiveProfile == null) effectiveProfile = profile }
    fun bindHost(monitor: RdpMonitor, activity: SessionActivity) {
        monitor.host = WeakReference(activity)
        if (capture == null) capture = activity.displayCapture
        bridge.displayCapture = capture
        updateMediaHost()
    }
    fun presented(monitor: RdpMonitor) { if (monitor in monitors) { monitor.presented = true; resizeAt = SystemClock.elapsedRealtime() + 300 } }
    fun updateMediaHost() {
        val activity = monitors.firstOrNull { it.host.get()?.isDestroyed == false }?.host?.get() ?: return
        capture?.useHost(activity.displayCapture)
        QuestSpatialAudio.windowSpace(activity)?.let { bridge.audioWindowSpace = it }
        val handle = instance.value
        if (handle != 0L) bridge.getAudioPlaybackSessionId(handle).takeIf { it > 0 }?.let { bridge.onNativeAudioSessionChanged(it) }
    }
    private fun connect(first: Surface) {
        val profile = effectiveProfile ?: this.profile
        val certificates = RdpCertificateVerifier(context, profile.host, profile.port, { certificateRequest.value = it })
        connection = RdpSessionConnection(context, profile, bridge, certificates, monitorMode = true,
            onDisconnected = { reason, established ->
                canAdd = false; supportsAdditionalMonitors = false; inputReady = false
                if (established || hasConnected.value) disconnectedReason.value = reason else failureReason.value = reason
            }, onStatus = { tunnelStatus.value = it }, onConnected = { handle ->
                instance.value = handle; tunnelStatus.value = ""
                connectionFailed.value = handle == 0L && !hasConnected.value
                if (handle != 0L) {
                    hasConnected.value = true
                    bridge.setAudioMuted(handle, speakerMuted.value, micMuted.value)
                    bridge.setCameraMuted(handle, cameraMuted.value)
                    monitors.forEach { applySurface(it) }
                    updateMediaHost(); startClipboard()
                }
            }).also { it.attach(first) }
    }
    fun attach(monitor: RdpMonitor, next: Surface) {
        if (closed || monitor !in monitors) { next.release(); return }
        detachSurface(monitor)
        monitor.hadSurface = true
        monitor.surface = next
        if (connection == null && !reconnecting) connect(next) else applySurface(monitor)
    }
    fun detachSurface(monitor: RdpMonitor) {
        drag.cancelFor(monitor)
        monitor.releaseInput()
        if (instance.value != 0L) bridge.setMonitorSurface(instance.value, monitor.id, null, 0, 0, 0, 0, 0)
        monitor.surface?.release(); monitor.surface = null
    }
    private fun applySurface(monitor: RdpMonitor) {
        val handle = instance.value
        if (handle == 0L) return
        val r = monitor.rect
        // A not-yet-confirmed new monitor gets no mirrored primary surface.
        val surface = monitor.surface.takeIf { monitor.visible && (committed.isEmpty() && isOwner(monitor) || committed.any { it.id == monitor.id }) }
        bridge.setMonitorSurface(handle, monitor.id, surface, r.x, r.y, r.width, r.height, revision)
    }
    fun resize(monitor: RdpMonitor, width: Int, height: Int) {
        val w = width.coerceIn(200, 8192) and -2; val h = height.coerceIn(200, 8192)
        if (w == monitor.requestedWidth && h == monitor.requestedHeight) return
        monitor.requestedWidth = w; monitor.requestedHeight = h
        resizeAt = SystemClock.elapsedRealtime() + 300
    }
    fun addWindow(activity: Activity) {
        if (!canAdd || closed || drag.active) return
        val monitor = RdpMonitor(this, nextId++).also {
            it.requestedWidth = 1280; it.requestedHeight = 800
            it.launchDeadline = SystemClock.elapsedRealtime() + 10_000
        }
        monitors += monitor; busy = true; canAdd = false
        val slot = SessionWindowSlots.reserve(activity, profile.id)
        monitor.launchSlot = slot
        val request = WindowLaunchMonitor.prepare(activity.applicationContext) {
            SessionWindowSlots.release(profile.id, slot)
            if (committed.none { it.id == monitor.id }) remove(monitor)
        }
        monitor.launchRequest = request
        val intent = SessionWindowLauncher.intent(activity, profile.id, slot).apply {
            putExtra(RdpMonitorSessions.EXTRA_SESSION, id)
            putExtra(RdpMonitorSessions.EXTRA_MONITOR, monitor.id)
            putExtra(WindowLaunchMonitor.EXTRA_REQUEST, request)
        }
        try { activity.startActivity(intent); WindowLaunchMonitor.watch(request) }
        catch (_: RuntimeException) { WindowLaunchMonitor.fail(request) }
    }
    fun remove(monitor: RdpMonitor) {
        if (monitor !in monitors) return
        drag.cancel()
        WindowLaunchMonitor.complete(monitor.launchRequest)
        monitor.launchSlot?.let { SessionWindowSlots.release(profile.id, it) }
        detachSurface(monitor); monitors.remove(monitor)
        if (monitors.isEmpty()) { close(); return }
        resizeAt = SystemClock.elapsedRealtime() + 300
        if (inFlight == null) busy = false
        if (retryBlocked) {
            retryBlocked = false; automaticRetries = 0; forceSync = true; busy = true
            observePanels(SystemClock.elapsedRealtime(), immediate = true)
        }
        updateMediaHost()
    }
    private fun readyMonitors() = monitors.filter { it.presented && it.hadSurface || committed.any { r -> r.id == it.id } }
    private fun recoveryRow() = RdpMonitorLayouts.row(readyMonitors().map { m ->
        committed.find { it.id == m.id } ?: RdpMonitorRect(m.id,0,0,1280,800)
    })
    private fun nativeOutputs() = bridge.getMonitorSurfaceState(instance.value).toList().chunked(8).filter { it.size == 8 }.associateBy { it[0] }
    private fun outputGeometry(output: List<Long>?, rect: RdpMonitorRect, version: Long): Boolean = output != null &&
        output[1] == rect.x.toLong() && output[2] == rect.y.toLong() && output[3] == rect.width.toLong() &&
        output[4] == rect.height.toLong() && output[5] == version
    private fun outputsMatch(target: List<RdpMonitorRect>, version: Long): Boolean {
        val outputs = nativeOutputs()
        val attached = monitors.filter { m -> m.surface != null && m.visible && target.any { it.id == m.id } }
        return outputs.size == attached.size && attached.all { m ->
            val rect = target.first { it.id == m.id }; val output = outputs[m.id]
            m.rect == rect && outputGeometry(output, rect, version) && output!![6] == version && output[7] > 0
        }
    }
    private fun bindOutputs(target: List<RdpMonitorRect>, version: Long) {
        val outputs = nativeOutputs()
        for ((id, _) in outputs) if (monitors.none { m -> m.id == id && m.surface != null && m.visible && target.any { it.id == id } })
            bridge.setMonitorSurface(instance.value, id, null, 0, 0, 0, 0, 0)
        for (monitor in monitors) target.find { it.id == monitor.id }?.let { rect ->
            monitor.rect = rect
            if (monitor.surface != null && monitor.visible && !outputGeometry(outputs[monitor.id], rect, version)) applySurface(monitor)
        }
    }
    private fun layoutFailed(now: Long, raw: LongArray) {
        android.util.Log.w("RdpLayout", "Layout sync failed, automatic retries=$automaticRetries recovery=$recoveringLayout")
        val failed = inFlight ?: targetLayout(readyMonitors())
        inFlight = null; inputReady = false; canAdd = false; layoutNeedsRetry = true
        if (recoveringLayout) {
            recoveringLayout = false; retryBlocked = true; forceSync = false; busy = false
            message(R.string.rdp_monitor_layout_failed)
        } else if (automaticRetries < 1) {
            automaticRetries++; forceSync = true; busy = true; resizeAt = now + 1000
            observePanels(now, immediate = true)
        } else {
            // Display Control has no rejection acknowledgement. A successful send does
            // not mean Windows accepted this shape. Stop resending it, and restore a
            // confirmed topology (or request a simple row if a new monitor is missing).
            rejectedTarget = failed; recoveringLayout = true; forceSync = false; busy = true
            val ready = readyMonitors()
            if (committed.map { it.id } == ready.map { it.id } && RdpMonitorLayouts.matches(raw, committed)) {
                inFlight = committed; sentAt = now; revision = raw[7]
                bindOutputs(committed, revision)
                android.util.Log.i("RdpLayout", "Recovering last confirmed layout monitors=${committed.size}")
            } else {
                fallbackTarget = recoveryRow()
                resizeAt = now
                android.util.Log.i("RdpLayout", "Recovering with a simple monitor row")
            }
        }
    }
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        // A panel minimized after its first surface/presentation remains part of this session.
        monitors.filter { it.launchDeadline > 0 && (!it.presented || !it.hadSurface) && now >= it.launchDeadline }.toList().forEach {
            it.host.get()?.finishAndRemoveTask(); remove(it); message(R.string.window_launch_failed)
        }
        observePanels(now)
        val handle = instance.value
        if (handle == 0L || disconnectedReason.value != null) return
        val raw = RdpMonitorLayouts.normalized(bridge.getMonitorLayoutState(handle)); lastRaw = raw
        if (raw.size < 10) return
        val count = raw[9].toInt()
        if (count !in 0..16 || raw.size < 10 + count * 5) return
        supportsAdditionalMonitors = profile.remoteApp.isNullOrBlank() && raw[0] == 1L && raw[8] == 1L && raw[1] > 1
        if (raw[8] == 1L && !layoutActive) { bridge.setMonitorLayoutActive(handle, true); layoutActive = true }
        if (raw[8] == 0L) {
            val rect = RdpMonitorRect(first().id, 0, 0, raw[4].toInt(), raw[5].toInt())
            if (rect != first().rect) { first().rect = rect; applySurface(first()) }
            if (now >= resizeAt && (raw[4] != first().requestedWidth.toLong() || raw[5] != first().requestedHeight.toLong())) {
                bridge.updateDisplay(handle, first().requestedWidth, first().requestedHeight, 1f)
                resizeAt = now + 1500
            }
            return
        }
        if (committed.isEmpty() && count == 1) {
            revision = raw[7]
            committed = listOf(RdpMonitorRect(first().id, 0, 0, (raw[12] - raw[10] + 1).toInt(), (raw[13] - raw[11] + 1).toInt()))
            first().rect = committed.first(); applySurface(first())
        }
        val painted = raw.getOrNull(10 + count * 5) == raw[7]
        inFlight?.let { target ->
            // An unchanged layout may be acknowledged without a new GFX reset. Rebind and
            // verify posted crops too, rather than requiring a newer protocol revision.
            if (RdpMonitorLayouts.matches(raw, target)) {
                committed = target; revision = raw[7]
                monitors.forEach { m -> if (target.any { it.id == m.id }) m.launchDeadline = 0 }
                bindOutputs(target, revision)
                if (painted && outputsMatch(target, revision)) {
                    inFlight = null; busy = false; inputReady = true; layoutNeedsRetry = rejectedTarget != null
                    recoveringLayout = false
                    automaticRetries = 0; retryBlocked = false
                    android.util.Log.i("RdpLayout", "Synced monitors=${target.size} revision=$revision")
                }
            }
            if (inFlight != null && (now - sentAt > 15_000 || raw[6] != 0L)) layoutFailed(now, raw)
        }
        // A revision is not the only source of drift: restored/replaced surfaces can also
        // carry the wrong crop while the host still reports the expected topology.
        if (inFlight == null && !forceSync && !retryBlocked && committed.isNotEmpty() && raw[0] == 1L &&
            (!RdpMonitorLayouts.matches(raw, committed) || revision != raw[7] || !painted || !outputsMatch(committed, revision))) {
            drag.cancel(); monitors.forEach { it.releaseInput(mouseOnly = true) }
            inputReady = false; canAdd = false; busy = true
            if (RdpMonitorLayouts.matches(raw, committed)) {
                // A normal GFX reset or a restored surface needs local rebinding, not
                // another identical remote reset (which could create a feedback loop).
                inFlight = committed; sentAt = now; revision = raw[7]
                bindOutputs(committed, revision)
            } else {
                layoutNeedsRetry = true; forceSync = true; automaticRetries = 0; resizeAt = now + 300
                if (rejectedTarget != null) {
                    recoveringLayout = true; fallbackTarget = recoveryRow()
                } else observePanels(now, immediate = true)
            }
        }
        if (inFlight == null && !retryBlocked && raw[0] == 1L && raw[8] == 1L && now >= resizeAt &&
            !drag.active && monitors.none { it.hasButtons }) {
            val target = fallbackTarget ?: targetLayout(readyMonitors())
            if (target.isNotEmpty() && (forceSync || recoveringLayout || target != committed) &&
                (recoveringLayout || target != rejectedTarget)) {
                if (!recoveringLayout) rejectedTarget = null
                fallbackTarget = null
                drag.cancel(); monitors.forEach { it.releaseInput(mouseOnly = true) }
                inputReady = false; canAdd = false
                if (forceSync) {
                    bridge.setMonitorLayoutActive(handle, true)
                    bridge.setSurface(handle, null) // No legacy full-atlas output in monitor mode.
                }
                layoutSubmissions++
                android.util.Log.i("RdpLayout", "Request retry=$automaticRetries rectangles=${target.map { listOf(it.x,it.y,it.width,it.height) }}")
                if (RdpMonitorLayouts.valid(target, raw[1].toInt(), raw[2], raw[3]) &&
                    bridge.requestMonitorLayout(handle, RdpMonitorLayouts.wire(target))) {
                    inFlight = target; sentAt = now; busy = true; forceSync = false
                } else layoutFailed(now, raw)
            }
        }
        if (!busy && inputReady && rejectedTarget != null && targetLayout(readyMonitors()) == committed &&
            RdpMonitorLayouts.matches(raw, committed)) {
            rejectedTarget = null; layoutNeedsRetry = false
        }
        // A pending/rejected placement does not invalidate the host's old layout.
        // Keep those confirmed crops interactive during the acknowledgement timeout.
        // Freeze only while the actual atlas/crops disagree, including a GFX transition.
        inputReady = RdpMonitorLayouts.matches(raw, committed) && painted && outputsMatch(committed, revision)
        canAdd = profile.remoteApp.isNullOrBlank() && !layoutNeedsRetry && !busy && !drag.active && inputReady &&
            monitors.all { it.presented } && raw[0] == 1L && raw[8] == 1L &&
            monitors.size < minOf(raw[1], 16L) && RdpMonitorLayouts.valid(
                RdpSpatialLayout.arrange(committed + RdpMonitorRect(-1, 0, 0, 1280, 800), panelPoints), raw[1].toInt(), raw[2], raw[3])
    }
    fun reconnect() {
        drag.cancel()
        monitors.forEach { it.releaseInput() }; keyboardOwner.value = null
        instance.value = 0L; canAdd = false; supportsAdditionalMonitors = false; inputReady = false; reconnecting = true
        val previous = connection; connection = null
        clipboard?.removePrimaryClipChangedListener(clipboardListener)
        certificateRequest.value = null; disconnectedReason.value = null; failureReason.value = ""; connectionFailed.value = false
        committed = emptyList(); inFlight = null; revision = 0; busy = false; inputReady = true
        layoutNeedsRetry = false; forceSync = false; retryBlocked = false; automaticRetries = 0
        rejectedTarget = null; fallbackTarget = null; recoveringLayout = false
        fun restart() {
            if (closed) return
            reconnecting = false
            capture?.resetSession()
            bridge = FreeRDPBridge().also { it.displayCapture = capture }
            layoutActive = false; inputReady = true
            updateMediaHost()
            monitors.firstOrNull { it.surface != null }?.surface?.let { connect(it) }
        }
        if (previous != null) previous.closeWhenDone { restart() } else restart()
    }
    private fun startClipboard() {
        if (!profile.redirectClipboard) return
        clipboard?.removePrimaryClipChangedListener(clipboardListener)
        clipboard?.addPrimaryClipChangedListener(clipboardListener)
        bridge.clipboardListener = { text -> main.post {
            if (!closed && text.isNotEmpty() && text != lastClip) {
                lastClip = text; clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.rdp_clipboard_label), text))
            }
        } }
        sendClipboard()
    }
    private fun sendClipboard() {
        if (instance.value == 0L || !profile.redirectClipboard) return
        runCatching { clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it != lastClip }?.let { lastClip = it; bridge.sendClipboardText(instance.value, it) }
    }
    fun closeWindows() {
        monitors.toList().forEach { monitor -> monitor.host.get()?.finishAndRemoveTask() ?: remove(monitor) }
    }
    private fun message(resource: Int) = Toast.makeText(context, resource, Toast.LENGTH_LONG).show()
    private fun close() {
        drag.cancel()
        closed = true; instance.value = 0L; canAdd = false
        main.removeCallbacks(poll); clipboard?.removePrimaryClipChangedListener(clipboardListener)
        connection?.close(); connection = null; capture?.close(); capture = null
        RdpMonitorSessions.remove(this)
    }
}
