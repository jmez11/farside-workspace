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
        report.put("layoutAndPointerBounds", true).put("signedLayoutsAndTransferredGesture",true)
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
            fun settled(count: Int) {
                await("$count committed monitors and posted surfaces") {
                    var ready = false
                    main {
                        val raw = native.getMonitorLayoutState(handle)
                        val surfaces = native.getMonitorSurfaceState(handle).toList().chunked(8)
                        ready = session.instance.value == handle && session.bridge === native && !session.busy &&
                            session.monitors.size == count && raw.getOrNull(9) == count.toLong() && surfaces.size == count &&
                            surfaces.all { it[7] > 0 && it[5] == raw[7] && it[6] == raw[7] }
                    }
                    ready
                }
            }
            fun record(label: String) {
                settled(session.monitors.size)
                val stage = JSONObject().put("stage", label).put("sameNativeConnection", session.instance.value == handle)
                val raw = native.getMonitorLayoutState(handle)
                stage.put("serverLayout", JSONArray(raw.toList())).put("surfaces", JSONArray(native.getMonitorSurfaceState(handle).toList()))
                val windows = JSONArray()
                session.monitors.toList().forEach { monitor ->
                    val activity = checkNotNull(monitor.host.get())
                    var bitmap: Bitmap? = null
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
                    // These diagnostic fields require shell identity on SDK 207; ordinary
                    // owned-window lookup is sufficient for the production audio token only.
                    test.uiAutomation.adoptShellPermissionIdentity()
                    try {
                        await("$label native window ${monitor.id} visible/drawn", 10_000) {
                            val managerClass = Class.forName("horizonos.app.volumetricwindow.VolumetricWindowManager")
                            val manager = activity.getSystemService(managerClass)
                            val token = Class.forName("horizonos.app.ActivityExt").getMethod("getVolumetricWindowToken", android.app.Activity::class.java).invoke(null, activity)
                            val info = managerClass.getMethod("getWindowInfo", Class.forName("horizonos.view.VolumetricWindowToken")).invoke(manager, token)
                            visible = info.javaClass.getMethod("isVisible").invoke(info) == true
                            drawn = info.javaClass.getMethod("isDrawn").invoke(info) == true
                            visible && drawn
                        }
                    } finally { test.uiAutomation.dropShellPermissionIdentity() }
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
            fun arrangementAction(resource: Int) {
                val activity = checkNotNull(second.host.get())
                // Reveal this panel's toolbar; actual control dispatch uses its own display.
                main { activity.onWindowFocusChanged(true) }
                val display = activity.display!!.displayId
                val action = context.getString(R.string.rdp_arrange_desktops)
                await("arrangement toolbar") { node(action,display) != null }
                click(action,display)
                val button = context.getString(resource)
                await("arrangement dialog") { node(button,display) != null }
                click(button,display)
            }
            fun placement(label: String, dx: Int, dy: Int) {
                arrangementAction(if (dx < 0) R.string.rdp_layout_left else if (dx > 0) R.string.rdp_layout_right else if (dy > 0) R.string.rdp_layout_above else R.string.rdp_layout_below)
                await(label) { var ready = false; main { ready = !session.busy && session.inputReady &&
                    (if (dx < 0) second.rect.x < first.rect.x else if (dx > 0) second.rect.x > first.rect.x else if (dy > 0) second.rect.y < first.rect.y else second.rect.y > first.rect.y) }; ready }
                record(label)
                check(RdpMonitorLayouts.matches(RdpMonitorLayouts.normalized(native.getMonitorLayoutState(handle)),session.monitors.map { it.rect }))
            }
            placement("second-left",-1,0)
            placement("second-above",0,1)
            placement("second-below",0,-1)
            placement("second-right",1,0)
            report.put("liveFourDirections",true)
            nativeDrag(first,second)
            arrangementAction(R.string.rdp_layout_auto)
            report.put("arrangementToolbarAndDialog",true)
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
            // Hold fixture geometry stable for the remaining lifecycle checks.
            main { session.arrange(mapOf(first.id to RdpPanelPoint(0f,0f),second.id to RdpPanelPoint(1f,0f))) }
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
            main { session.cameraMuted.value = true }
            await("session camera mute reaches shared native context") { native.isCameraMuted(handle) }
            check(first.session.cameraMuted === second.session.cameraMuted)
            main { session.cameraMuted.value = false }
            await("session camera unmute") { !native.isCameraMuted(handle) }
            report.put("sharedNativeCameraMute", true)
            val third = session.monitors.last()
            main { third.host.get()?.finishAndRemoveTask() }
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
            return "Toolbar opened real same-session monitor windows; 1->2->3->2->1, secondary resize, per-monitor crops, pointer bounds, shared mute, primary audio transfer, transient surface reattach, final teardown."
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("error", error.stackTraceToString())
            throw error
        } finally {
            runtime?.monitors?.toList()?.forEach { m -> main { m.host.get()?.finishAndRemoveTask() ?: m.session.remove(m) } }
            owner?.takeIf { !it.isDestroyed }?.let { a -> main { a.finishAndRemoveTask() } }
            File(directory, "results.json").writeText(report.put("stages", stages).toString(2))
        }
    }
}
