package com.farside.workspace

import android.app.Instrumentation
import androidx.compose.runtime.MutableState

/** Synthetic Home interaction, including when an active keyboard suppresses focus-only signals. */
internal fun revealSessionToolbar(test: Instrumentation, activity: SessionActivity) {
    test.runOnMainSync {
        activity.onWindowFocusChanged(true)
        val field=SessionActivity::class.java.getDeclaredField("windowInteraction").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val interaction=field.get(activity) as MutableState<Long>
        interaction.value+=1L
    }
    Thread.sleep(350) // Let the production reveal animation submit its clickable semantics.
}

/** Dispatch into the owned Activity when Horizon refuses a session accessibility action. */
internal fun tapSessionControl(test: Instrumentation, activity: SessionActivity, description: String) {
    val service=test.uiAutomation.serviceInfo
    service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    test.uiAutomation.serviceInfo=service
    test.uiAutomation.clearCache()
    fun visit(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> =
        listOf(node)+(0 until node.childCount).flatMap {node.getChild(it)?.let(::visit).orEmpty()}
    var matches=emptyList<android.view.accessibility.AccessibilityNodeInfo>()
    val deadline=android.os.SystemClock.uptimeMillis()+5000
    do {
        test.uiAutomation.clearCache()
        val displays=test.uiAutomation.windowsOnAllDisplays
        val windows=(0 until displays.size()).flatMap {displays.valueAt(it)}
        java.io.File(test.targetContext.cacheDir,"session-controls.private.txt").writeText(
            "Activity display=${activity.display?.displayId}\n"+windows.joinToString("\n") {w ->
                "display=${w.displayId} title=${w.title}: "+w.root?.let(::visit).orEmpty()
                    .filter {it.packageName?.toString()==test.targetContext.packageName}
                    .joinToString {"${it.contentDescription}/${it.text}"}})
        matches=windows.filter {it.displayId==activity.display?.displayId}.flatMap {it.root?.let(::visit).orEmpty()}
            .filter {it.packageName?.toString()==test.targetContext.packageName && it.contentDescription?.toString()==description}
        if(matches.isNotEmpty()) break
        Thread.sleep(150)
    } while(android.os.SystemClock.uptimeMillis()<deadline)
    val bitmap=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
    val copied=java.util.concurrent.CountDownLatch(1)
    var result=-1
    test.runOnMainSync {android.view.PixelCopy.request(activity.window,bitmap,{result=it;copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
    check(copied.await(5,java.util.concurrent.TimeUnit.SECONDS) && result==android.view.PixelCopy.SUCCESS)
    java.io.File(test.targetContext.cacheDir,"session-control-${activity.rdpInstance!=0L}.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    bitmap.recycle()
    val node=checkNotNull(matches.firstOrNull {it.isVisibleToUser} ?: matches.firstOrNull()) {"Owned session control missing: $description"}
    val bounds=android.graphics.Rect().also(node::getBoundsInScreen)
    val down=android.os.SystemClock.uptimeMillis()
    for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
        test.runOnMainSync {
            val origin=IntArray(2).also(activity.window.decorView::getLocationOnScreen)
            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,bounds.exactCenterX()-origin[0],bounds.exactCenterY()-origin[1],0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {activity.dispatchTouchEvent(event)} finally {event.recycle()}
        }
        Thread.sleep(80)
    }
    Thread.sleep(250)
}
