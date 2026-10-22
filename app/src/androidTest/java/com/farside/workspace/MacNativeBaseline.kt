package com.farside.workspace

import android.app.Instrumentation
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.farside.workspace.data.*
import java.util.concurrent.atomic.AtomicLong

/** Explicit ordinary VNC baseline; bypasses the production automatic streaming policy. */
internal class MacNativeBaseline(private val test:Instrumentation) {
    fun run(id:String):String {
        val pm=ProfileManager(test.targetContext)
        val profile=pm.getProfiles().single { it.id==id && it.type==ConnectionType.VNC }
        val credential=pm.getCredentials().single { it.id==profile.credentialId }
        val gate=MacAuthenticationGate { false }
        val thread=HandlerThread("MacBaselineFrames").apply { start() }
        val images=ImageReader.newInstance(1920,1080,PixelFormat.RGBA_8888,3)
        val frames=AtomicLong()
        images.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.use { frames.incrementAndGet() } },Handler(thread.looper))
        val bridge=VncBridge();var handle=0L
        val tunnel=if(profile.useCloudflare || profile.proxyType==ProxyType.CLOUDFLARED)
            com.farside.workspace.tunnel.CloudflaredTunnel(test.targetContext) else null
        try {
            val port=tunnel?.start(profile.host,profile.cloudflaredParameters,{},{},accessSubcommand="tcp") ?: profile.port
            handle=bridge.connect(if(tunnel!=null) "127.0.0.1" else profile.host,port,credential.pass,images.surface,credential.user,
                VncAuthMode.MAC_USER,profile.copy(expMacHevc=false),macAuthenticationGate={gate.await()})
            check(handle!=0L && bridge.isConfirmedMac && !bridge.isMacHevc)
            check(bridge.canSubmitSavedMacPassword(handle)) { "Standard Mac VNC lost saved-password unlock" }
            val deadline=android.os.SystemClock.uptimeMillis()+5000
            while(frames.get()==0L && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
            check(frames.get()>0 && bridge.getDesktopWidth(handle)>0 && bridge.getDesktopHeight(handle)>0)
            return "PASS standard Mac VNC authentication, rendered frames=${frames.get()}, saved-password unlock available without typing"
        } finally {
            if(handle!=0L) bridge.disconnect(handle)
            tunnel?.close()
            images.close();thread.quitSafely();thread.join(1000)
        }
    }
}
