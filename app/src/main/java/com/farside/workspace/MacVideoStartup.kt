package com.farside.workspace

/** Allow a bounded decoder warmup only after authenticated video has arrived. */
internal object MacVideoStartup {
    fun await(frame:()->Boolean, progress:()->Boolean, initialMs:Long=3500, progressMs:Long=10000,
        now:()->Long={System.nanoTime()/1_000_000}, pause:(Long)->Unit={Thread.sleep(it)},
        cancelled:()->Boolean={false}):Boolean {
        require(initialMs>0 && progressMs>=initialMs)
        val start=now()
        var deadline=start+initialMs
        while(now()<deadline) {
            if(cancelled()) return false
            if(frame()) return true
            if(progress()) deadline=start+progressMs
            pause(20)
        }
        return !cancelled() && frame()
    }
}
