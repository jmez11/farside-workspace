package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Live full-desktop integration: same transport, independently cropped document windows. */
internal class RdpMonitorWindowsVerification(private val test: Instrumentation) {
    private val context get() = test.targetContext
    private val report = JSONObject()
    private val stages = JSONArray()
    private val directory = File(context.cacheDir, "rdp-monitor-windows").apply { mkdirs() }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun await(label: String, timeout: Long = 25_000, predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        error("Timed out: $label")
    }
    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun node(label: String, display: Int? = null): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        fun search(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.contentDescription?.toString() == label || n.text?.toString() == label) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { search(it)?.let { result -> return result } }
            return null
        }
        val windows = test.uiAutomation.windowsOnAllDisplays
        for (i in 0 until windows.size()) if (display == null || windows.keyAt(i) == display) for (window in windows.valueAt(i)) window.root?.let {
            if (it.packageName == context.packageName) search(it)?.let { result -> return result }
        }
        return null
    }
    private fun click(label: String, display: Int? = null) {
        var control = checkNotNull(node(label, display)) { "Missing toolbar action: $label" }
        while (!control.isClickable && control.parent != null) control = control.parent
        check(control.isEnabled && control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled/rejected action: $label" }
    }
    private fun presentation(activity: SessionActivity): Pair<Boolean, Boolean> {
        test.uiAutomation.adoptShellPermissionIdentity()
        return try {
            val cls = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
            val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", android.app.Activity::class.java).invoke(null,activity)
            val info = cls.getMethod("getWindowInfo",Class.forName("horizonos.view.VolumetricWindowToken")).invoke(activity.getSystemService(cls),token)
            (info.javaClass.getMethod("isVisible").invoke(info) == true) to (info.javaClass.getMethod("isDrawn").invoke(info) == true)
        } finally { test.uiAutomation.dropShellPermissionIdentity() }
    }
    fun geometryOnly(): String { geometryTests(); return "Monitor bounds, connectivity, stable wire IDs, unordered host matching, hidden-pose tracking and gesture ownership." }
    private fun geometryTests() {
        val row = RdpMonitorLayouts.row(listOf(RdpMonitorRect(10, 0, 0, 1281, 800), RdpMonitorRect(11, 0, 0, 1024, 768)))
        check(row[0].width == 1280 && row[1].x == 1280)
        check(RdpMonitorLayouts.pointer(row[1], -40, 900) == (1280 to 767))
        check(RdpMonitorLayouts.pointer(row[1], 1023, 0) == (2303 to 0))
        check(RdpMonitorLayouts.valid(row, 16, 8192, 8192))
        check(!RdpMonitorLayouts.valid(row, 1, 8192, 8192))
        check(!RdpMonitorLayouts.valid(row, 16, 200, 200))
        check(!RdpMonitorLayouts.valid(RdpMonitorLayouts.row(List(5) { RdpMonitorRect(it.toLong(), 0, 0, 8192, 8192) }), 16, 8192, 8192))
        val promoted = RdpMonitorLayouts.row(row.drop(1))
        check(promoted.single().id == 11L && promoted.single().x == 0)
        val wire = RdpMonitorLayouts.wire(row)
        check(wire.contentEquals(intArrayOf(0, 0, 1280, 800, 1, 1280, 0, 1024, 768, 0)))
        val points = mapOf(10L to RdpPanelPoint(0f,0f), 11L to RdpPanelPoint(-1f,0f))
        val reversed = RdpSpatialLayout.arrange(row,points)
        check(reversed[0].x == 1024 && reversed[1].x == 0)
        check(RdpMonitorLayouts.wire(reversed).contentEquals(intArrayOf(0,0,1280,800,1,-1024,0,1024,768,0)))
        val above = RdpSpatialLayout.arrange(row,mapOf(10L to RdpPanelPoint(0f,0f),11L to RdpPanelPoint(0f,1f)))
        check(above[0].y == 768 && above[1].y == 0 && RdpMonitorLayouts.valid(above,16,8192,8192))
        val four = List(4) { RdpMonitorRect(it.toLong(),0,0,1280,800) }
        val upperRight = RdpSpatialLayout.arrange(four,mapOf(0L to RdpPanelPoint(0f,0f),
            1L to RdpPanelPoint(1f,0f),2L to RdpPanelPoint(2f,0f),3L to RdpPanelPoint(2f,1f)))
        check(upperRight.take(3).map { it.x to it.y } == listOf(0 to 800,1280 to 800,2560 to 800))
        check(kotlin.math.abs(upperRight[3].x-2560) <= 2 && upperRight[3].y == 0)
        check(RdpMonitorLayouts.valid(upperRight,16,8192,8192))
        val mixed = RdpSpatialLayout.arrange(four.mapIndexed { i,m -> if (i == 0) m.copy(height=768) else m },
            mapOf(0L to RdpPanelPoint(0f,1f),1L to RdpPanelPoint(1f,1f),
                2L to RdpPanelPoint(2f,1f),3L to RdpPanelPoint(0f,0f)))
        check(RdpMonitorLayouts.valid(mixed,16,8192,8192))
        val diagonal = RdpSpatialLayout.arrange(four.take(2),mapOf(0L to RdpPanelPoint(0f,0f),1L to RdpPanelPoint(-1f,-1f)))
        check(diagonal[1].x < diagonal[0].x && diagonal[1].y > diagonal[0].y)
        check(RdpMonitorLayouts.valid(diagonal,16,8192,8192))
        report.put("crossRowHorizontalAlignment",true)
        check(!RdpMonitorLayouts.valid(listOf(row[0],row[1].copy(x=10)),16,8192,8192))
        val q = kotlin.math.sqrt(.5f)
        val base = RdpPanelPose(0f,0f,0f,0f,q,0f,q,null)
        check(kotlin.math.abs(base.project(base.copy(z=-1f)).x-1f) < .001f)
        val sent = mutableListOf<Int>()
        var transfers = 0
        val gesture = RdpPointerGesture(8f,{ _,_,flag -> sent += flag },{ _,_ -> },{ _,_ -> transfers++; true })
        for ((action,x) in listOf(android.view.MotionEvent.ACTION_DOWN to 10f,android.view.MotionEvent.ACTION_MOVE to 40f,
            android.view.MotionEvent.ACTION_CANCEL to 40f,android.view.MotionEvent.ACTION_UP to 50f)) {
            val event = android.view.MotionEvent.obtain(100,200,action,x,10f,0)
            gesture.onTouch(event); event.recycle()
        }
        check(transfers == 1 && sent == listOf(FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN,FreeRDPBridge.PTR_FLAGS_MOVE)) { "Transferred gesture sent an early release: $sent" }
        val crossed = listOf(RdpMonitorRect(1,0,800,1280,800),RdpMonitorRect(2,2560,0,1024,768),
            RdpMonitorRect(3,0,0,1280,800),RdpMonitorRect(4,1280,0,1280,800))
        check(RdpMonitorLayouts.valid(crossed,16,8192,8192))
        val stableWire = RdpMonitorLayouts.wire(crossed).toList().chunked(5)
        check(stableWire.map { it[0] to it[1] } == listOf(0 to 0,2560 to -800,0 to -800,1280 to -800))
        val tracker = RdpPanelPoseTracker()
        val room = RdpPanelPose(0f,0f,0f,0f,0f,0f,1f,null,true)
        tracker.update(1,room,true,0); tracker.update(1,room,true,1000)
        check(tracker.poses(listOf(1L))?.single() == room)
        tracker.update(1,room.copy(y=-1f),true,1100) // transient visible part of minimization
        tracker.update(1,room.copy(y=-1f,visible=false),false,1400)
        check(tracker.poses(listOf(1L))?.single() == room)
        val restoredPose = room.copy(x=2f)
        tracker.update(1,restoredPose,true,2000); tracker.update(1,restoredPose,true,2800)
        check(tracker.poses(listOf(1L))?.single() == restoredPose)
        check(!RdpMonitorLayouts.valid(listOf(row.first(),row.last().copy(x=10000)),16,8192,8192))
        val raw = longArrayOf(1,16,8192,8192,2304,800,0,1,1,2,1280,0,2303,767,0,0,0,1279,799,1,1)
        check(RdpMonitorLayouts.matches(raw,row)) { "Server enumeration order must not change monitor identity" }
        check(!RdpMonitorLayouts.matches(raw.copyOf().also { it[4]=1280 },row)) { "Framebuffer bounds must also agree" }
        report.put("layoutAndPointerBounds", true).put("signedLayoutsAndTransferredGesture",true).put("unorderedHostMonitors",true)
    }
    private fun dragEvent(action: Int, x: Float, y: Float, description: android.content.ClipDescription?): android.view.DragEvent {
        // Android 14 Parcelable field order, verified against the headset framework.
        // Avoid hidden factory methods, which are filtered from app reflection.
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeInt(action); parcel.writeFloat(x); parcel.writeFloat(y)
            parcel.writeFloat(0f); parcel.writeFloat(0f); parcel.writeInt(1)
            parcel.writeInt(0) // no ClipData/URI grants
            parcel.writeInt(if (description == null) 0 else 1)
            description?.writeToParcel(parcel,0)
            parcel.writeInt(0); parcel.writeInt(0) // no native surface or drag permissions
            parcel.setDataPosition(0)
            android.view.DragEvent.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
    private fun syntheticDrag(first: RdpMonitor, second: RdpMonitor) {
        val session = first.session
        var description: android.content.ClipDescription? = null
        // Replace only the Android start boundary; route real RDP input through the same coordinator.
        val drag = RdpSessionDrag(session) { _,clip -> description = clip.description; true }
        val v = checkNotNull(first.host.get()).window.decorView
        val move = android.view.DragEvent.ACTION_DRAG_LOCATION
        val drop = android.view.DragEvent.ACTION_DROP
        main {
            first.bridge.sendMouseEvent(session.instance.value,600,400,FreeRDPBridge.PTR_FLAGS_BUTTON1 or FreeRDPBridge.PTR_FLAGS_DOWN)
            check(first.hasButtons && drag.start(v,first,600,400) && !first.hasButtons)
            check(drag.event(second,dragEvent(android.view.DragEvent.ACTION_DRAG_STARTED,0f,0f,description)) { x,y -> x.toInt() to y.toInt() })
            val isolated = RdpMonitorSessions.create(context,session.profile)
            try { check(!drag.accepts(isolated,true,description?.label?.toString())) }
            finally { isolated.session.remove(isolated) }
            check(!drag.accepts(second,true,"different-token"))
            check(!drag.accepts(second,false,description?.label?.toString()))
            check(drag.event(second,dragEvent(move,400f,400f,description)) { x,y -> x.toInt() to y.toInt() })
            val expected = RdpMonitorLayouts.pointer(second.rect,400,400)
            check(drag.active && drag.position == expected)
            check(drag.event(second,dragEvent(drop,400f,400f,description)) { x,y -> x.toInt() to y.toInt() })
            check(!drag.active && drag.position == expected)
            // Cancellation and source destruction release the session-owned button, including duplicates.
            check(drag.start(v,first,600,400))
            drag.cancelFor(first); drag.cancelFor(first)
            check(!drag.active && !first.hasButtons)
            check(drag.start(v,first,600,400))
            check(drag.event(first,dragEvent(android.view.DragEvent.ACTION_DRAG_ENDED,0f,0f,null)) { x,y -> x.toInt() to y.toInt() })
            check(!drag.active)
        }
        report.put("syntheticTargetDragHandoff",true).put("dragTokenIsolation",true).put("dragCancellation",true)
    }
    private fun nativeDrag(first: RdpMonitor, second: RdpMonitor) {
        val session = first.session
        val source = checkNotNull(first.host.get())
        val starts = mutableSetOf<Long>()
        var description: android.content.ClipDescription? = null
        val events = JSONArray()
        fun point(m: RdpMonitor, v: View, x: Float, y: Float) = mapScreenToDesktop(x,y,v.width.toFloat(),v.height.toFloat(),1f,0f,0f,m.rect.width,m.rect.height)
        main {
            for (m in listOf(first,second)) {
                val v = checkNotNull(m.host.get()).window.decorView
                v.setOnDragListener { _, event ->
                    events.put(JSONObject().put("monitor",m.id).put("action",event.action))
                    if (event.action == android.view.DragEvent.ACTION_DRAG_STARTED) {
                        starts += m.id; description = event.clipDescription
                    }
                    session.drag.event(m,event) { x,y -> point(m,v,x,y) }
                }
            }
        }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("am task focus ${source.taskId}")).use { it.readBytes() }
        Thread.sleep(500)
        val time = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float) {
            val event = android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,x,500f,0).apply {
                this.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            android.view.InputEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType).invoke(event,source.display!!.displayId)
            try { val ok = test.uiAutomation.injectInputEvent(event,true); if (action != android.view.MotionEvent.ACTION_UP) check(ok) } finally { event.recycle() }
        }
        try {
            touch(android.view.MotionEvent.ACTION_DOWN,500f)
            touch(android.view.MotionEvent.ACTION_MOVE,550f)
            main { check(!session.drag.active && first.hasButtons) { "Interior drag must stay a normal RDP drag" } }
            touch(android.view.MotionEvent.ACTION_MOVE,source.window.decorView.width - 2f)
            await("native drag START callback",5000) { description != null }
            Thread.sleep(200)
            main {
                if (session.drag.active) {
                    val target = checkNotNull(second.host.get()).window.decorView
                    if (second.id !in starts) target.dispatchDragEvent(dragEvent(android.view.DragEvent.ACTION_DRAG_STARTED,0f,0f,description))
                    check(target.dispatchDragEvent(dragEvent(android.view.DragEvent.ACTION_DRAG_LOCATION,400f,400f,description)))
                    check(session.drag.position == point(second,target,400f,400f).let { RdpMonitorLayouts.pointer(second.rect,it.first,it.second) })
                    check(target.dispatchDragEvent(dragEvent(android.view.DragEvent.ACTION_DROP,400f,400f,description)))
                    source.window.decorView.cancelDragAndDrop()
                    report.put("nativeStartSyntheticDrop",true)
                } else {
                    // Home can cancel injected spatial drags (e.g. another active Home drag).
                    check(!first.hasButtons)
                    report.put("nativeStartSyntheticDrop",false).put("nativeCancellationReleasedInput",true)
                }
            }
        } finally {
            report.put("dragEvents",events).put("nativeDragSourceStarted",first.id in starts).put("nativeDragDestinationStarted",second.id in starts)
            touch(android.view.MotionEvent.ACTION_UP,550f)
            main {
                session.drag.cancel()
                for (m in listOf(first,second)) {
                    val v = checkNotNull(m.host.get()).window.decorView
                    v.setOnDragListener { _,event -> session.drag.event(m,event) { x,y -> point(m,v,x,y) } }
                }
            }
        }
        syntheticDrag(first,second)
    }
    fun run(): String {
        val service = test.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = service
        geometryTests()
        val profile = ProfileManager(context).getProfiles().first { it.type == ConnectionType.RDP }
        check(profile.remoteApp.isNullOrEmpty()) { "Requires full desktop profile" }
        var owner: SessionActivity? = null
        var runtime: RdpMonitorSession? = null
        try {
            owner = test.startActivitySync(SessionWindowLauncher.intent(context, profile.id,
                SessionWindowSlots.reserve(context, profile.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SessionActivity
            val firstActivity = checkNotNull(owner)
            val first = checkNotNull(firstActivity.rdpMonitor)
            val session = first.session; runtime = session
            var handle = 0L
            await("initial toolbar pin") { node(context.getString(R.string.keep_toolbar_visible)) != null }
            click(context.getString(R.string.keep_toolbar_visible))
            await("connection and supported idle toolbar", 60_000) {
                // Existing session-only trust acceptance, never a persisted verification bypass.
                if (node("Certificate can't be verified") != null) click("Connect")
                var ready = false
                main { handle = session.instance.value; ready = handle != 0L && session.canAdd }
                ready
            }
            var native = session.bridge
            fun settled(count: Int, allowRetry: Boolean = false) {
                await("$count committed monitors and posted surfaces") {
                    var ready = false
                    main {
                        val raw = native.getMonitorLayoutState(handle)
                        val surfaces = native.getMonitorSurfaceState(handle).toList().chunked(8)
                        val attached = session.monitors.filter { it.surface != null && it.visible }
                        ready = session.instance.value == handle && session.bridge === native && !session.busy && (allowRetry || !session.layoutNeedsRetry) && session.inputReady &&
                            session.monitors.size == count && raw.getOrNull(9) == count.toLong() && surfaces.size == attached.size &&
                            RdpMonitorLayouts.matches(RdpMonitorLayouts.normalized(raw),session.confirmedLayout) &&
                            attached.all { m -> surfaces.any { it[0] == m.id && it[1] == m.rect.x.toLong() && it[2] == m.rect.y.toLong() &&
                                it[3] == m.rect.width.toLong() && it[4] == m.rect.height.toLong() && it[7] > 0 && it[5] == raw[7] && it[6] == raw[7] } }
                    }
                    ready
                }
            }
            fun verifyUiPointer(monitor: RdpMonitor, label: String) {
                val activity = checkNotNull(monitor.host.get())
                var before: Bitmap? = null
                var delivered = 0
                main { before = texture(activity.window.decorView)?.bitmap
                    activity.onTouchDebug = { _,_,_,_ -> delivered++ }
                    val view = activity.window.decorView
                    val now = android.os.SystemClock.uptimeMillis()
                    val x = view.width / 2f; val y = view.height / 2f
                    val down = android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0)
                    val up = android.view.MotionEvent.obtain(now,now+1100,android.view.MotionEvent.ACTION_UP,x,y,0)
                    try { activity.dispatchTouchEvent(down); activity.dispatchTouchEvent(up) }
                    finally { down.recycle(); up.recycle() }
                }
                try {
                    check(delivered >= 2) { "$label did not reach desktop pointer UI" }
                    val original = checkNotNull(before)
                    await("$label pointer causes remote context menu in its own crop",10_000) {
                        var changed = 0
                        main { texture(activity.window.decorView)?.bitmap?.let { after ->
                            if (after.width == original.width && after.height == original.height) {
                                for (y in 0 until after.height step 10) for (x in 0 until after.width step 10)
                                    if (original.getPixel(x,y) != after.getPixel(x,y)) changed++
                            }
                            if (changed > 100) File(directory,"$label.png").outputStream().use {
                                after.compress(Bitmap.CompressFormat.PNG,100,it) }
                            after.recycle()
                        } }
                        changed > 100
                    }
                    report.put(label,true)
                } finally {
                    main { activity.onTouchDebug = null
                        monitor.bridge.sendKeyEvent(handle,1,true); monitor.bridge.sendKeyEvent(handle,1,false) }
                    before?.recycle()
                }
            }
            fun record(label: String, allowMinimized: Boolean = false) {
                settled(session.monitors.size)
                val stage = JSONObject().put("stage", label).put("sameNativeConnection", session.instance.value == handle)
                val raw = native.getMonitorLayoutState(handle)
                stage.put("serverLayout", JSONArray(raw.toList())).put("surfaces", JSONArray(native.getMonitorSurfaceState(handle).toList()))
                val windows = JSONArray()
                session.monitors.toList().forEach { monitor ->
                    val activity = checkNotNull(monitor.host.get())
                    var bitmap: Bitmap? = null
                    if (allowMinimized) {
                        val (visible,drawn) = presentation(activity)
                        if (!visible || !drawn || monitor.surface == null || texture(activity.window.decorView)?.isAvailable != true) {
                            windows.put(JSONObject().put("monitor",monitor.id).put("task",activity.taskId).put("surfaceAttached",monitor.surface != null)
                                .put("visible",visible).put("drawn",drawn))
                            return@forEach
                        }
                    }
                    // This saved Windows desktop has a known non-flat image. Color is a test
                    // fixture condition, never the production readiness criterion (black is valid).
                    await("$label monitor ${monitor.id} actual desktop video", 15_000) {
                        bitmap?.recycle()
                        main { bitmap = texture(activity.window.decorView)?.bitmap }
                        bitmap?.let { image ->
                            val colors = mutableSetOf<Int>()
                            for (y in 0 until image.height step maxOf(1, image.height / 40))
                                for (x in 0 until image.width step maxOf(1, image.width / 40)) colors += image.getPixel(x, y)
                            colors.size > 16
                        } == true
                    }
                    val image = checkNotNull(bitmap)
                    File(directory, "$label-${monitor.id}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until image.height step maxOf(1, image.height / 50)) for (x in 0 until image.width step maxOf(1, image.width / 50)) colors += image.getPixel(x, y)
                    var visible = false
                    var drawn = false
                    await("$label native window ${monitor.id} visible/drawn", 10_000) {
                        val state = presentation(activity); visible = state.first; drawn = state.second
                        allowMinimized || visible && drawn
                    }
                    windows.put(JSONObject().put("visible", visible).put("drawn", drawn).put("monitor", monitor.id).put("task", activity.taskId).put("width", monitor.rect.width).put("height", monitor.rect.height).put("x", monitor.rect.x).put("sampleColors", colors.size))
                    image.recycle()
                }
                stage.put("windows", windows); stages.put(stage)
                File(directory, "results.json").writeText(report.put("stages", stages).toString(2))
            }
            settled(1); record("one")
            var isolatedMonitor: RdpMonitor? = null
            main { isolatedMonitor = RdpMonitorSessions.create(context, profile) }
            val isolated = checkNotNull(isolatedMonitor)
            try {
                check(isolated.session !== session && isolated.session.bridge !== session.bridge)
                main { isolated.session.speakerMuted.value = true }
                check(!session.speakerMuted.value && isolated.session.instance.value == 0L)
            } finally { main { isolated.session.remove(isolated) } }
            report.put("sameProfileSessionIsolation", true)
            // Actual accessible floating-toolbar action, not a direct launch fixture.
            await("Add desktop action") { node(context.getString(R.string.rdp_add_desktop)) != null }
            click(context.getString(R.string.rdp_add_desktop)); settled(2); record("two")
            val second = session.monitors.last()
            report.put("ordinaryPanelPoses", JSONArray(session.monitors.map { RdpPanelPose.read(checkNotNull(it.host.get()))?.toString() ?: "unavailable" }))
            fun followPlacement() {
                session.monitors.forEach { m -> main { m.host.get()?.onWindowFocusChanged(true) } }
                val before = session.layoutSubmissions
                val action = context.getString(R.string.rdp_layout_auto)
                await("follow panel placement toolbar") { node(action) != null }
                click(action)
                await("toolbar re-reads and resubmits layout") { var ready = false; main { ready = session.layoutSubmissions > before && !session.busy && !session.layoutNeedsRetry }; ready }
                settled(session.monitors.size)
            }
            followPlacement()
            report.put("followPlacementToolbarWithoutDialog",true)
            await("automatic layout from ordinary actual panel poses") {
                var ready = false
                main {
                    val poses = session.monitors.map { RdpPanelPose.read(checkNotNull(it.host.get())) }
                    if (poses.all { it != null }) {
                        val origin = checkNotNull(poses.first())
                        val expected = RdpSpatialLayout.arrange(session.monitors.map { it.rect }, session.monitors.mapIndexed { i,m -> m.id to origin.project(checkNotNull(poses[i])) }.toMap())
                        ready = !session.busy && session.monitors.map { it.rect } == expected
                    }
                }; ready
            }
            Thread.sleep(1500)
            record("automatic-actual-poses")
            report.put("automaticOrdinaryPanelPoses",true)
            settled(2)
            check(second.session === first.session && second.host.get()?.rdpBridge === native)
            // Native crop geometry and inverse pointer mapping share the same committed rectangle.
            val outputs = native.getMonitorSurfaceState(handle).toList().chunked(8)
            check(outputs.single { it[0] == second.id }[1] == first.rect.width.toLong())
            check(RdpMonitorLayouts.pointer(second.rect, 0, 0).first == first.rect.width)
            main { session.resize(second, 1024, 768) }
            await("secondary resize") { var ready = false; main { ready = !session.busy && second.rect.width == 1024 && second.rect.height == 768 }; ready }
            record("secondary-resized")
            // Shared media values change immediately in both windows without another native connection.
            click(context.getString(R.string.mute_speakers))
            await("session mute state") { session.speakerMuted.value }
            check(first.session.speakerMuted === second.session.speakerMuted)
            main { session.speakerMuted.value = false }
            check(session.bridge === native && session.instance.value == handle)
            main { session.addWindow(checkNotNull(second.host.get())) }
            settled(3); record("three")
            main { session.addWindow(checkNotNull(session.monitors.last().host.get())) }
            settled(4)
            if (test.arguments?.getString("rdpLayoutProbe") == "true") {
                val handler = session.javaClass.getDeclaredField("main").apply { isAccessible=true }.get(session) as android.os.Handler
                val poll = session.javaClass.getDeclaredField("poll").apply { isAccessible=true }.get(session) as Runnable
                main { handler.removeCallbacks(poll) }
                val probes = JSONArray()
                try {
                    fun probe(label: String, rectangles: List<RdpMonitorRect>) {
                        main { check(native.requestMonitorLayout(handle,RdpMonitorLayouts.wire(rectangles))) }
                        val end = android.os.SystemClock.elapsedRealtime()+2500
                        var matched = false
                        do {
                            Thread.sleep(100)
                            matched = RdpMonitorLayouts.matches(RdpMonitorLayouts.normalized(native.getMonitorLayoutState(handle)),rectangles)
                        } while (!matched && android.os.SystemClock.elapsedRealtime()<end)
                        probes.put(JSONObject().put("label",label).put("matched",matched)
                            .put("requested",JSONArray(rectangles.map { it.toString() }))
                            .put("actual",JSONArray(native.getMonitorLayoutState(handle).toList())))
                        File(directory,"probe.json").writeText(probes.toString(2))
                    }
                    fun rects(width: Int,height: Int,xy: List<Pair<Int,Int>>) = xy.mapIndexed { i,(x,y) ->
                        RdpMonitorRect(i.toLong(),x*width,y*height,width,height) }
                    val l = listOf(2 to 1,1 to 1,0 to 1,0 to 0)
                    val baseline = rects(1280,800,List(4) { it to 0 })
                    for ((label,layout) in listOf(
                        "L-no-corner-contacts" to listOf(RdpMonitorRect(0,2562,800,1280,800),RdpMonitorRect(1,1282,800,1280,800),RdpMonitorRect(2,2,800,1280,800),RdpMonitorRect(3,0,0,1280,800)),
                        "grid-no-corner-contacts" to listOf(RdpMonitorRect(0,0,0,1280,800),RdpMonitorRect(1,1280,0,1280,800),RdpMonitorRect(2,2,800,1280,800),RdpMonitorRect(3,1282,800,1280,800)),
                        "five-grid-no-corner-contacts" to listOf(RdpMonitorRect(0,0,0,1280,800),RdpMonitorRect(1,1280,0,1280,800),RdpMonitorRect(2,2560,0,1280,800),RdpMonitorRect(3,2,800,1280,800),RdpMonitorRect(4,1282,800,1280,800)))) {
                        probe("baseline-$label",baseline); probe(label,layout)
                    }
                    report.put("protocolProbe",probes)
                } finally { main { handler.post(poll) } }
                return "Monitor topology probe completed."
            }
            report.put("fourOwnedWindowState",JSONArray(session.monitors.map { m ->
                val activity = checkNotNull(m.host.get())
                JSONObject().put("monitor",m.id).put("pose",RdpPanelPose.read(activity).toString())
                    .put("shown",activity.window.decorView.isShown).put("windowVisibility",activity.window.decorView.windowVisibility)
                    .put("lifecycle",activity.lifecycle.currentState.name).put("nativeVisible",presentation(activity).first)
            }))
            File(directory,"results.json").writeText(report.put("stages",stages).toString(2))
            record("four",allowMinimized = true)
            val fourth = session.monitors.last()
            val ids = session.monitors.map { it.id }
            val fourGeometry = session.monitors.map { it.rect }
            Thread.sleep(1500)
            settled(4)
            check(session.monitors.map { it.rect } == fourGeometry) { "Minimized tray pose changed remote monitor geometry" }
            report.put("minimizedTrayPoseIgnored",true)
            // A server-side reset to one monitor previously exposed the atlas in one panel
            // and stranded stale surfaces in the others. Recovery must retain all four IDs.
            val previousRevision = native.getMonitorLayoutState(handle)[7]
            main { check(native.requestMonitorLayout(handle,intArrayOf(0,0,1280,800,1))) }
            await("unsolicited host topology recovered",30_000) {
                var ready = false
                main { ready = native.getMonitorLayoutState(handle)[7] > previousRevision &&
                    native.getMonitorLayoutState(handle).getOrNull(9) == 4L && !session.busy && !session.layoutNeedsRetry }
                ready
            }
            settled(4)
            check(session.monitors.map { it.id } == ids && session.instance.value == handle)
            report.put("unexpectedHostResetRecovered",true)
            // Corrupt an attached local crop without changing the host, as a surface-restore
            // regression fixture. Health checks must repair it without a remote reset loop.
            val restored = session.monitors.last { it.surface != null && it.visible }
            val beforeCropRepair = session.layoutSubmissions
            main { val raw = native.getMonitorLayoutState(handle)
                native.setMonitorSurface(handle,restored.id,restored.surface,0,0,raw[4].toInt(),raw[5].toInt(),0) }
            settled(4)
            Thread.sleep(500)
            check(session.layoutSubmissions == beforeCropRepair)
            report.put("staleLocalCropRepairedWithoutResetLoop",true)
            // Replay the exact shape logged during the wearer's failure: primary at
            // the right of a three-monitor lower row, with the fourth above the left.
            // Reflection freezes only the room-pose fixture; the real host, recovery
            // state machine, crops and input gates are production paths.
            val pointsField = session.javaClass.getDeclaredField("panelPoints").apply { isAccessible = true }
            val poseReadField = session.javaClass.getDeclaredField("nextPoseRead").apply { isAccessible = true }
            val oldPoints = pointsField.get(session)
            val oldWidth = second.requestedWidth; val oldHeight = second.requestedHeight
            val failurePoints = ids.zip(listOf(RdpPanelPoint(2f,0f),RdpPanelPoint(1f,0f),
                RdpPanelPoint(0f,0f),RdpPanelPoint(0f,1f))).toMap()
            val beforeShape = session.layoutSubmissions
            main { session.resize(second,1280,800); session.followPanelLayout()
                pointsField.set(session,failurePoints); poseReadField.setLong(session,Long.MAX_VALUE) }
            await("wearer layout accepted or recovered with functional windows",60_000) {
                var ready = false
                main { pointsField.set(session,failurePoints); poseReadField.setLong(session,Long.MAX_VALUE)
                    ready = session.layoutSubmissions > beforeShape && !session.busy && session.inputReady }
                ready
            }
            settled(4,allowRetry = true)
            report.put("wearerRejectedShapeFunctionalRecovery",true).put("wearerShapeNeededFallback",session.layoutNeedsRetry)
            val expectedWearerGeometry = RdpSpatialLayout.arrange(session.monitors.map { m ->
                RdpMonitorRect(m.id,0,0,m.requestedWidth,m.requestedHeight) },failurePoints)
            check(session.confirmedLayout.toSet() == expectedWearerGeometry.toSet()) { "Host did not apply the wearer spatial layout" }
            report.put("wearerSpatialGeometryAppliedOnHost",true)
            val submissionsAfterRecovery = session.layoutSubmissions
            Thread.sleep(1500)
            check(session.inputReady && session.layoutSubmissions == submissionsAfterRecovery) { "Rejected placement retried in a loop" }
            // The pending spatial layout must not stop local surface restoration.
            main { native.setMonitorSurface(handle,restored.id,restored.surface,0,0,1,1,0) }
            settled(4,allowRetry = true)
            main { pointsField.set(session,oldPoints); poseReadField.setLong(session,0)
                session.resize(second,oldWidth,oldHeight); session.followPanelLayout() }
            settled(4)
            // An oversized request exercises both failed attempts and the actual retry icon
            // without disconnecting or deleting any of the four desktop windows.
            val originalWidth = fourth.requestedWidth; val originalHeight = fourth.requestedHeight
            val beforeFailure = session.layoutSubmissions
            main { session.resize(fourth,8192,8192) }
            await("pending invalid placement keeps confirmed windows interactive") {
                session.layoutSubmissions > beforeFailure && session.busy && session.inputReady
            }
            verifyUiPointer(session.monitors.last { it.visible && it.surface != null },"uiPointerDuringPendingLayout")
            await("automatic retry then actionable layout error") {
                var ready = false
                main { ready = !session.layoutNeedsRetry && !session.busy && session.inputReady && session.canAdd &&
                    session.layoutSubmissions >= beforeFailure + 2 }
                ready
            }
            settled(4,allowRetry = true)
            val recoveredSubmissions = session.layoutSubmissions
            Thread.sleep(1500)
            check(session.inputReady && session.layoutSubmissions == recoveredSubmissions)
            check(session.monitors.map { it.id } == ids)
            report.put("rejectedLayoutRestoresConfirmedInputWithoutLoop",true)
            // Also exercise recovery when the host lost monitors, so adopting the
            // previous geometry is impossible and a fresh simple-row request is needed.
            main { check(native.requestMonitorLayout(handle,intArrayOf(0,0,1280,800,1))) }
            await("rejected placement recovers all monitors through simple row",30_000) {
                var ready = false
                main { ready = session.inputReady && !session.busy && !session.layoutNeedsRetry &&
                    native.getMonitorLayoutState(handle).getOrNull(9) == 4L &&
                    session.layoutSubmissions > recoveredSubmissions }
                ready
            }
            settled(4,allowRetry = true)
            report.put("missingHostMonitorsRecoveredWithSimpleRow",true)
            // Send actual remote input through a recovered secondary's bridge and
            // require new host pixels in its cropped outputs, not only ready flags.
            val inputMonitor = session.monitors.last { it.visible && it.surface != null }
            val beforeInput = mutableMapOf<Long,Bitmap>()
            main { session.monitors.filter { it.visible && it.surface != null }.forEach { m ->
                texture(checkNotNull(m.host.get()).window.decorView)?.bitmap?.let { beforeInput[m.id] = it }
            } }
            try {
                main { inputMonitor.bridge.sendKeyEvent(handle,0x15b,true)
                    inputMonitor.bridge.sendKeyEvent(handle,0x13,true)
                    inputMonitor.bridge.sendKeyEvent(handle,0x13,false)
                    inputMonitor.bridge.sendKeyEvent(handle,0x15b,false) }
                await("recovered secondary input changes remote desktop pixels",10_000) {
                    var changed = 0
                    main { beforeInput.forEach { (id,before) ->
                        val m = session.monitors.first { it.id == id }
                        texture(checkNotNull(m.host.get()).window.decorView)?.bitmap?.let { after ->
                            if (after.width == before.width && after.height == before.height) {
                                for (y in 0 until before.height step 10) for (x in 0 until before.width step 10)
                                    if (before.getPixel(x,y) != after.getPixel(x,y)) changed++
                            }
                            after.recycle()
                        }
                    } }
                    changed > 200
                }
                report.put("recoveredSecondaryRemoteInputChangesPixels",true)
            } finally {
                main { inputMonitor.bridge.sendKeyEvent(handle,1,true); inputMonitor.bridge.sendKeyEvent(handle,1,false) }
                beforeInput.values.forEach { it.recycle() }
            }
            // Returning to the already confirmed placement clears Retry without a
            // redundant host request. Then re-enter failure for the actual toolbar check.
            val beforeReturn = session.layoutSubmissions
            main { pointsField.set(session,session.monitors.associate { m ->
                    m.id to RdpPanelPoint(m.rect.x / 1280f,-m.rect.y / 800f) })
                poseReadField.setLong(session,Long.MAX_VALUE)
                session.resize(fourth,originalWidth,originalHeight) }
            settled(4)
            check(session.canAdd)
            report.put("returnToConfirmedPlacementRestoresAdd",true)
            main { session.resize(fourth,8192,8192) }
            await("recovered Add after second invalid request") { !session.busy && !session.layoutNeedsRetry && session.inputReady && session.layoutSubmissions > beforeReturn }
            session.monitors.forEach { m -> main { m.host.get()?.onWindowFocusChanged(true) } }
            val retryLabel = context.getString(R.string.rdp_layout_auto)
            await("retry layout toolbar action") { node(retryLabel) != null }
            click(retryLabel)
            main { session.resize(fourth,originalWidth,originalHeight) }
            settled(4)
            check(!session.layoutNeedsRetry && session.canAdd && session.monitors.map { it.id } == ids)
            report.put("automaticRetryAndToolbarRecovery",true)
            record("four-recovered",allowMinimized = true)
            await("Add remains available after recovery") { node(context.getString(R.string.rdp_add_desktop)) != null }
            click(context.getString(R.string.rdp_add_desktop))
            settled(5)
            check(session.monitors.take(4).map { it.id } == ids)
            record("five",allowMinimized = true)
            report.put("fifthDesktopAfterRecovery",true)
            val fivePoints = session.monitors.mapIndexed { i,m -> m.id to
                RdpPanelPoint((i % 3).toFloat(),if (i < 3) 1f else 0f) }.toMap()
            val beforeFiveGrid = session.layoutSubmissions
            main { session.followPanelLayout(); pointsField.set(session,fivePoints); poseReadField.setLong(session,Long.MAX_VALUE) }
            await("five-panel grid submitted") { session.layoutSubmissions > beforeFiveGrid }
            settled(5)
            check(session.confirmedLayout.toSet() == RdpSpatialLayout.arrange(session.monitors.map { m ->
                RdpMonitorRect(m.id,0,0,m.requestedWidth,m.requestedHeight) },fivePoints).toSet())
            report.put("fivePanelGridAppliedOnHost",true)
            record("five-grid",allowMinimized = true)
            main { pointsField.set(session,oldPoints); poseReadField.setLong(session,0); session.followPanelLayout() }
            settled(5)

            main { session.monitors.last().host.get()?.finishAndRemoveTask() }
            settled(4)

            val hidden = session.monitors.firstOrNull { m -> !presentation(checkNotNull(m.host.get())).first }
            if (hidden != null) {
                val activity = checkNotNull(hidden.host.get())
                main { LauncherWindowLauncher.setSessionVisible(activity,true) }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("am task focus ${activity.taskId}")).use { it.readBytes() }
                await("restore OS-hidden desktop") { val state = presentation(activity); state.first && state.second }
                settled(4)
                record("four-native-restored",allowMinimized = true)
                report.put("ownedNativeVisibilityRestore",true)
                verifyUiPointer(hidden,"uiPointerAfterNativeRestore")
            }

            val surfaceRestored = session.monitors.last { it.surface != null && it.visible }
            val restoredTexture = checkNotNull(texture(checkNotNull(surfaceRestored.host.get()).window.decorView)?.surfaceTexture)
            main { surfaceRestored.detach(); surfaceRestored.launchDeadline = android.os.SystemClock.elapsedRealtime()-1 }
            Thread.sleep(600)
            check(session.monitors.map { it.id } == ids) { "Minimized presented monitor reservation was removed" }
            main { surfaceRestored.attach(android.view.Surface(restoredTexture)) }
            settled(4)
            report.put("fourMonitorSurfaceRestore",true)
            followPlacement()
            main { fourth.host.get()?.finishAndRemoveTask() }
            settled(3)
            main { session.cameraMuted.value = true }
            await("session camera mute reaches shared native context") { native.isCameraMuted(handle) }
            check(first.session.cameraMuted === second.session.cameraMuted)
            main { session.cameraMuted.value = false }
            await("session camera unmute") { !native.isCameraMuted(handle) }
            report.put("sharedNativeCameraMute", true)
            val third = session.monitors.last()
            main { third.host.get()?.finishAndRemoveTask() }
            settled(2)
            session.monitors.filter { !presentation(checkNotNull(it.host.get())).first }.forEach { m ->
                val activity = checkNotNull(m.host.get())
                main { LauncherWindowLauncher.setSessionVisible(activity,true) }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("am task focus ${activity.taskId}")).use { it.readBytes() }
                await("two-window survivor restored after five-window minimization") { presentation(activity).first }
            }
            settled(2); record("third-closed")
            // Cancel before Home presents the child: its late Intent must not create a new connection.
            main {
                session.addWindow(checkNotNull(second.host.get()))
                val pending = session.monitors.last()
                check(pending !== second)
                session.remove(pending)
            }
            Thread.sleep(1000)
            settled(2)
            check(native.getMonitorLayoutState(handle)[9] == 2L)
            report.put("canceledLaunchLeavesTopology", true)
            val oldHandle = handle
            main { session.reconnect() }
            await("one shared reconnect and both monitor windows", 60_000) {
                var ready = false
                main {
                    if (session.instance.value != 0L && session.bridge !== native) {
                        handle = session.instance.value; native = session.bridge
                    }
                    ready = session.instance.value != 0L && !session.busy && session.monitors.size == 2 &&
                        native.getMonitorLayoutState(handle).getOrNull(9) == 2L
                }
                ready
            }
            settled(2); record("reconnected-two")
            report.put("sharedReconnect", true).put("nativeHandleChangedOnReconnect", oldHandle != handle)
            val capture = checkNotNull(native.displayCapture)
            val oldSpace = native.audioWindowSpace
            main { firstActivity.finishAndRemoveTask() }
            settled(1); record("primary-closed")
            check(session.monitors.single() === second && second.rect.x == 0)
            check(native.displayCapture === capture && !capture.isClosed)
            report.put("captureOwnerSurvivesPrimaryClose", true)
            val survivor = checkNotNull(second.host.get())
            main { survivor.checkAndBindAudioSession() }
            val survivorSpace = QuestSpatialAudio.windowSpace(survivor)
            check(survivorSpace != null && native.audioWindowSpace == survivorSpace)
            report.put("audioAnchorTransferred", true).put("audioAnchorChanged", oldSpace != survivorSpace)
                .put("sharedMediaControls", true).put("oneNativeConnection", true)
            // A transient Surface loss/replacement must retain the same monitor and native context.
            main { second.detach() }
            Thread.sleep(400)
            main { val t = checkNotNull(texture(survivor.window.decorView)?.surfaceTexture); second.attach(android.view.Surface(t)) }
            settled(1); record("surface-restored")
            main { survivor.finishAndRemoveTask() }
            await("last monitor tears down session") { session.instance.value == 0L && session.monitors.isEmpty() }
            check(capture.isClosed)
            report.put("lastWindowClosed", true).put("result", "PASS")
            return "Four same-session desktops; follow-placement toolbar, automatic host/crop recovery, bounded retry and retry toolbar, surface restore, resize, reconnect, shared ownership and teardown."
        } catch (error: Throwable) {
            runtime?.let { session -> main {
                val handle = session.instance.value
                if (handle != 0L) report.put("failedServerLayout",JSONArray(session.bridge.getMonitorLayoutState(handle).toList()))
                    .put("failedSurfaceState",JSONArray(session.bridge.getMonitorSurfaceState(handle).toList()))
                    .put("failedRequestedGeometry",JSONArray(session.monitors.map { listOf(it.id,it.requestedWidth,it.requestedHeight) }))
                    .put("failedLocalGeometry",JSONArray(session.monitors.map { it.rect.toString() }))
            } }
            report.put("result", "FAIL").put("error", error.stackTraceToString())
            throw error
        } finally {
            runtime?.monitors?.toList()?.forEach { m -> main { m.host.get()?.finishAndRemoveTask() ?: m.session.remove(m) } }
            owner?.takeIf { !it.isDestroyed }?.let { a -> main { a.finishAndRemoveTask() } }
            File(directory, "results.json").writeText(report.put("stages", stages).toString(2))
        }
    }
}
