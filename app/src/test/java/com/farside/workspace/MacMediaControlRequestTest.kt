package com.farside.workspace

import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class MacMediaControlRequestTest {
    @Test fun startupAndIncrementalRequestsCannotPullScreenPixels() {
        for(incremental in listOf(false,true)) {
            val request=MacHevcSession.buildControlUpdateRequest(incremental)
            DataInputStream(request.inputStream()).use { wire ->
                assertEquals(3,wire.readUnsignedByte())
                assertEquals(if(incremental) 1 else 0,wire.readUnsignedByte())
                assertEquals(0,wire.readUnsignedShort());assertEquals(0,wire.readUnsignedShort())
                assertEquals(1,wire.readUnsignedShort());assertEquals(1,wire.readUnsignedShort())
                assertEquals(0,wire.available())
            }
        }
    }
}
