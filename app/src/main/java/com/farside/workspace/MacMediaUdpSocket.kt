package com.farside.workspace

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** One kernel receiver per Apple media port. Each session authenticates its own SRTP keys.
 * SO_REUSEADDR with independent receivers lets a second Mac steal the first Mac's packets.
 * Fan-out preserves fixed-port/NAT behavior and also handles multiple loopback SSH relays.
 */
internal class MacMediaUdpSocket private constructor(private val hub:Hub,private val timeout:Int):Closeable {
    val droppedPackets=java.util.concurrent.atomic.AtomicLong()
    private val packets=ArrayBlockingQueue<DatagramPacket>(256)
    @Volatile private var closed=false
    val isClosed get()=closed
    val localPort get()=hub.socket.localPort
    val receiveBufferSize get()=hub.socket.receiveBufferSize
    fun receive(target:DatagramPacket) {
        if(closed) throw SocketException("Subscription closed")
        val next=packets.poll(timeout.toLong(),TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException()
        if(closed) throw SocketException("Subscription closed")
        val length=minOf(target.length,next.length)
        next.data.copyInto(target.data,target.offset,next.offset,next.offset+length)
        target.length=length;target.address=next.address;target.port=next.port
    }
    fun send(packet:DatagramPacket) {if(closed) throw SocketException("Subscription closed");hub.socket.send(packet)}
    private fun offer(packet:DatagramPacket) {
        if(!closed && !packets.offer(packet)) {if(packets.poll()!=null) droppedPackets.incrementAndGet();packets.offer(packet)}
    }
    override fun close() = synchronized(hubs) {
        if(!closed) {
            closed=true;packets.clear();packets.offer(DatagramPacket(ByteArray(0),0))
            hub.subscribers.remove(this)
            if(hub.subscribers.isEmpty()) {hubs.remove(hub.port);hub.socket.close()}
        }
    }
    private class Hub(val port:Int,bindNetwork:(DatagramSocket)->Unit) {
        val socket=DatagramSocket(null).apply {
            try {reuseAddress=false;receiveBufferSize=4*1024*1024;bindNetwork(this);bind(InetSocketAddress(this@Hub.port))}
            catch(e:Exception) {close();throw e}
        }
        val subscribers=CopyOnWriteArrayList<MacMediaUdpSocket>()
        fun start() {Thread({
            val data=ByteArray(65536)
            while(!socket.isClosed) {
                try {
                    val packet=DatagramPacket(data,data.size);socket.receive(packet)
                    val copy=DatagramPacket(packet.data.copyOfRange(packet.offset,packet.offset+packet.length),packet.length,packet.address,packet.port)
                    subscribers.forEach {it.offer(copy)}
                } catch(e:Exception) {if(socket.isClosed) break}
            }
        },"MacMediaUdp-$port").apply {isDaemon=true;start()}}
    }
    companion object {
        private val hubs=mutableMapOf<Int,Hub>()
        fun open(port:Int,timeout:Int,bindNetwork:(DatagramSocket)->Unit={}):MacMediaUdpSocket = synchronized(hubs) {
            val existing=hubs[port]
            val hub=existing ?: Hub(port,bindNetwork).also {hubs[port]=it}
            MacMediaUdpSocket(hub,timeout).also {hub.subscribers.add(it);if(existing==null) hub.start()}
        }
    }
}
