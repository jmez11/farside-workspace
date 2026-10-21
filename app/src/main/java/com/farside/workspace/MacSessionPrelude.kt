package com.farside.workspace

import java.io.DataInputStream
import java.io.EOFException
import java.net.SocketTimeoutException

/** SessionSelect is optional when the authenticated user already owns the console. */
internal object MacSessionPrelude {
    fun optionalHeader(input:DataInputStream, normalTimeout:Int, setTimeout:(Int)->Unit):ByteArray? {
        val first=try {
            setTimeout(2000)
            try { input.read() } catch(_:SocketTimeoutException) { return null }
        } finally { setTimeout(normalTimeout) }
        if(first<0) throw EOFException("Mac closed before streaming prelude")
        // Once any header byte arrived, a truncated prefix is a protocol failure, not absence.
        return byteArrayOf(first.toByte(),input.readByte())
    }
}
