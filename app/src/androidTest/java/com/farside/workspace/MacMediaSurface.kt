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
internal class MacMediaSurface(private val test: Instrumentation, profile: ConnectionProfile? = null) : Closeable {
    private val ready=CountDownLatch(1)
    private val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    private val connected=CountDownLatch(1)
    @Volatile var bridge: VncBridge?=null; private set
    @Volatile var handle=0L; private set
    private lateinit var view: TextureView
    lateinit var surface: Surface; private set
    init {
        if(profile!=null) test.runOnMainSync { activity.setContent { MaterialTheme { SpatialScene {
            VncSessionScreen(profile,onInstanceCreated={ handle=it;connected.countDown() },
                onClose={ activity.finish() },onBridgeCreated={ bridge=it })
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
    fun awaitConnection():Boolean=connected.await(180,TimeUnit.SECONDS) && handle!=0L
    private fun texture(root:View):TextureView? {
        if(root is TextureView) return root
        if(root is ViewGroup) for(i in 0 until root.childCount) texture(root.getChildAt(i))?.let { return it }
        return null
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
    override fun close() { if(::surface.isInitialized) surface.release();test.runOnMainSync { activity.finish() } }
}
