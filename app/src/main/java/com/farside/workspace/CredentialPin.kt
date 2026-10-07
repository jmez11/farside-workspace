package com.farside.workspace

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** An app access lock; login passwords remain encrypted separately by Android Keystore. */
object CredentialPin {
    private const val ITERATIONS = 600_000
    fun valid(pin: String) = pin.length in 4..12 && pin.all { it in '0'..'9' }
    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun create(pin: String): String {
        require(valid(pin)) { "Use 4–12 digits for the PIN." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "v1:" + Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP)
    }
    fun matches(pin: String, verifier: String): Boolean {
        if (!valid(pin)) return false
        val parts = verifier.split(':')
        if (parts.size != 3 || parts[0] != "v1") return false
        return runCatching {
            val salt = Base64.decode(parts[1], Base64.NO_WRAP)
            val expected = Base64.decode(parts[2], Base64.NO_WRAP)
            salt.size == 16 && expected.size == 32 && MessageDigest.isEqual(derive(pin, salt), expected)
        }.getOrDefault(false)
    }
    @Synchronized
    fun unlock(context: Context, verifier: String?, pin: String, now: Long = System.currentTimeMillis()): String? {
        if (verifier == null) return null
        val prefs = context.getSharedPreferences("farside_profiles", Context.MODE_PRIVATE)
        val prefix = "app-pin-"
        val until = prefs.getLong(prefix + "until", 0)
        if (until > now) return "Too many attempts. Try again in ${(until - now + 999) / 1000} seconds."
        if (matches(pin, verifier)) {
            check(prefs.edit().remove(prefix + "attempts").remove(prefix + "until").commit())
            return null
        }
        val attempts = (prefs.getInt(prefix + "attempts", 0) + 1).coerceAtMost(30)
        val delay = if (attempts >= 5) (30_000L shl ((attempts - 5).coerceAtMost(4))).coerceAtMost(300_000L) else 0L
        check(prefs.edit().putInt(prefix + "attempts", attempts).putLong(prefix + "until", now + delay).commit())
        return if (delay == 0L) "Incorrect PIN." else "Too many attempts. Try again in ${delay / 1000} seconds."
    }
}
