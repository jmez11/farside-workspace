package com.farside.workspace

import java.util.zip.Inflater

/** Apple cursor pixmap is independently deflated BGRA followed by one alpha byte per pixel. */
internal data class MacCursor(val width:Int,val height:Int,val hotX:Int,val hotY:Int,val pixels:IntArray)
internal class MacCursorCache {
    private val cache=linkedMapOf<Int,MacCursor>()
    fun update(id:Int,w:Int,h:Int,x:Int,y:Int,compressed:ByteArray):MacCursor? {
        if(compressed.isEmpty()) return cache[id]
        if(w !in 1..256 || h !in 1..256 || compressed.size>1024*1024) return null
        val raw=ByteArray(w*h*5+1)
        val inflater=Inflater()
        val size=try {
            inflater.setInput(compressed)
            var count=0
            while(count<raw.size) {
                val n=inflater.inflate(raw,count,raw.size-count)
                if(n==0) break
                count+=n
            }
            count
        } catch(_:java.util.zip.DataFormatException) { return null } finally { inflater.end() }
        if(size!=w*h*5) return null
        val pixels=IntArray(w*h) { i ->
            ((raw[w*h*4+i].toInt() and 255) shl 24) or
                ((raw[i*4+2].toInt() and 255) shl 16) or
                ((raw[i*4+1].toInt() and 255) shl 8) or (raw[i*4].toInt() and 255)
        }
        val cursor=MacCursor(w,h,x.coerceIn(0,w-1),y.coerceIn(0,h-1),pixels)
        cache[id]=cursor
        if(cache.size>64) cache.remove(cache.keys.first())
        return cursor
    }
}
