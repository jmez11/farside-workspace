package com.farside.workspace

import android.app.Instrumentation
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Transparent owned TCP relay drops UDP: production UDP settings must retain usable TCP. */
internal object RdpUdpFallbackVerification {
    fun run(test: Instrumentation): String {
        val context=test.targetContext
        val manager=ProfileManager(context)
        val profile=manager.getProfiles().first {it.type==ConnectionType.RDP && it.remoteApp.isNullOrBlank()}
        val credential=profile.credentialId?.let {id -> manager.getCredentials().first {it.id==id}}
        val host=kotlinx.coroutines.runBlocking {com.farside.workspace.utils.NetworkScanner.resolveRdpHost(profile.host)}
        val loopback=InetAddress.getByName("127.0.0.1")
        val listener=ServerSocket(0,1,loopback)
        val udp=DatagramSocket(listener.localPort,loopback)
        val datagrams=AtomicInteger()
        val frames=AtomicInteger()
        val sockets=java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val udpWorker=thread(name="owned-udp-blackhole",isDaemon=true) {
            val packet=DatagramPacket(ByteArray(65535),65535)
            while(!udp.isClosed) try {packet.length=packet.data.size;udp.receive(packet);datagrams.incrementAndGet()}
                catch(_:java.io.IOException) {break}
        }
        val relay=thread(name="owned-rdp-tcp-relay",isDaemon=true) {
            try {
                val client=listener.accept().also {sockets.add(it)}
                val server=Socket(host,profile.port).also {sockets.add(it)}
                val upload=thread(name="owned-rdp-upload",isDaemon=true) {
                    try {client.getInputStream().copyTo(server.getOutputStream())}
                    catch(_:java.io.IOException) {} finally {runCatching {server.shutdownOutput()}}
                }
                try {server.getInputStream().copyTo(client.getOutputStream())}
                finally {runCatching {client.shutdownOutput()};upload.join(1000)}
            } catch(_:java.io.IOException) {}
        }
        val images=HandlerThread("owned-rdp-fallback-images").apply {start()}
        val reader=ImageReader.newInstance(1920,1080,PixelFormat.RGBA_8888,3)
        reader.setOnImageAvailableListener({source ->
            source.acquireLatestImage()?.use {image ->
                val plane=image.planes[0]
                val colors=mutableSetOf<Int>()
                for(y in 0 until image.height step 60) for(x in 0 until image.width step 80) {
                    val offset=y*plane.rowStride+x*plane.pixelStride
                    if(offset+4<=plane.buffer.limit()) colors+=plane.buffer.getInt(offset)
                }
                if(colors.size>1) frames.incrementAndGet()
            }
        },Handler(images.looper))
        val bridge=FreeRDPBridge()
        val certificates=RdpCertificateVerifier(context,profile.host,profile.port,{})
        var handle=0L
        try {
            // The relay changes the socket peer, never the certificate's expected server identity.
            handle=bridge.connect("127.0.0.1",listener.localPort,credential?.user ?: profile.user,
                credential?.pass ?: profile.pass,null,false,false,false,false,reader.surface,
                profile.host.removeSurrounding("[","]"),certificates)
            check(handle!=0L) {"TCP relay failed to connect the saved RDP server"}
            check(RdpTransportVerification.udpEnabled(handle)) {"Direct route did not default to UDP"}
            val deadline=android.os.SystemClock.uptimeMillis()+35000
            var state=bridge.getMultitransportState(handle)
            while(android.os.SystemClock.uptimeMillis()<deadline) {
                state=bridge.getMultitransportState(handle)
                if(datagrams.get()>0 && state==0 && frames.get()>0) break
                Thread.sleep(100)
            }
            check(datagrams.get()>0) {"Server did not request UDP; unavailable-UDP fallback unverified"}
            check(state==0 && frames.get()>0) {"Unavailable UDP did not retain a rendered TCP desktop: state=$state frames=${frames.get()}"}
            check(RdpTransportVerification.udpEnabled(handle)) {"Fallback rewrote transport policy"}
            return "PASS: automatic direct UDP attempted (${datagrams.get()} datagrams dropped by owned loopback fixture), negotiation failed, TCP continued with ${frames.get()} nonblank desktop frames; no profile or user transport setting."
        } finally {
            if(handle!=0L) bridge.disconnect(handle)
            certificates.close()
            reader.setOnImageAvailableListener(null,null)
            images.quitSafely();images.join(3000);reader.close()
            udp.close();listener.close()
            synchronized(sockets) {sockets.forEach {runCatching {it.close()}}}
            udpWorker.join(1000);relay.join(1000)
        }
    }
}
