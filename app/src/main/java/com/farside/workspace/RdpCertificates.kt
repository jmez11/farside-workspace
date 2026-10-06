package com.farside.workspace

import android.content.Context
import android.net.http.X509TrustManagerExtensions
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.CompletableFuture
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal fun platformCertificateTrust(): X509TrustManagerExtensions {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return X509TrustManagerExtensions(factory.trustManagers.filterIsInstance<X509TrustManager>().first())
}

internal class RdpCertificateStore(context: Context) {
    private val prefs = context.getSharedPreferences("rdp_certificates", Context.MODE_PRIVATE)
    private fun key(host: String, port: Int) = "${host.lowercase(Locale.ROOT)}:$port"
    fun load(host: String, port: Int): JSONObject? = try {
        prefs.getString(key(host, port), null)?.let(::JSONObject)
    } catch (_: Exception) { null }

    fun save(host: String, port: Int, certificate: JSONObject): Boolean =
        prefs.edit().putString(key(host, port), certificate.toString()).commit()
}

internal data class RdpCertificateRequest(
    val host: String, val port: Int, val subject: String, val issuer: String,
    val validFrom: String, val validUntil: String, val fingerprint: String,
    val previousFingerprint: String?, val reasons: List<String>,
    val decision: CompletableFuture<Int> = CompletableFuture(),
) {
    val changed get() = previousFingerprint != null && previousFingerprint != fingerprint
    fun respond(remember: Boolean) { decision.complete(if (remember) 1 else 2) }
    fun reject() { decision.complete(0) }
}

/** Called synchronously by FreeRDP on its worker; only dialog presentation runs on main. */
@Keep
class RdpCertificateVerifier internal constructor(
    context: Context, private val host: String, private val port: Int,
    private val show: (RdpCertificateRequest?) -> Unit,
    private val trust: X509TrustManagerExtensions = platformCertificateTrust(),
) {
    private val store = RdpCertificateStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private var closed = false
    private var pending: RdpCertificateRequest? = null

    @Keep
    fun verifyCertificates(pem: ByteArray, hostnameMatches: Boolean): Boolean {
        if (synchronized(gate) { closed }) return false
        val chain = try {
            CertificateFactory.getInstance("X.509").generateCertificates(pem.inputStream())
                .map { it as X509Certificate }.toTypedArray().also { require(it.isNotEmpty()) }
        } catch (_: Exception) {
            Log.e("RdpCertificate", "Rejecting unreadable certificate for $host:$port")
            return false
        }
        val leaf = chain.first()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
        val reasons = mutableListOf<String>()
        if (!hostnameMatches) reasons += "PC name does not match the certificate."
        try {
            leaf.checkValidity()
        } catch (e: Exception) { reasons += e.message ?: "Certificate is outside its validity dates." }
        try {
            trust.checkServerTrusted(chain, leaf.publicKey.algorithm, host)
        } catch (e: Exception) {
            reasons += "Certificate chain is not trusted or could not be validated."
            Log.i("RdpCertificate", "Chain validation failed for $host:$port: ${e.javaClass.simpleName}")
        }
        if (reasons.isEmpty()) {
            Log.i("RdpCertificate", "Verified $host:$port against Android trust store and hostname")
            return !synchronized(gate) { closed }
        }
        val previous = store.load(host, port)?.optString("fingerprint")?.takeIf { it.isNotEmpty() }
        if (previous == fingerprint) {
            Log.i("RdpCertificate", "Saved certificate matches $host:$port")
            return !synchronized(gate) { closed }
        }
        val request = RdpCertificateRequest(host, port, leaf.subjectX500Principal.name,
            leaf.issuerX500Principal.name, leaf.notBefore.toString(), leaf.notAfter.toString(),
            fingerprint, previous, reasons)
        synchronized(gate) {
            if (closed) return false
            pending = request
        }
        main.post { synchronized(gate) { if (!closed && pending === request) show(request) } }
        val decision = try { request.decision.get() } catch (_: Exception) { 0 }
        val accepted = synchronized(gate) {
            pending = null
            if (closed || decision == 0) false
            else if (decision == 2) true
            else store.save(host, port, JSONObject()
                .put("fingerprint", fingerprint).put("subject", request.subject).put("issuer", request.issuer)
                .put("validFrom", request.validFrom).put("validUntil", request.validUntil)
                .put("pem", pem.toString(Charsets.US_ASCII)).put("savedAt", System.currentTimeMillis()))
        }
        main.post { show(null) }
        Log.i("RdpCertificate", "Certificate decision for $host:$port: accepted=$accepted saved=${decision == 1 && accepted} changed=${request.changed}")
        return accepted
    }

    fun close() {
        synchronized(gate) {
            closed = true
            pending?.reject()
        }
    }
}
