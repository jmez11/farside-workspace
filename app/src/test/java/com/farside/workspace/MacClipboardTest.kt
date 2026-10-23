package com.farside.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class MacClipboardTest {
    private fun archive(vararg items: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply {
            writeInt(items.size)
            items.forEach { (type, data) ->
                val uti = type.toByteArray()
                writeInt(uti.size); write(uti); writeInt(0)
                writeInt(1)
                val name = "public.mime-type".toByteArray()
                val value = "text/plain".toByteArray()
                writeInt(name.size); write(name); writeInt(value.size); write(value)
                writeInt(data.size); write(data)
            }
        }
    }.toByteArray()

    private fun incoming(plain: ByteArray, expected: Int = plain.size, promise: Int = 0): DataInputStream {
        val deflater = Deflater()
        val data = ByteArray(plain.size * 2 + 128)
        deflater.setInput(plain)
        val n = deflater.deflate(data, 0, data.size, Deflater.SYNC_FLUSH)
        deflater.end()
        return DataInputStream(ByteArrayInputStream(ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeByte(0); writeByte(promise); writeByte(0); writeInt(0)
                writeInt(expected); writeInt(n); write(data, 0, n)
            }
        }.toByteArray()))
    }

    @Test fun exactControlMessages() {
        assertArrayEquals(byteArrayOf(21,0,0,1,0,0,0,0), MacClipboard.enable())
        assertArrayEquals(byteArrayOf(11,0,0,0,0,0,0,0), MacClipboard.fetch())
    }

    @Test fun syncFlushArchivePrefersUtf8AndSkipsAliasesAndImages() {
        val text = "Quest → Mac café 日本語 😀\nsecond line"
        val plain = archive("public.png" to byteArrayOf(1,2,3),
            "public.utf16-plain-text" to "other".toByteArray(Charsets.UTF_16BE),
            "public.utf8-plain-text" to text.toByteArray())
        assertEquals(text, MacClipboard.receive(incoming(plain)))
    }

    @Test fun safariRichArchiveBeyondOldTwoMiBLimitRetainsPlainText() {
        val plain = archive("com.apple.webarchive" to ByteArray(3 * 1024 * 1024),
            "public.utf8-plain-text" to "Safari selection 日本語".toByteArray())
        assertEquals("Safari selection 日本語", MacClipboard.receive(incoming(plain)))
    }

    @Test fun utf16UsesBomOrBigEndianExternalRepresentation() {
        val text = "日本語 😀"
        listOf(Charsets.UTF_16BE, Charsets.UTF_16).forEach { charset ->
            assertEquals(text, MacClipboard.receive(incoming(archive("public.utf16-plain-text" to text.toByteArray(charset)))))
        }
        val little = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, MacClipboard.receive(incoming(archive("public.utf16-external-plain-text" to little))))
    }

    @Test fun emptyMetadataAndImageOnlyDoNotOverwriteText() {
        assertNull(MacClipboard.receive(incoming(byteArrayOf())))
        assertNull(MacClipboard.receive(incoming(archive("public.png" to byteArrayOf(1,2)))))
        assertNull(MacClipboard.receive(incoming(archive("public.utf8-plain-text" to "metadata".toByteArray()), promise=1)))
    }

    @Test fun rawArchivePreservesEmptyAndRichClipboardForRestoration() {
        listOf(byteArrayOf(), archive("public.png" to byteArrayOf(1,2,3),
            "public.utf8-plain-text" to "rich text".toByteArray())).forEach { original ->
            val payload = MacClipboard.read(incoming(original))
            assertArrayEquals(original, payload.archive)
            val wire = MacClipboard.sendArchive(checkNotNull(payload.archive))
            val restored = MacClipboard.read(DataInputStream(ByteArrayInputStream(wire,1,wire.size-1)))
            assertArrayEquals(original, restored.archive)
        }
    }

    @Test fun malformedConsumedPayloadsAreIgnored() {
        val plain = archive("public.utf8-plain-text" to "test".toByteArray())
        assertNull(MacClipboard.receive(incoming(plain, expected=plain.size-1)))
        assertNull(MacClipboard.receive(incoming(plain, expected=plain.size+1)))
        assertNull(MacClipboard.receive(incoming(byteArrayOf(0x7f,0x7f,0x7f,0x7f))))
        assertNull(MacClipboard.receive(incoming(plain.copyOf(plain.size-1))))
        assertNull(MacClipboard.receive(incoming(plain+byteArrayOf(0))))
    }

    @Test fun oversizedFramingRejectedBeforeAllocation() {
        val header = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
            writeByte(0); writeByte(0); writeByte(0); writeInt(0)
            writeInt(1); writeInt(Int.MAX_VALUE)
        } }.toByteArray()
        assertThrows(MacHevcProtocolException::class.java) { MacClipboard.receive(DataInputStream(ByteArrayInputStream(header))) }
    }

    @Test fun oversizedImageArchiveIsDrainedWithoutLosingNextMessage() {
        val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
            writeByte(0); writeByte(0); writeByte(0); writeInt(0)
            writeInt(MacClipboard.MAX_ARCHIVE_BYTES + 1); writeInt(3)
            write(byteArrayOf(1,2,3)); writeByte(20)
        } }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertNull(MacClipboard.receive(input))
        assertEquals(20, input.readUnsignedByte())
    }

    @Test fun largeUnicodeOutboundAndEmptyText() {
        listOf("", "café 日本語 😀\n".repeat(20000)).forEach { text ->
            val sent = MacClipboard.send(text.toByteArray())
            assertEquals(31, sent[0].toInt())
            assertEquals(text, MacClipboard.receive(DataInputStream(ByteArrayInputStream(sent, 1, sent.size-1))))
        }
        assertThrows(IllegalArgumentException::class.java) { MacClipboard.send(ByteArray(MacClipboard.MAX_TEXT_BYTES+1)) }
    }
}
