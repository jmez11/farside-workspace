package com.farside.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Apple's pasteboard extension, inside the authenticated control record stream.
 * Wire reference: renegadelink/iShareScreen, proxy/protocol/clipboard.py.
 * Only plain text is exported/imported; other pasteboard flavors are left alone.
 */
internal object MacClipboard {
    data class Payload(val text: String?, val archive: ByteArray?)
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_WIRE_BYTES = 64 * 1024 * 1024
    const val RECORD_CHUNK_BYTES = 60000
    private const val UTF8 = "public.utf8-plain-text"

    fun enable(): ByteArray = byteArrayOf(0x15, 0, 0, 1, 0, 0, 0, 0)
    fun fetch(): ByteArray = byteArrayOf(0x0b, 0, 0, 0, 0, 0, 0, 0)

    fun send(text: ByteArray): ByteArray {
        require(text.size <= MAX_TEXT_BYTES)
        val archive = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(1)
                val uti = UTF8.toByteArray(Charsets.UTF_8)
                writeInt(uti.size); write(uti)
                writeInt(0); writeInt(0) // reserved, aliases
                writeInt(text.size); write(text)
            }
        }.toByteArray()
        return sendArchive(archive)
    }

    fun sendArchive(archive: ByteArray): ByteArray {
        require(archive.size <= MAX_ARCHIVE_BYTES)
        val deflater = Deflater()
        val compressed = ByteArrayOutputStream()
        try {
            deflater.setInput(archive)
            val buffer = ByteArray(8192)
            do {
                val n = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
                compressed.write(buffer, 0, n)
            } while (n == buffer.size)
        } finally { deflater.end() }
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeByte(0x1f); writeByte(0); writeByte(0); writeByte(0)
                writeInt(0); writeInt(archive.size); writeInt(compressed.size())
                write(compressed.toByteArray())
            }
        }.toByteArray()
    }

    /** Type byte has already been consumed. Bound framing before allocating/reading. */
    fun receive(input: DataInputStream): String? = read(input).text

    fun read(input: DataInputStream): Payload {
        input.readUnsignedByte()
        val promise = input.readUnsignedByte()
        input.readUnsignedByte(); input.readInt()
        val plainSize = input.readInt()
        val compressedSize = input.readInt()
        if (plainSize < 0 || compressedSize !in 0..MAX_WIRE_BYTES)
            throw MacHevcProtocolException("Invalid clipboard payload size")
        // Large images/files must not disconnect an otherwise healthy desktop.
        // Drain their bounded wire body without allocating/decompressing it.
        if (plainSize > MAX_ARCHIVE_BYTES || compressedSize > MAX_ARCHIVE_BYTES) {
            var remaining = compressedSize
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                if (skipped == 0) { input.readUnsignedByte(); remaining-- } else remaining -= skipped
            }
            return Payload(null, null)
        }
        val compressed = ByteArray(compressedSize).also(input::readFully)
        if (promise != 0) return Payload(null, null)
        // A malformed, fully consumed archive need not tear down a healthy desktop.
        return runCatching {
            val archive = inflate(compressed, plainSize)
            Payload(textFromArchive(archive), archive)
        }.getOrDefault(Payload(null, null))
    }

    private fun inflate(compressed: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val plain = ByteArray(expected + 1) // Detect expansion beyond the advertised bound.
            var size = 0
            while (!inflater.needsInput() && !inflater.finished()) {
                val n = inflater.inflate(plain, size, plain.size - size)
                require(n > 0 || inflater.finished() || inflater.needsInput())
                size += n
                require(size <= expected)
            }
            require(size == expected && inflater.remaining == 0)
            return plain.copyOf(size)
        } finally { inflater.end() }
    }

    internal fun textFromArchive(archive: ByteArray): String? {
        if (archive.isEmpty()) return null
        val input = DataInputStream(ByteArrayInputStream(archive))
        fun count(limit: Int): Int = input.readInt().also { require(it in 0..limit) }
        fun bytes(): ByteArray = ByteArray(count(input.available())).also(input::readFully)
        var utf8: String? = null
        var utf16: String? = null
        repeat(count(256)) {
            val uti = bytes().toString(Charsets.UTF_8)
            input.readInt()
            repeat(count(256)) { bytes(); bytes() } // Aliases describe flavors; they are not text.
            val data = bytes()
            if (uti == UTF8 && utf8 == null) utf8 = data.toString(Charsets.UTF_8)
            if (uti in listOf("public.utf16-plain-text", "public.utf16-external-plain-text") && utf16 == null) {
                require(data.size % 2 == 0)
                // NSPasteboard's external UTF-16 representation is BE without a BOM,
                // or explicitly byte-ordered by a BOM (Java's UTF_16 handles both).
                utf16 = data.toString(Charsets.UTF_16)
            }
        }
        require(input.available() == 0)
        return utf8 ?: utf16
    }
}
