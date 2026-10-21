package com.farside.workspace

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Select only a complete, sole display explicitly returned by Apple's negotiated layout. */
internal data class MacDisplaySelection(val id:Long,val width:Int,val height:Int) {
    fun request(firstIndex:Boolean=false):ByteArray=ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
        put(9);put(0);putShort(1);putInt(if(firstIndex) 0 else id.toInt())
        putShort(0);putShort(0);putShort(width.toShort());putShort(height.toShort())
    }.array()
    companion object {
        fun parse(data:ByteArray):MacDisplaySelection? {
            if(data.size!=76) return null
            val input=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            fun u16(at:Int)=input.getShort(at).toInt() and 65535
            val width=u16(6);val height=u16(8)
            if(u16(18)!=1 || width !in 200..8192 || height !in 200..8192) return null
            val id=input.getInt(36).toLong() and 0xffffffffL
            if(id==0L || id==0xffffffffL) return null
            if(u16(48)!=0 || u16(50)!=0 || u16(52)!=height || u16(54)!=width) return null
            return MacDisplaySelection(id,width,height)
        }
    }
}
