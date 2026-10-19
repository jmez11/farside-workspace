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
    fun wire(row: List<RdpMonitorRect>) = row.flatMapIndexed { i, m -> listOf(m.x, m.y, m.width, m.height, if (i == 0) 1 else 0) }.toIntArray()
    fun valid(row: List<RdpMonitorRect>, maximum: Int, areaA: Long, areaB: Long): Boolean {
        if (row.isEmpty() || row.size > minOf(maximum, 16) || maximum <= 0 || areaA <= 0 || areaB <= 0) return false
        val budget = runCatching { Math.multiplyExact(Math.multiplyExact(maximum.toLong(), areaA), areaB) }.getOrNull() ?: return false
        return row.last().x.toLong() + row.last().width <= 32766 &&
            row.sumOf { it.width.toLong() * it.height } <= minOf(budget, MAX_PIXELS)
    }
    fun pointer(rect: RdpMonitorRect, x: Int, y: Int) =
        (rect.x + x.coerceIn(0, rect.width - 1)) to (rect.y + y.coerceIn(0, rect.height - 1))
    fun matches(raw: LongArray, row: List<RdpMonitorRect>): Boolean = raw.size >= 10 + row.size * 5 && raw[9] == row.size.toLong() &&
        row.withIndex().all { (i, m) -> val p = 10 + i * 5
            raw[p] == m.x.toLong() && raw[p + 1] == m.y.toLong() && raw[p + 2] == (m.x + m.width - 1).toLong() &&
                raw[p + 3] == (m.y + m.height - 1).toLong() && (raw[p + 4].toInt() and 1 != 0) == (i == 0) }
}

internal class RdpMonitor(val session: RdpMonitorSession, val id: Long) : DesktopSessionConnection {
    var rect by mutableStateOf(RdpMonitorRect(id, 0, 0, 1920, 1080))
    var requestedWidth = 1920
    var requestedHeight = 1080
    private val heldKeys = mutableSetOf<Int>()
    private val heldUnicode = mutableSetOf<Int>()
    private var buttons = 0
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
        if (handle == 0L) return
        val held = if (unicode) heldUnicode else heldKeys
        val wasHeld = code in held
        if (down) held.add(code) else held.remove(code)
        val heldElsewhere = session.monitors.any { it !== this && code in (if (unicode) it.heldUnicode else it.heldKeys) }
        if (down && (!heldElsewhere || wasHeld) || !down && wasHeld && !heldElsewhere) {
            if (unicode) session.bridge.sendUnicodeKeyEvent(handle, code, down) else session.bridge.sendKeyEvent(handle, code, down)
        }
    }
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
            if (handle != 0L && session.inputReady) {
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
    private var sentRevision = 0L
    private var revision = 0L
    private var resizeAt = 0L
    private var fallback = false
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
        monitor.surface = next
        if (connection == null && !reconnecting) connect(next) else applySurface(monitor)
    }
    fun detachSurface(monitor: RdpMonitor) {
        monitor.releaseInput()
        if (instance.value != 0L) bridge.setMonitorSurface(instance.value, monitor.id, null, 0, 0, 0, 0, 0)
        monitor.surface?.release(); monitor.surface = null
    }
    private fun applySurface(monitor: RdpMonitor) {
        val handle = instance.value
        if (handle == 0L) return
        val r = monitor.rect
        // A not-yet-confirmed new monitor gets no mirrored primary surface.
        val surface = monitor.surface.takeIf { if (fallback) isOwner(monitor) else committed.isEmpty() && isOwner(monitor) || committed.any { it.id == monitor.id } }
        bridge.setMonitorSurface(handle, monitor.id, surface, r.x, r.y, r.width, r.height, if (fallback) 0 else revision)
    }
    fun resize(monitor: RdpMonitor, width: Int, height: Int) {
        val w = width.coerceIn(200, 8192) and -2; val h = height.coerceIn(200, 8192)
        if (w == monitor.requestedWidth && h == monitor.requestedHeight) return
        monitor.requestedWidth = w; monitor.requestedHeight = h
        resizeAt = SystemClock.elapsedRealtime() + 300
    }
    fun addWindow(activity: Activity) {
        if (!canAdd || closed) return
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
        WindowLaunchMonitor.complete(monitor.launchRequest)
        monitor.launchSlot?.let { SessionWindowSlots.release(profile.id, it) }
        detachSurface(monitor); monitors.remove(monitor)
        if (monitors.isEmpty()) { close(); return }
        resizeAt = SystemClock.elapsedRealtime() + 300
        if (inFlight == null) busy = false
        updateMediaHost()
    }
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        monitors.filter { it.launchDeadline > 0 && (!it.presented || it.surface == null) && now >= it.launchDeadline }.toList().forEach {
            it.host.get()?.finishAndRemoveTask(); remove(it); message(R.string.window_launch_failed)
        }
        val handle = instance.value
        if (handle == 0L || disconnectedReason.value != null) return
        val raw = bridge.getMonitorLayoutState(handle); lastRaw = raw
        if (raw.size < 10) return
        val count = raw[9].toInt()
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
        if (inFlight == null && committed.isNotEmpty() && raw[7] != revision) {
            val observed = if (count == committed.size) committed.mapIndexed { i, old -> val p = 10 + i * 5
                old.copy(x = raw[p].toInt(), y = raw[p + 1].toInt(), width = (raw[p + 2] - raw[p] + 1).toInt(), height = (raw[p + 3] - raw[p + 1] + 1).toInt()) } else emptyList()
            revision = raw[7]
            if (observed.isNotEmpty() && observed == RdpMonitorLayouts.row(observed) && RdpMonitorLayouts.matches(raw, observed)) {
                committed = observed
                monitors.forEach { m -> committed.find { it.id == m.id }?.let { m.rect = it }; applySurface(m) }
            } else {
                fallback = true
                first().rect = RdpMonitorRect(first().id, 0, 0, raw[4].toInt(), raw[5].toInt())
                monitors.forEach { applySurface(it) }
                message(R.string.rdp_monitor_layout_failed)
            }
        }
        inFlight?.let { target ->
            if (raw[7] > sentRevision && RdpMonitorLayouts.matches(raw, target)) {
                if (committed != target || revision != raw[7]) {
                    committed = target; revision = raw[7]
                    monitors.forEach { m -> if (target.any { it.id == m.id }) m.launchDeadline = 0 }
                    monitors.forEach { m -> target.find { it.id == m.id }?.let { m.rect = it }; applySurface(m) }
                }
                inputReady = painted
                if (painted) { inFlight = null; busy = false }
            }
            if (inFlight != null && (now - sentAt > 15_000 || raw[6] != 0L)) {
                // Preserve access to every remote pixel if the server ignores a topology change.
                inFlight = null; fallback = true; busy = false; inputReady = true
                monitors.filter { m -> committed.none { it.id == m.id } }.toList().forEach { m -> m.host.get()?.finishAndRemoveTask(); remove(m) }
                first().rect = RdpMonitorRect(first().id, 0, 0, raw[4].toInt(), raw[5].toInt())
                monitors.forEach { applySurface(it) }; message(R.string.rdp_monitor_layout_failed)
            }
        }
        if (inFlight == null && !fallback && raw[0] == 1L && raw[8] == 1L && now >= resizeAt) {
            val ready = monitors.filter { it.presented && it.surface != null || committed.any { r -> r.id == it.id } }
            val target = RdpMonitorLayouts.row(ready.map { RdpMonitorRect(it.id, 0, 0, it.requestedWidth, it.requestedHeight) })
            if (target.isNotEmpty() && target != committed) {
                if (RdpMonitorLayouts.valid(target, raw[1].toInt(), raw[2], raw[3]) && bridge.requestMonitorLayout(handle, RdpMonitorLayouts.wire(target))) {
                    monitors.forEach { it.releaseInput(mouseOnly = true) }
                    inFlight = target; sentAt = now; sentRevision = raw[7]; busy = true; inputReady = false
                } else {
                    ready.filter { m -> committed.none { it.id == m.id } }.forEach { m -> m.host.get()?.finishAndRemoveTask(); remove(m) }
                    monitors.forEach { m -> committed.find { it.id == m.id }?.let { m.requestedWidth = it.width; m.requestedHeight = it.height } }
                    busy = false; message(R.string.rdp_monitor_limit)
                }
            }
        }
        canAdd = profile.remoteApp.isNullOrBlank() && !fallback && !busy && monitors.all { it.presented } && raw[0] == 1L && raw[8] == 1L &&
            monitors.size < minOf(raw[1], 16L) && RdpMonitorLayouts.valid(RdpMonitorLayouts.row(committed + RdpMonitorRect(-1, 0, 0, 1280, 800)), raw[1].toInt(), raw[2], raw[3])
    }
    fun reconnect() {
        monitors.forEach { it.releaseInput() }; keyboardOwner.value = null
        instance.value = 0L; canAdd = false; supportsAdditionalMonitors = false; inputReady = false; reconnecting = true
        val previous = connection; connection = null
        clipboard?.removePrimaryClipChangedListener(clipboardListener)
        certificateRequest.value = null; disconnectedReason.value = null; failureReason.value = ""; connectionFailed.value = false
        committed = emptyList(); inFlight = null; fallback = false; revision = 0; busy = false; inputReady = true
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
        closed = true; instance.value = 0L; canAdd = false
        main.removeCallbacks(poll); clipboard?.removePrimaryClipChangedListener(clipboardListener)
        connection?.close(); connection = null; capture?.close(); capture = null
        RdpMonitorSessions.remove(this)
    }
}
