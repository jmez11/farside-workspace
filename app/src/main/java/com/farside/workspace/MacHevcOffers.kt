package com.farside.workspace

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

data class MacHevcNegotiationKeys(
    val audioKeyV: ByteArray,
    val audioKeyS: ByteArray,
    val videoKeyV: ByteArray,
    val videoKeyS: ByteArray
) {
    companion object {
        fun random(): MacHevcNegotiationKeys {
            val random = SecureRandom()
            fun k(): ByteArray = ByteArray(46).also { random.nextBytes(it) }
            return MacHevcNegotiationKeys(k(), k(), k(), k())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MacHevcNegotiationKeys) return false
        return audioKeyV.contentEquals(other.audioKeyV) &&
                audioKeyS.contentEquals(other.audioKeyS) &&
                videoKeyV.contentEquals(other.videoKeyV) &&
                videoKeyS.contentEquals(other.videoKeyS)
    }

    override fun hashCode(): Int {
        var result = audioKeyV.contentHashCode()
        result = 31 * result + audioKeyS.contentHashCode()
        result = 31 * result + videoKeyV.contentHashCode()
        result = 31 * result + videoKeyS.contentHashCode()
        return result
    }
}

data class MacHevcOffersBundle(
    val videoOffer: ByteArray,
    val audioOffer: ByteArray,
    val ourVideoSsrc: Long,
    val ourAudioSsrc: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MacHevcOffersBundle) return false
        return videoOffer.contentEquals(other.videoOffer) &&
                audioOffer.contentEquals(other.audioOffer) &&
                ourVideoSsrc == other.ourVideoSsrc &&
                ourAudioSsrc == other.ourAudioSsrc
    }

    override fun hashCode(): Int {
        var result = videoOffer.contentHashCode()
        result = 31 * result + audioOffer.contentHashCode()
        result = 31 * result + ourVideoSsrc.hashCode()
        result = 31 * result + ourAudioSsrc.hashCode()
        return result
    }
}

/**
 * Binary Property List (bplist00) serializer for Apple media negotiation dictionaries.
 */
object AppleBplistWriter {
    fun writeDict(dict: Map<String, Any>): ByteArray {
        val keys = dict.keys.sorted()
        val numKeys = keys.size
        val objs = mutableListOf<ByteArray>()

        // Root object: dictionary
        val dictHeader = byteArrayOf((0xd0 or (numKeys and 0x0f)).toByte())
        val dictBody = ByteArray(numKeys * 2)
        for (i in 0 until numKeys) {
            dictBody[i] = (i + 1).toByte()               // key refs: 1..numKeys
            dictBody[numKeys + i] = (numKeys + 1 + i).toByte() // val refs: numKeys+1..2*numKeys
        }
        objs.add(dictHeader + dictBody)

        // Key strings
        for (k in keys) {
            val b = k.toByteArray(Charsets.US_ASCII)
            if (b.size < 15) {
                objs.add(byteArrayOf((0x50 or b.size).toByte()) + b)
            } else {
                objs.add(byteArrayOf(0x5f.toByte(), 0x10.toByte(), b.size.toByte()) + b)
            }
        }

        // Value objects
        for (k in keys) {
            val v = dict[k] ?: error("Null value for key $k")
            when (v) {
                is Int -> {
                    objs.add(byteArrayOf(0x10.toByte(), (v and 0xff).toByte()))
                }
                is String -> {
                    val b = v.toByteArray(Charsets.US_ASCII)
                    if (b.size < 15) {
                        objs.add(byteArrayOf((0x50 or b.size).toByte()) + b)
                    } else if (b.size < 256) {
                        objs.add(byteArrayOf(0x5f.toByte(), 0x10.toByte(), b.size.toByte()) + b)
                    } else {
                        objs.add(byteArrayOf(0x5f.toByte(), 0x11.toByte(), (b.size shr 8).toByte(), (b.size and 0xff).toByte()) + b)
                    }
                }
                is ByteArray -> {
                    if (v.size < 15) {
                        objs.add(byteArrayOf((0x40 or v.size).toByte()) + v)
                    } else if (v.size < 256) {
                        objs.add(byteArrayOf(0x4f.toByte(), 0x10.toByte(), v.size.toByte()) + v)
                    } else {
                        objs.add(byteArrayOf(0x4f.toByte(), 0x11.toByte(), (v.size shr 8).toByte(), (v.size and 0xff).toByte()) + v)
                    }
                }
                else -> error("Unsupported plist value type: ${v.javaClass}")
            }
        }

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))

        val offsets = mutableListOf<Int>()
        for (obj in objs) {
            offsets.add(out.size())
            out.write(obj)
        }

        val offsetTableOffset = out.size()
        val offsetSize = if (offsetTableOffset > 255) 2 else 1

        for (off in offsets) {
            if (offsetSize == 1) {
                out.write(off and 0xff)
            } else {
                out.write((off shr 8) and 0xff)
                out.write(off and 0xff)
            }
        }

        // Trailer (32 bytes)
        val trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        trailer.put(ByteArray(6)) // unused
        trailer.put(offsetSize.toByte())
        trailer.put(1.toByte())   // objectRefSize = 1
        trailer.putLong(objs.size.toLong())
        trailer.putLong(0L)       // topObject = 0
        trailer.putLong(offsetTableOffset.toLong())
        out.write(trailer.array())

        return out.toByteArray()
    }
}

/**
 * AVCMediaStreamNegotiator offer generator and parser matching renegadelink/iShareScreen.
 */
object MacHevcOffers {

    private fun writeVarint(v: Long, out: ByteArrayOutputStream) {
        var num = v
        while (num > 0x7fL) {
            out.write(((num and 0x7fL) or 0x80L).toInt())
            num = num ushr 7
        }
        out.write((num and 0x7fL).toInt())
    }

    private fun fieldVarint(fieldNum: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(((fieldNum.toLong() shl 3) or 0L), out)
        writeVarint(value, out)
        return out.toByteArray()
    }

    private fun fieldBytes(fieldNum: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(((fieldNum.toLong() shl 3) or 2L), out)
        writeVarint(value.size.toLong(), out)
        out.write(value)
        return out.toByteArray()
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var pos = startPos
        var value = 0L
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            shift += 7
            if ((b and 0x80) == 0) break
        }
        return Pair(value, pos)
    }

    private val REMOTE_ENDPOINT_INFO: ByteArray by lazy {
        val hwModel = "Quest-arm64".toByteArray(Charsets.UTF_8)
        val avcVer = "1.0.0".toByteArray(Charsets.UTF_8)
        val osBuild = "0".toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x08, 0x00, 0x10, 0x01))
        out.write(0x1a); out.write(hwModel.size); out.write(hwModel)
        out.write(0x22); out.write(avcVer.size); out.write(avcVer)
        out.write(0x2a); out.write(osBuild.size); out.write(osBuild)
        out.toByteArray()
    }

    private val AUDIO_F9_TIERS = listOf(
        Triple(0L, 40_000_000L, 12288L),
        Triple(0L, 6_000_000L, 131072L),
        Triple(4074L, 0L, 16384L),
        Triple(16L, 4100L, null),
        Triple(0L, 75_000_000L, 524288L),
        Triple(0L, 20_000_000L, 98304L),
        Triple(4L, 6500L, null),
        Triple(0L, 60_000_000L, 262144L),
        Triple(1L, 299L, null),
        Triple(0L, 100_000_000L, 1048576L)
    )

    private val APPLE_AUDIO_F9: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        for ((f1, f2, f3) in AUDIO_F9_TIERS) {
            val entry = ByteArrayOutputStream()
            entry.write(0x08); writeVarint(f1, entry)
            entry.write(0x10); writeVarint(f2, entry)
            if (f3 != null) {
                entry.write(0x18); writeVarint(f3, entry)
            }
            val entryBytes = entry.toByteArray()
            out.write(0x4a)
            writeVarint(entryBytes.size.toLong(), out)
            out.write(entryBytes)
        }
        out.toByteArray()
    }

    private val HEVC_PARAMS_LTR = "FLS;MS:-1;LF:-1;LTR;CABAC;POS:0;EOD:1;HTS:2;RR:3;AR:16/9,5/8;XR:16/9,5/8;".toByteArray(Charsets.US_ASCII)
    private val AVC_PARAMS = "FLS;LF:-1;POS:5;EOD:1;HTS:2;RR:3;POSE:4;AR:16/9,5/8;XR:16/9,5/8;".toByteArray(Charsets.US_ASCII)

    fun buildMediaBlob(mode: Int, sessionId: Int, timestamp: Long, tilesPerFrame: Int = 1, audioEnabled: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fieldVarint(1, 1))
        out.write(fieldVarint(2, 1))

        if (mode == 7) {
            // Video
            val resEntry = fieldVarint(1, 1) + fieldVarint(2, 1) + fieldVarint(3, 50115) + fieldVarint(4, 0)
            val resEntryAlt = fieldVarint(1, 1) + fieldVarint(2, 2) + fieldVarint(3, 50115) + fieldVarint(4, 0)

            val hevcBank = fieldVarint(1, 123) +
                    fieldBytes(2, resEntry) + fieldBytes(2, resEntryAlt) +
                    fieldBytes(2, resEntry) + fieldBytes(2, resEntryAlt) +
                    fieldBytes(3, HEVC_PARAMS_LTR) +
                    fieldVarint(4, 1)

            val avcBank = fieldVarint(1, 100) +
                    fieldBytes(2, resEntry) + fieldBytes(2, resEntryAlt) +
                    fieldBytes(3, AVC_PARAMS) +
                    fieldVarint(4, 14)

            // Both banks advertised: 123 then 100 per Apple reference
            val codecBanks = fieldBytes(3, hevcBank) + fieldBytes(3, avcBank)

            val desc = fieldVarint(1, sessionId.toLong() and 0xffffffffL) +
                    fieldVarint(2, 1) + // LTR on
                    codecBanks +
                    fieldVarint(6, tilesPerFrame.toLong()) +
                    fieldVarint(7, 1) + // LTR on
                    fieldVarint(8, 63) +
                    fieldVarint(9, 1) +
                    fieldVarint(12, 1)

            out.write(fieldBytes(5, desc))
        } else if (mode == 8) {
            // Audio
            val af4 = if (audioEnabled) 24191L else 1000L
            val desc = fieldVarint(1, sessionId.toLong() and 0xffffffffL) +
                    fieldVarint(2, 0) +
                    fieldVarint(3, 0) +
                    fieldVarint(4, af4) +
                    fieldVarint(5, 0) +
                    fieldVarint(6, 0)
            out.write(fieldBytes(3, desc))
        }

        out.write(fieldBytes(6, "Farside 1.0".toByteArray(Charsets.US_ASCII)))
        out.write(fieldVarint(8, 0))
        out.write(APPLE_AUDIO_F9)
        out.write(fieldVarint(13, timestamp))
        out.write(fieldVarint(14, 2))
        out.write(fieldVarint(16, 0))
        out.write(fieldVarint(18, 1))

        return out.toByteArray()
    }

    private fun compressZlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    fun createOffers(tilesPerFrame: Int = 1, audioEnabled: Boolean = false): MacHevcOffersBundle {
        val rand = SecureRandom()
        val videoSessionId = rand.nextInt()
        val audioSessionId = rand.nextInt()

        fun plist(mode: Int, sessionId: Int): ByteArray {
            val timestamp = System.currentTimeMillis() * 1_000_000L
            val blob = buildMediaBlob(mode, sessionId, timestamp, tilesPerFrame, audioEnabled)
            val dict = mapOf(
                "avcMediaStreamOptionRemoteEndpointInfo" to REMOTE_ENDPOINT_INFO,
                "avcMediaStreamNegotiatorMode" to mode,
                "avcMediaStreamNegotiatorMediaBlob" to compressZlib(blob),
                "avcMediaStreamOptionCallID" to UUID.randomUUID().toString().uppercase()
            )
            return AppleBplistWriter.writeDict(dict)
        }
        val videoOffer = plist(7, videoSessionId)
        val audioOffer = plist(8, audioSessionId)
        val videoSsrc = videoSessionId.toLong() and 0xffffffffL
        val audioSsrc = audioSessionId.toLong() and 0xffffffffL
        return MacHevcOffersBundle(videoOffer, audioOffer, videoSsrc, audioSsrc)
    }

    fun build0x1cMessage(
        audioOffer: ByteArray,
        videoOffer: ByteArray,
        keys: MacHevcNegotiationKeys,
        altSession: Boolean = false
    ): ByteArray {
        val asSize = audioOffer.size
        val vsSize = videoOffer.size
        val ms = asSize + vsSize + 0xd8

        val buf = ByteBuffer.allocate(ms + 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x1c.toByte())
        buf.put(0.toByte())
        buf.putShort(ms.toShort())
        buf.putShort(3.toShort()) // version = 3

        val configFlags = if (altSession) 5 else 7 // bit 0 (60fps stream 1), bit 1 (60fps stream 2), bit 2 (no cursor)
        buf.putInt(configFlags)
        buf.putShort(asSize.toShort())
        buf.putShort(vsSize.toShort())

        val callId = UUID.randomUUID()
        val callIdBuf = ByteBuffer.allocate(16)
        callIdBuf.putLong(callId.mostSignificantBits)
        callIdBuf.putLong(callId.leastSignificantBits)
        buf.position(0x14)
        buf.put(callIdBuf.array())

        buf.position(0x24)
        buf.put(keys.audioKeyV)
        buf.position(0x52)
        buf.put(keys.audioKeyS)
        buf.position(0x80)
        buf.put(audioOffer)

        val vo = 0x80 + asSize
        buf.position(vo)
        buf.put(keys.videoKeyV)
        buf.position(vo + 0x2e)
        buf.put(keys.videoKeyS)
        buf.position(vo + 0x5c)
        buf.put(videoOffer)

        return buf.array()
    }

    fun extractCanvasDims(answerMsg: ByteArray): Triple<Int, Int, Int>? {
        if (answerMsg.size < 40) return null
        val bplistHeader = "bplist".toByteArray(Charsets.US_ASCII)

        // Find "bplist" in answerMsg
        var idx = 0
        while (idx <= answerMsg.size - bplistHeader.size) {
            var found = true
            for (i in bplistHeader.indices) {
                if (answerMsg[idx + i] != bplistHeader[i]) {
                    found = false
                    break
                }
            }
            if (found) break
            idx++
        }
        if (idx > answerMsg.size - bplistHeader.size) return null

        val bplist = if (idx == 0) answerMsg else answerMsg.copyOfRange(idx, answerMsg.size)
        if (bplist.size < 32) return null

        var trailerOff = -1
        var offsetSize = 0
        var numObjects = 0
        var offsetTableOffset = 0

        for (end in bplist.size downTo 32) {
            val off = end - 32
            var zeroCheck = true
            for (z in 0 until 6) {
                if (bplist[off + z] != 0.toByte()) {
                    zeroCheck = false
                    break
                }
            }
            if (!zeroCheck) continue
            val offSz = bplist[off + 6].toInt() and 0xff
            val refSz = bplist[off + 7].toInt() and 0xff
            if (offSz !in 1..4 || refSz !in 1..4) continue
            val numObjs = ByteBuffer.wrap(bplist, off + 8, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            val topObj = ByteBuffer.wrap(bplist, off + 16, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            val tableOff = ByteBuffer.wrap(bplist, off + 24, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            if (tableOff in 8 until off && numObjs in 1..100000 && topObj in 0 until numObjs) {
                trailerOff = off
                offsetSize = offSz
                numObjects = numObjs
                offsetTableOffset = tableOff
                break
            }
        }
        if (trailerOff == -1) return null

        for (i in 0 until numObjects) {
            val tPos = offsetTableOffset + i * offsetSize
            val objOff = when (offsetSize) {
                1 -> bplist[tPos].toInt() and 0xff
                2 -> ((bplist[tPos].toInt() and 0xff) shl 8) or (bplist[tPos + 1].toInt() and 0xff)
                else -> ByteBuffer.wrap(bplist, tPos, 4).order(ByteOrder.BIG_ENDIAN).int
            }
            if (objOff !in 0 until bplist.size) continue
            val marker = bplist[objOff].toInt() and 0xff
            if ((marker and 0xf0) != 0x40) continue // not NSData

            var dataLen = marker and 0x0f
            var p = objOff + 1
            if (dataLen == 0x0f) {
                if (p >= bplist.size) continue
                val intMarker = bplist[p].toInt() and 0xff
                val exp = intMarker and 0x0f
                p++
                val intLen = 1 shl exp
                if (p + intLen > bplist.size) continue
                dataLen = when (intLen) {
                    1 -> bplist[p].toInt() and 0xff
                    2 -> ((bplist[p].toInt() and 0xff) shl 8) or (bplist[p + 1].toInt() and 0xff)
                    4 -> ByteBuffer.wrap(bplist, p, 4).order(ByteOrder.BIG_ENDIAN).int
                    else -> continue
                }
                p += intLen
            }

            if (p + dataLen > bplist.size || dataLen < 2) continue
            val cmf = bplist[p].toInt() and 0xff
            val flg = bplist[p + 1].toInt() and 0xff
            if (cmf != 0x78 || (cmf * 256 + flg) % 31 != 0) continue

            val decompressed = try {
                val inflater = Inflater()
                inflater.setInput(bplist, p, dataLen)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(1024)
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(buf)
                    if (count <= 0) break
                    out.write(buf, 0, count)
                }
                inflater.end()
                out.toByteArray()
            } catch (e: Exception) {
                continue
            }

            var cw = 0
            var ch = 0
            var ct = 0
            var pos = 0
            while (pos < decompressed.size) {
                val (tag, nextPos) = readVarint(decompressed, pos)
                pos = nextPos
                val fn = (tag ushr 3).toInt()
                val wt = (tag and 7L).toInt()
                when (wt) {
                    0 -> {
                        val (_, nextP) = readVarint(decompressed, pos)
                        pos = nextP
                    }
                    2 -> {
                        val (len, nextP) = readVarint(decompressed, pos)
                        pos = nextP
                        val length = len.toInt()
                        if (fn == 5 && pos + length <= decompressed.size) {
                            var sp = pos
                            val end = pos + length
                            while (sp < end) {
                                val (st, snp) = readVarint(decompressed, sp)
                                sp = snp
                                val sf = (st ushr 3).toInt()
                                val sw = (st and 7L).toInt()
                                when (sw) {
                                    0 -> {
                                        val (v, vp) = readVarint(decompressed, sp)
                                        sp = vp
                                        if (sf == 4) cw = v.toInt()
                                        else if (sf == 5) ch = v.toInt()
                                        else if (sf == 6) ct = v.toInt()
                                    }
                                    2 -> {
                                        val (sl, slp) = readVarint(decompressed, sp)
                                        sp = slp + sl.toInt()
                                    }
                                    1 -> sp += 8
                                    5 -> sp += 4
                                    else -> break
                                }
                            }
                        }
                        pos += length
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> break
                }
            }
            if (cw > 0 && ch > 0) return Triple(cw, ch, ct)
        }
        return null
    }
}
