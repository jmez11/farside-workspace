package com.farside.workspace

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Apple SessionSelect login identity is explicit; preserve either saved account alias verbatim. */
internal object MacSessionSelect {
    private fun slot(value:String):ByteArray {
        val bytes=value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size<64 && '\u0000' !in value) { "Apple login value does not fit its NUL-terminated slot" }
        return ByteArray(64).also { bytes.copyInto(it) }
    }
    fun virtualLogin(username:String,password:String,key:ByteArray):ByteArray {
        require(key.size==16)
        val cipher=Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES")) }
        val user=slot(username);val pass=slot(password)
        try {
            return ByteBuffer.allocate(202).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(200);putShort(2);putShort(0);putShort(0);put(2);put(0)
                put(ByteArray(64));put(cipher.doFinal(user));put(cipher.doFinal(pass))
            }.array()
        } finally { user.fill(0);pass.fill(0) }
    }
    fun consoleLogin(command:Int,username:String):ByteArray {
        require(command in 0..1)
        return ByteBuffer.allocate(74).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(72);putShort(2);putShort(0);putShort(0);put(command.toByte());put(0);put(slot(username))
        }.array()
    }
}
