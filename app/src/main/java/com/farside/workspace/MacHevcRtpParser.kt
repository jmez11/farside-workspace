package com.farside.workspace

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Apple RFC 7798 HEVC RTP Depacketizer.
 *
 * Handles Apple's variant of RFC 7798 with 2-byte DONL (Decoding Order Number)
 * present on every structure:
 * - Single NAL units (types 0..47) with 2-byte DONL stripped
 * - Aggregation Packets (type 48) with 2-byte DONL stripped
 * - Fragmentation Units (type 49) with 2-byte DONL in every fragment
 *
 * Produces Annex B formatted NAL units (prefixed with 00 00 00 01 start codes)
 * ready for Android MediaCodec "video/hevc".
 */
class MacHevcRtpParser {

    companion object {
        val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
        const val NAL_TYPE_AP = 48
        const val NAL_TYPE_FU = 49
    }

    private class FuAssembly(
        val originalHeader: ByteArray,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    // Tracks in-progress FU reassembly per SSRC
    private val fuAssemblies = mutableMapOf<Long, FuAssembly>()

    /**
     * Parses a decrypted RTP packet payload and returns a list of complete Annex B NAL units.
     * Returns an empty list if this packet was an intermediate FU fragment.
     */
    fun parseRtpPayload(payload: ByteArray, ssrc: Long = 0L): List<ByteArray> {
        if (payload.size < 4) return emptyList()

        val nalHeader0 = payload[0].toInt() and 0xff
        val nalHeader1 = payload[1].toInt() and 0xff
        val nalType = (nalHeader0 ushr 1) and 0x3f

        val result = mutableListOf<ByteArray>()

        when (nalType) {
            in 0..47 -> {
                // Single NAL unit with 2-byte DONL
                // Format: [2 bytes NAL header] [2 bytes DONL] [NAL payload...]
                if (payload.size < 4) return emptyList()
                val nalPayloadLen = payload.size - 4
                val annexBNal = ByteArray(4 + 2 + nalPayloadLen)
                System.arraycopy(ANNEX_B_START_CODE, 0, annexBNal, 0, 4)
                annexBNal[4] = nalHeader0.toByte()
                annexBNal[5] = nalHeader1.toByte()
                System.arraycopy(payload, 4, annexBNal, 6, nalPayloadLen)
                result.add(annexBNal)
            }
            NAL_TYPE_AP -> {
                // Aggregation Packet (type 48)
                // Format: [2 bytes header] [2 bytes DONL] then repeated [2 bytes size][NAL data]
                if (payload.size < 4) return emptyList()
                val buf = ByteBuffer.wrap(payload, 4, payload.size - 4).order(ByteOrder.BIG_ENDIAN)
                while (buf.remaining() >= 2) {
                    val nalSize = buf.short.toInt() and 0xffff
                    if (nalSize <= 0 || buf.remaining() < nalSize) break
                    val nalData = ByteArray(nalSize)
                    buf.get(nalData)

                    val annexBNal = ByteArray(4 + nalSize)
                    System.arraycopy(ANNEX_B_START_CODE, 0, annexBNal, 0, 4)
                    System.arraycopy(nalData, 0, annexBNal, 4, nalSize)
                    result.add(annexBNal)
                }
            }
            NAL_TYPE_FU -> {
                // Fragmentation Unit (type 49)
                // Format: [2 bytes header] [1 byte FU header] [2 bytes DONL] [FU payload...]
                if (payload.size < 5) return emptyList()
                val fuHeader = payload[2].toInt() and 0xff
                val startBit = (fuHeader and 0x80) != 0
                val endBit = (fuHeader and 0x40) != 0
                val actualNalType = fuHeader and 0x3f

                val fuPayloadOffset = 5 // skip 2B header + 1B FU header + 2B DONL
                val fuPayloadLen = payload.size - fuPayloadOffset
                if (fuPayloadLen < 0) return emptyList()

                if (startBit) {
                    // Reconstruct original NAL header:
                    // header0 = (nalHeader0 & 0x81) | (actualNalType << 1)
                    val recHeader0 = (nalHeader0 and 0x81) or ((actualNalType shl 1) and 0x7e)
                    val recHeader1 = nalHeader1
                    val assembly = FuAssembly(byteArrayOf(recHeader0.toByte(), recHeader1.toByte()))
                    assembly.buffer.write(payload, fuPayloadOffset, fuPayloadLen)
                    fuAssemblies[ssrc] = assembly

                    if (endBit) {
                        // Edge case: single-fragment FU
                        val fullNal = assembleAnnexB(assembly)
                        fuAssemblies.remove(ssrc)
                        result.add(fullNal)
                    }
                } else {
                    val assembly = fuAssemblies[ssrc]
                    if (assembly != null) {
                        assembly.buffer.write(payload, fuPayloadOffset, fuPayloadLen)
                        if (endBit) {
                            val fullNal = assembleAnnexB(assembly)
                            fuAssemblies.remove(ssrc)
                            result.add(fullNal)
                        }
                    }
                }
            }
            else -> {
                // Unknown NAL type, ignore per §12
            }
        }
        return result
    }

    private fun assembleAnnexB(assembly: FuAssembly): ByteArray {
        val payloadBytes = assembly.buffer.toByteArray()
        val totalLen = 4 + 2 + payloadBytes.size
        val out = ByteArray(totalLen)
        System.arraycopy(ANNEX_B_START_CODE, 0, out, 0, 4)
        out[4] = assembly.originalHeader[0]
        out[5] = assembly.originalHeader[1]
        System.arraycopy(payloadBytes, 0, out, 6, payloadBytes.size)
        return out
    }

    fun reset() {
        fuAssemblies.clear()
    }
}
