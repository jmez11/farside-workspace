package com.farside.workspace

import net.schmizz.sshj.SSHClient
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Resolve only the SSH account when the same Mac confirms its saved VNC alias. */
internal object MacSessionIdentity {
    fun canonical(saved:String,reply:String):String {
        val names=reply.lineSequence().map { it.trim() }.toList()
        if(names.size!=2 || !names[0].matches(Regex("[A-Za-z0-9._-]+"))) return saved
        return if(saved==names[0] || saved==names[1]) names[0] else saved
    }
    fun resolve(client:SSHClient,saved:String):String {
        val channel=client.startSession()
        val task=FutureTask {
            val command=channel.exec("/usr/bin/id -un && /usr/bin/id -F")
            val text=command.inputStream.bufferedReader().readText().trimEnd()
            command.join(1,TimeUnit.SECONDS)
            if(command.exitStatus==0) canonical(saved,text) else saved
        }
        Thread(task,"MacSessionIdentity").apply { isDaemon=true;start() }
        return try { task.get(4000,TimeUnit.MILLISECONDS) }
        catch(error:InterruptedException) { Thread.currentThread().interrupt();throw error }
        catch(_:Exception) { saved }
        finally {
            task.cancel(true)
            Thread({runCatching { channel.close() }},"MacIdentityClose").apply { isDaemon=true;start() }
        }
    }
}
