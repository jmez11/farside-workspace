package com.farside.workspace
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.Deflater
class MacCursorTest {
    private fun compressed(raw:ByteArray):ByteArray {
        val d=Deflater();d.setInput(raw)
        val out=ByteArray(100)
        val n=d.deflate(out,0,out.size,Deflater.SYNC_FLUSH);d.end()
        return out.copyOf(n)
    }
    @Test fun syncFlushBgraAlphaAndCache() {
        val cache=MacCursorCache()
        val cursor=checkNotNull(cache.update(42,2,1,1,0,compressed(byteArrayOf(1,2,3,0,4,5,6,0,127,-1))))
        assertArrayEquals(intArrayOf(0x7f030201,0xff060504.toInt()),cursor.pixels)
        assertSame(cursor,cache.update(42,0,0,0,0,byteArrayOf()))
        assertEquals(1,cursor.hotX)
        assertNull(cache.update(99,0,0,0,0,byteArrayOf()))
    }
    @Test fun malformedOrOversizedCursorCannotAllocateUnboundedBitmap() {
        val cache=MacCursorCache()
        assertNull(cache.update(1,65535,65535,0,0,byteArrayOf(1)))
        assertNull(cache.update(1,1,1,0,0,compressed(ByteArray(6))))
        assertNull(cache.update(1,1,1,0,0,byteArrayOf(1)))
    }
}
