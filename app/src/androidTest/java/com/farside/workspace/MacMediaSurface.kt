package com.farside.workspace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.farside.workspace.data.ConnectionProfile
import metavrx.layout.compose.SpatialScene
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real displayed TextureView consumes codec frames; a detached SurfaceTexture cannot prove pixels. */
internal class MacMediaSurface(private val test: Instrumentation, profile: ConnectionProfile? = null,
    realSessionWindow:Boolean=false) : Closeable {
    private val ready=CountDownLatch(1)
    private val transientProfile=if(realSessionWindow) requireNotNull(profile).copy(id=java.util.UUID.randomUUID().toString()) else null
    private val activity:androidx.activity.ComponentActivity=run {
        val pm=com.farside.workspace.data.ProfileManager(test.targetContext)
        if(transientProfile!=null) { pm.addProfile(transientProfile);check(test.targetContext.getSharedPreferences("farside_profiles",0).edit().commit()) }
        val component=if(transientProfile!=null) SessionActivity::class.java else MainActivity::class.java
        val monitor=test.addMonitor(component.name,null,false)
        try {
            val intent=if(transientProfile!=null) Intent(test.targetContext,SessionActivity::class.java)
                .putExtra("profileId",transientProfile.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            else Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            test.targetContext.startActivity(intent)
            checkNotNull(test.waitForMonitorWithTimeout(monitor,15000)) { "Horizon did not open media fixture" } as androidx.activity.ComponentActivity
        } catch(e:Exception) { transientProfile?.let { pm.deleteProfile(it.id) };throw e }
        finally { test.removeMonitor(monitor) }
    }
    private val connected=CountDownLatch(1)
    @Volatile private var callbackBridge: VncBridge?=null
    @Volatile private var callbackHandle=0L
    val bridge:VncBridge? get()=(activity as? SessionActivity)?.vncBridge ?: callbackBridge
    val handle:Long get()=(activity as? SessionActivity)?.vncInstance ?: callbackHandle
    private lateinit var view: TextureView
    lateinit var surface: Surface; private set
    init {
        if(realSessionWindow) { /* SessionActivity owns the normal connection/window lifecycle. */ }
        else if(profile!=null) test.runOnMainSync { activity.setContent { MaterialTheme { SpatialScene {
            VncSessionScreen(profile,onInstanceCreated={ callbackHandle=it;connected.countDown() },
                onClose={ activity.finish() },onBridgeCreated={ callbackBridge=it })
        } } } } else test.runOnMainSync {
            view=TextureView(activity)
            view.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture,w:Int,h:Int) {
                    surface=Surface(texture);ready.countDown()
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,w:Int,h:Int) {}
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture)=true
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            }
            activity.setContentView(view)
        }
        if(profile==null) check(ready.await(10,TimeUnit.SECONDS)) { "Actual media TextureView unavailable" }
    }
    fun awaitConnection():Boolean {
        if(transientProfile==null) return connected.await(180,TimeUnit.SECONDS) && handle!=0L
        val until=android.os.SystemClock.uptimeMillis()+180000
        while(handle==0L && !activity.isFinishing && android.os.SystemClock.uptimeMillis()<until) Thread.sleep(50)
        return handle!=0L
    }
    private fun texture(root:View):TextureView? {
        if(root is TextureView) return root
        if(root is ViewGroup) for(i in 0 until root.childCount) texture(root.getChildAt(i))?.let { return it }
        return null
    }
    fun resize(w:Int,h:Int) {
        require(w in 200..2500 && h in 200..2000)
        test.uiAutomation.executeShellCommand("am task resize ${activity.taskId} 0 0 $w $h").close()
    }
    fun hover(x:Float,y:Float) {
        test.runOnMainSync {
            val root=activity.window.decorView
            val event=android.view.MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_HOVER_MOVE,root.width*x,root.height*y,0)
            event.source=android.view.InputDevice.SOURCE_MOUSE
            try { activity.dispatchGenericMotionEvent(event) } finally { event.recycle() }
        }
    }
    fun ime(delete:Boolean) {
        val session=activity as SessionActivity
        if(session.keyboardEditorView==null) {
            val info=test.uiAutomation.serviceInfo
            info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            test.uiAutomation.serviceInfo=info
            test.uiAutomation.clearCache()
            var button:android.view.accessibility.AccessibilityNodeInfo?=null
            fun visit(n:android.view.accessibility.AccessibilityNodeInfo) {
                if(n.contentDescription?.toString()==test.targetContext.getString(R.string.keyboard) && n.isVisibleToUser) button=n
                repeat(n.childCount) { n.getChild(it)?.let(::visit) }
            }
            val windows=test.uiAutomation.windowsOnAllDisplays
            for(i in 0 until windows.size()) windows.valueAt(i).forEach { it.root?.let(::visit) }
            var node=checkNotNull(button) { "Keyboard toolbar action missing" }
            while(!node.isClickable && node.parent!=null) node=checkNotNull(node.parent)
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(1500)
        }
        test.runOnMainSync {
            val view=if(session.keyboardPanelEditorHasFocus) session.keyboardPanelEditorView else session.keyboardEditorView
            val connection=checkNotNull(view?.onCreateInputConnection(android.view.inputmethod.EditorInfo())) { "No actual IME InputConnection" }
            check(if(delete) connection.deleteSurroundingText(1,0) else connection.commitText("x",1))
        }
    }
    fun captureWindow(name:String) {
        val latch=CountDownLatch(1);var code=-1
        lateinit var bitmap:Bitmap
        test.runOnMainSync {
            bitmap=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(activity.window,bitmap,{ code=it;latch.countDown() },android.os.Handler(android.os.Looper.getMainLooper()))
        }
        check(latch.await(5,TimeUnit.SECONDS) && code==android.view.PixelCopy.SUCCESS)
        try { File(test.targetContext.cacheDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }
    fun key(code:Int) {
        test.runOnMainSync {
            for(action in listOf(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP)) {
                activity.dispatchKeyEvent(android.view.KeyEvent(action,code))
            }
        }
    }
    fun touch(x:Float,y:Float,down:Boolean,started:Long) {
        test.runOnMainSync {
            val root=activity.window.decorView
            val event=android.view.MotionEvent.obtain(started,android.os.SystemClock.uptimeMillis(),
                if(down) android.view.MotionEvent.ACTION_DOWN else android.view.MotionEvent.ACTION_UP,
                root.width*x,root.height*y,0)
            try { check(activity.dispatchTouchEvent(event)) { "Production window rejected touch" } } finally { event.recycle() }
        }
    }
    fun capture(name:String): Boolean {
        var bitmap: Bitmap?=null
        test.runOnMainSync { bitmap=texture(activity.window.decorView)?.getBitmap(960,540) }
        val image=bitmap ?: return false
        try {
            val target=File(test.targetContext.cacheDir,name)
            val pending=File(test.targetContext.cacheDir,"$name.pending")
            pending.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            check(pending.renameTo(target)) { "Could not publish completed test capture" }
            val colors=mutableSetOf<Int>()
            for(y in 20 until image.height step 30) for(x in 20 until image.width step 30) colors+=image.getPixel(x,y)
            return colors.size>16
        } finally { image.recycle() }
    }
    override fun close() {
        if(::surface.isInitialized) surface.release()
        val closingPipeline=if(transientProfile!=null) bridge?.macMediaPipeline else null
        test.runOnMainSync { if(transientProfile!=null) activity.finishAndRemoveTask() else activity.finish() }
        if(transientProfile!=null) {
            val until=android.os.SystemClock.uptimeMillis()+10000
            while((!activity.isDestroyed || closingPipeline?.audio!=null) && android.os.SystemClock.uptimeMillis()<until) Thread.sleep(50)
            check(activity.isDestroyed && closingPipeline?.audio==null) { "Streaming Activity/media did not finish teardown" }
        }
        transientProfile?.let { com.farside.workspace.data.ProfileManager(test.targetContext).deleteProfile(it.id)
            check(test.targetContext.getSharedPreferences("farside_profiles",0).edit().commit()) }
    }
}
