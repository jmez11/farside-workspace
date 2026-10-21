package com.farside.workspace

/** Debug-only stage counters, never key values, text, credentials or packet contents. */
internal object MacInputTrace {
    private val counts=mutableMapOf<String,Long>()
    private val printed=mutableMapOf<String,Long>()
    @Synchronized fun event(stage:String) {
        if(!BuildConfig.DEBUG) return
        val count=(counts[stage] ?: 0)+1;counts[stage]=count
        val now=android.os.SystemClock.uptimeMillis()
        if(count==1L || now-(printed[stage] ?: 0)>=1000) {
            printed[stage]=now
            android.util.Log.d("MacInputTrace","$stage count=$count")
        }
    }
}
