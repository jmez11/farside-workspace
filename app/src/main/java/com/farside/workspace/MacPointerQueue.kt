package com.farside.workspace

/** Keep the latest motion while preserving the original position of every button edge. */
internal class MacPointerQueue(private val enqueue:(Runnable)->Unit,private val write:(Point)->Unit) {
    private sealed interface Work
    data class Point(val x:Int,val y:Int,val buttons:Int,val edge:Boolean):Work
    private data class Barrier(val action:Runnable):Work
    private val pending=java.util.ArrayDeque<Work>()
    private var buttons=0
    private var scheduled=false
    private var closed=false
    var coalesced=0L;private set
    @Synchronized fun offer(x:Int,y:Int,mask:Int) {
        if(closed) return
        val edge=buttons!=mask || mask and 0xf8!=0
        buttons=mask
        val last=pending.peekLast()
        if(!edge && last is Point && !last.edge && last.buttons==mask) { pending.removeLast();coalesced++ }
        pending.addLast(Point(x,y,mask,edge))
        schedule()
    }
    @Synchronized fun barrier(action:Runnable) {
        if(closed) return
        pending.addLast(Barrier(action))
        schedule()
    }
    private fun schedule() {
        if(!scheduled) {
            scheduled=true
            try { enqueue(Runnable(::drain)) } catch(_:java.util.concurrent.RejectedExecutionException) { close() }
        }
    }
    private fun drain() {
        repeat(32) {
            val point=synchronized(this) {
                if(closed || pending.isEmpty()) { scheduled=false;return }
                pending.removeFirst()
            }
            when(point) {
                is Point -> write(point)
                is Barrier -> point.action.run()
            }
        }
        synchronized(this) {
            if(!closed && pending.isNotEmpty()) {
                try { enqueue(Runnable(::drain)) } catch(_:java.util.concurrent.RejectedExecutionException) { close() }
            } else scheduled=false
        }
    }
    @Synchronized fun close() { closed=true;pending.clear();scheduled=false }
}
