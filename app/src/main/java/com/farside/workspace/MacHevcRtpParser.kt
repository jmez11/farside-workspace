package com.farside.workspace

import java.io.ByteArrayOutputStream

/**
 * Apple RFC 7798 HEVC RTP Depacketizer.
 *
 * Handles both observed Apple RFC 7798 layouts. Complete aggregation packets establish
 * per-SSRC presence/absence of the optional 2-byte DONL (Decoding Order Number).
 * The legacy default before that evidence is DONL present:
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
    private val donlPresent = mutableMapOf<Long, Boolean>()

    /** Infer the optional DONL only from a completely valid aggregation packet, per SSRC. */
    private fun aggregation(payload: ByteArray, start: Int): List<ByteArray>? {
        var offset = start
        val nals = mutableListOf<ByteArray>()
        while (offset + 2 <= payload.size) {
            val size = ((payload[offset].toInt() and 255) shl 8) or (payload[offset + 1].toInt() and 255)
            offset += 2
            if (size < 2 || size > payload.size - offset) return null
            val type = (payload[offset].toInt() ushr 1) and 63
            if (payload[offset].toInt() and 0x80 != 0 || payload[offset + 1].toInt() and 7 == 0 || type > 47) return null
            nals += ANNEX_B_START_CODE + payload.copyOfRange(offset, offset + size)
            offset += size
        }
        return nals.takeIf { it.isNotEmpty() && offset == payload.size }
    }

    fun usesDonl(ssrc: Long): Boolean? = donlPresent[ssrc]

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
                val sourceOffset = if (donlPresent[ssrc] == false) 2 else 4
                val nalPayloadLen = payload.size - sourceOffset
                val annexBNal = ByteArray(4 + 2 + nalPayloadLen)
                System.arraycopy(ANNEX_B_START_CODE, 0, annexBNal, 0, 4)
                annexBNal[4] = nalHeader0.toByte()
                annexBNal[5] = nalHeader1.toByte()
                System.arraycopy(payload, sourceOffset, annexBNal, 6, nalPayloadLen)
                result.add(annexBNal)
            }
            NAL_TYPE_AP -> {
                // Aggregation Packet (type 48)
                // Format: [2 bytes header] [2 bytes DONL] then repeated [2 bytes size][NAL data]
                if (payload.size < 4) return emptyList()
                val plain = aggregation(payload, 2)
                val withDonl = aggregation(payload, 4)
                when {
                    plain != null && withDonl == null -> { donlPresent[ssrc] = false; result.addAll(plain) }
                    withDonl != null && plain == null -> { donlPresent[ssrc] = true; result.addAll(withDonl) }
                    // Ambiguous packets cannot establish a dialect; retain only a previously proven mode.
                    donlPresent[ssrc] == false && plain != null -> result.addAll(plain)
                    donlPresent[ssrc] == true && withDonl != null -> result.addAll(withDonl)
                }
            }
            NAL_TYPE_FU -> {
                // Fragmentation Unit (type 49)
                // Format: [2 bytes header] [1 byte FU header] [2 bytes DONL] [FU payload...]
                val fuPayloadOffset = if (donlPresent[ssrc] == false) 3 else 5
                if (payload.size <= fuPayloadOffset) return emptyList()
                val fuHeader = payload[2].toInt() and 0xff
                val startBit = (fuHeader and 0x80) != 0
                val endBit = (fuHeader and 0x40) != 0
                val actualNalType = fuHeader and 0x3f

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
        donlPresent.clear()
    }
}
