package com.farside.workspace

import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A stalled companion channel must not hold the VNC fallback behind SSH's close timeout. */
internal object MacRelayStartup {
    fun port(readLine:()->String?, abort:()->Unit, timeoutMs:Long=4000):Int {
        val task=FutureTask {
            val line=readLine() ?: throw IOException("Companion relay ended before ready")
            val port=if(line.startsWith("READY ")) line.removePrefix("READY ").toIntOrNull() else null
            if(port==null || port !in 1..65535) throw IOException("Invalid companion relay reply")
            port
        }
        Thread(task,"MacRelayStartup").apply { isDaemon=true;start() }
        var ready=false
        try { return task.get(timeoutMs,TimeUnit.MILLISECONDS).also { ready=true } }
        catch(error:InterruptedException) { Thread.currentThread().interrupt();throw error }
        catch(error:Exception) { throw IOException("Companion media relay unavailable",error) }
        finally {
            if(!ready) {
                task.cancel(true)
                Thread({ runCatching(abort) },"MacRelayAbort").apply { isDaemon=true;start() }
            }
        }
    }
}
