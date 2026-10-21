package com.farside.workspace

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit user input only: one submission per authenticated session, never an auth fallback. */
internal class MacSavedPasswordInput(private val password:String) {
    private val submitted=AtomicBoolean()
    val attempted:Boolean get()=submitted.get()

    fun submit(active:()->Boolean, enqueue:(Runnable)->Unit, key:(Int,Boolean)->Unit,
        pause:()->Unit={ Thread.sleep(60) }, failed:()->Unit={}, beforeInput:()->Unit={}):Boolean {
        if(!active() || password.isEmpty() || password.any { Character.isISOControl(it) }) return false
        if(!submitted.compareAndSet(false,true)) return false
        val symbols=password.codePoints().toArray()
        try {
            enqueue(Runnable {
                try {
                    if(!active()) return@Runnable
                    beforeInput()
                    for(code in symbols) {
                        if(!active()) return@Runnable
                        val symbol=if(code<=255) code else 0x01000000 or code
                        key(symbol,true);key(symbol,false);pause()
                    }
                    if(active()) { key(0xff0d,true);key(0xff0d,false) }
                } catch(_:Exception) { failed() }
                finally { symbols.fill(0) }
            })
            return true
        } catch(_:RejectedExecutionException) { symbols.fill(0);return false }
    }
}
