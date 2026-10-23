package com.farside.workspace

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Observational only: never drives recovery or attributes unauthenticated hub traffic to a session. */
internal class MacRtcpDiagnostics(blobs: List<ByteArray>) {
    private class Keys(blob: ByteArray) {
        private val masterKey = blob.copyOfRange(0, 32)
        private val masterSalt = blob.copyOfRange(32, 46)
        val cipher = MacHevcSrtp.deriveKey(masterKey, masterSalt, 3, 32)
        val auth = MacHevcSrtp.deriveKey(masterKey, masterSalt, 4, 20)
        val salt = MacHevcSrtp.deriveKey(masterKey, masterSalt, 5, 14)
    }

    private val keys = blobs.distinctBy { it.toList() }.map {
        require(it.size == 46)
        Keys(it)
    }
    private val seen = LinkedHashSet<String>()
    private var control = 0L
    private var video = 0L
    private var authenticated = 0L
    private var unverified = 0L
    private var malformed = 0L
    private var duplicates = 0L
    private var sr = 0L
    private var rr = 0L
    private var feedback = 0L

    data class Snapshot(
        val control: Long,
        val video: Long,
        val authenticated: Long,
        val unverified: Long,
        val malformed: Long,
        val duplicates: Long,
        val sr: Long,
        val rr: Long,
        val feedback: Long
    ) {
        override fun toString() =
            "rtcp5900=$control rtcp5901=$video srtcpAuth=$authenticated " +
                "rtcpUnverified=$unverified rtcpMalformed=$malformed rtcpDuplicate=$duplicates " +
                "SR=$sr RR=$rr FB=$feedback"
    }

    @Synchronized
    fun snapshot() = Snapshot(control, video, authenticated, unverified, malformed, duplicates, sr, rr, feedback)

    /** RFC5761: marker-bit audio PT101 is229 and must stay on the RTP path. */
    @Synchronized
    fun receive(port: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Boolean {
        if (offset < 0 || length < 8 || offset > bytes.size - length) return false
        fun u(i: Int) = bytes[offset + i].toInt() and 255
        if (u(0) ushr 6 != 2 || u(1) !in 192..223) return false
        when (port) {
            5900 -> control++
            5901 -> video++
            else -> return false
        }
        if (length < 22) {
            unverified++
            return true
        }
        val end = offset + length
        val match = keys.withIndex().firstOrNull { (_, key) ->
            val mac = Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(key.auth, "HmacSHA1"))
                update(bytes, offset, length - 10)
            }
            MessageDigest.isEqual(mac.doFinal().copyOf(10), bytes.copyOfRange(end - 10, end))
        }
        if (match == null) {
            unverified++
            return true
        }
        val indexPosition = length - 14
        val index = (u(indexPosition).toLong() shl 24) or
            (u(indexPosition + 1).toLong() shl 16) or
            (u(indexPosition + 2).toLong() shl 8) or u(indexPosition + 3).toLong()
        val iv = ByteArray(16)
        match.value.salt.copyInto(iv)
        for (i in 0..3) iv[4 + i] = (iv[4 + i].toInt() xor u(4 + i)).toByte()
        val packetIndex = index and 0x7fffffffL
        for (i in 0..3) {
            iv[10 + i] = (iv[10 + i].toInt() xor (packetIndex ushr (24 - i * 8)).toInt()).toByte()
        }
        val clear = bytes.copyOfRange(offset, offset + indexPosition)
        if (index and 0x80000000L != 0L) {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(match.value.cipher, "AES"), IvParameterSpec(iv))
            }
            cipher.doFinal(clear, 8, clear.size - 8).copyInto(clear, 8)
        }
        val types = types(clear)
        if (types == null) {
            malformed++
            return true
        }
        val identity = "${match.index}:${(4..7).joinToString { u(it).toString() }}:$packetIndex"
        if (!seen.add(identity)) {
            duplicates++
            return true
        }
        if (seen.size > 512) seen.remove(seen.first())
        authenticated++
        sr += types.count { it == 200 }
        rr += types.count { it == 201 }
        feedback += types.count { it == 192 || it == 205 || it == 206 }
        return true
    }

    private fun types(bytes: ByteArray): List<Int>? {
        val types = mutableListOf<Int>()
        var pos = 0
        while (pos < bytes.size) {
            if (bytes.size - pos < 4) return null
            val first = bytes[pos].toInt() and 255
            val type = bytes[pos + 1].toInt() and 255
            val size = ((((bytes[pos + 2].toInt() and 255) shl 8) or
                (bytes[pos + 3].toInt() and 255)) + 1) * 4
            if (first ushr 6 != 2 || type !in 192..223 || size < 8 || size > bytes.size - pos) return null
            var useful = size
            if (first and 32 != 0) {
                val padding = bytes[pos + size - 1].toInt() and 255
                if (pos + size != bytes.size || padding == 0 || padding > size - 4) return null
                useful -= padding
            }
            val minimum = when (type) {
                200 -> 28 + (first and 31) * 24
                201 -> 8 + (first and 31) * 24
                205, 206 -> 12
                else -> 8
            }
            if (useful < minimum) return null
            types += type
            pos += size
        }
        return types.takeIf { it.isNotEmpty() }
    }
}
