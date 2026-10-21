package com.farside.workspace

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Serializes new streaming authentications per host, including after a reconnect. */
internal class MacAuthenticationGate(context: Context, host: String, private val cancelled: ()->Boolean) {
    companion object { private val monitor=Any() }
    private val key=MessageDigest.getInstance("SHA-256").digest(host.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    private val stamp=File(context.cacheDir,"mac-auth-$key")
    fun await() = synchronized(monitor) {
        val previous=stamp.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        var remaining=(5_000-(System.currentTimeMillis()-previous)).coerceIn(0,5_000)
        while(remaining>0) {
            check(!cancelled()) { "Session closed" }
            val pause=minOf(remaining,1000);Thread.sleep(pause);remaining-=pause
        }
        check(!cancelled()) { "Session closed" }
        stamp.writeText(System.currentTimeMillis().toString())
    }
}
