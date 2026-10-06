package com.farside.workspace

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.net.http.X509TrustManagerExtensions
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Fixtures exercise verification/persistence; live mode exercises the FreeRDP TLS/JNI/UI path. */
internal class RdpCertificateVerification(private val test: Instrumentation) {
    fun run(mode: String) {
        val prefs = test.targetContext.getSharedPreferences("rdp_certificates", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val positions = test.targetContext.getSharedPreferences("session_window_positions", Context.MODE_PRIVATE)
        val beforePositions = positions.all.toMap()
        try {
            if (mode == "policy") policy() else live()
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> edit.putString(key, value as String) }
            check(edit.commit()) { "Could not restore certificate exceptions" }
            val restorePositions = positions.edit().clear()
            beforePositions.forEach { (key, value) -> restorePositions.putString(key, value as String) }
            check(restorePositions.commit()) { "Could not restore window positions" }
        }
    }

    private fun policy() {
        fun fixture(name: String) = test.context.assets.open("certificates/$name.pem").use { it.readBytes() }
        val bridge = FreeRDPBridge()
        val one = fixture("one")
        val two = fixture("two")
        val expired = fixture("expired")
        check(bridge.certificateMatchesHost(one, "certificate.test"))
        check(bridge.certificateMatchesHost(one, "127.0.0.1"))
        check(bridge.certificateMatchesHost(one, "a.example.test"))
        check(!bridge.certificateMatchesHost(one, "a.b.example.test"))
        check(!bridge.certificateMatchesHost(one, "wrong.test"))
        check(!bridge.certificateMatchesHost(one, "127.0.0.2"))
        check(!bridge.certificateMatchesHost("bad pem".toByteArray(), "certificate.test"))
        val decisions = AtomicInteger(0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<RdpCertificateRequest>()
        val worker = Executors.newSingleThreadExecutor()
        val verifiers = mutableListOf<RdpCertificateVerifier>()
        fun verifier(host: String = "certificate.test", port: Int = 3389,
                     trust: X509TrustManagerExtensions = platformCertificateTrust()): RdpCertificateVerifier =
            RdpCertificateVerifier(test.targetContext, host, port, { request ->
                if (request != null) {
                    requests += request
                    request.decision.complete(decisions.get())
                }
            }, trust).also { verifiers += it }
        fun checkCert(verifier: RdpCertificateVerifier, pem: ByteArray, host: String = "certificate.test") =
            worker.submit<Boolean> { verifier.verifyCertificates(pem, bridge.certificateMatchesHost(pem, host)) }
                .get(10, TimeUnit.SECONDS)
        try {
            test.targetContext.getSharedPreferences("rdp_certificates", Context.MODE_PRIVATE).edit().clear().commit()
            val v = verifier()
            check(!checkCert(v, one)) { "Untrusted certificate accepted without approval" }
            decisions.set(2)
            check(checkCert(v, one))
            val count = requests.size
            check(checkCert(v, one) && requests.size == count + 1) { "Session-only acceptance persisted" }
            decisions.set(1)
            check(checkCert(v, one))
            val savedCount = requests.size
            check(checkCert(verifier(), one) && requests.size == savedCount) { "Saved certificate did not survive verifier recreation" }
            decisions.set(0)
            check(!checkCert(v, two)) { "Changed certificate bypassed confirmation" }
            check(requests.last().changed && requests.last().previousFingerprint != requests.last().fingerprint)
            check(checkCert(v, one)) { "Rejecting a replacement erased the saved certificate" }
            decisions.set(1)
            check(checkCert(v, two) && requests.last().changed)
            val replacedCount = requests.size
            check(checkCert(verifier(), two) && requests.size == replacedCount)
            decisions.set(0)
            check(!checkCert(v, one) && requests.last().changed) { "Old certificate still accepted after replacement" }
            check(!checkCert(verifier(port = 3390), two) && !requests.last().changed) { "Exception leaked between ports" }
            check(!checkCert(verifier("other.test"), two, "other.test")) { "Exception leaked between hosts" }
            check(requests.last().reasons.any { it.contains("name does not match") })
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test-root", CertificateFactory.getInstance("X.509").generateCertificate(fixture("root").inputStream()))
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val trusted = X509TrustManagerExtensions(factory.trustManagers.filterIsInstance<X509TrustManager>().first())
            val trustedVerifier = verifier(trust = trusted)
            val trustedCount = requests.size
            check(checkCert(trustedVerifier, one) && requests.size == trustedCount) { "Valid changed certificate prompted despite trusted chain" }
            check(!checkCert(verifier("wrong.test", trust = trusted), one, "wrong.test")) { "Trusted chain bypassed hostname mismatch" }
            check(!checkCert(trustedVerifier, expired) && requests.last().reasons.size >= 2) { "Expired trusted certificate accepted" }
            check(!checkCert(v, "bad pem".toByteArray()))
            v.close()
            check(!checkCert(v, two)) { "Closed verifier accepted a saved certificate" }
            val waiting = java.util.concurrent.CountDownLatch(1)
            val closing = RdpCertificateVerifier(test.targetContext, "cancel.test", 3389, { if (it != null) waiting.countDown() })
            verifiers += closing
            val pending = worker.submit<Boolean> { closing.verifyCertificates(one, false) }
            check(waiting.await(5, TimeUnit.SECONDS))
            closing.close()
            check(!pending.get(5, TimeUnit.SECONDS)) { "Session close did not unblock confirmation" }
        } finally {
            verifiers.forEach { it.close() }
            worker.shutdownNow()
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { found -> return found } }
            return null
        }
        val windows = test.uiAutomation.windowsOnAllDisplays
        for (i in 0 until windows.size()) for (window in windows.valueAt(i))
            window.root?.let { if (it.packageName == test.targetContext.packageName) search(it)?.let { found -> return found } }
        return null
    }

    private fun await(label: String) {
        repeat(100) { if (find(label) != null) return; Thread.sleep(200) }
        error("Missing certificate control: $label")
    }

    private fun click(label: String) {
        var node = checkNotNull(find(label)) { "Missing $label" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        Thread.sleep(400)
    }

    private fun live() {
        val profile = ProfileManager(test.targetContext).getProfiles().first { it.type == ConnectionType.RDP }
        val store = RdpCertificateStore(test.targetContext)
        val prefs = test.targetContext.getSharedPreferences("rdp_certificates", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val service = test.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = service
        fun launch(): Activity = test.startActivitySync(Intent(test.targetContext, SessionActivity::class.java).apply {
            putExtra("profileId", profile.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        fun texture(view: View): TextureView? {
            if (view is TextureView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
            return null
        }
        fun connected(activity: Activity) {
            repeat(80) {
                var content = false
                test.runOnMainSync {
                    texture(activity.window.decorView)?.bitmap?.let { image ->
                        val first = image.getPixel(0, 0)
                        content = (1..8).any { i -> image.getPixel(image.width * i / 9, image.height * i / 9) != first }
                        image.recycle()
                    }
                }
                if (content) return
                Thread.sleep(250)
            }
            error("No remote desktop after certificate decision")
        }
        var activity: Activity? = null
        try {
            activity = launch()
            await("Certificate can't be verified")
            click("More details")
            // Compose's dialog provides its real Window, including the separate dialog surface.
            fun dialogWindow(view: View): android.view.Window? {
                if (view is androidx.compose.ui.window.DialogWindowProvider) return view.window
                if (view is ViewGroup) for (i in 0 until view.childCount) dialogWindow(view.getChildAt(i))?.let { return it }
                return null
            }
            var window: android.view.Window? = null
            test.runOnMainSync {
                window = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { dialogWindow(it) }
            }
            val dialog = checkNotNull(window) { "Certificate dialog window missing" }
            val screenshot = Bitmap.createBitmap(dialog.decorView.width, dialog.decorView.height, Bitmap.Config.ARGB_8888)
            val copied = java.util.concurrent.CountDownLatch(1)
            var copyResult = -1
            test.runOnMainSync {
                android.view.PixelCopy.request(dialog, screenshot, { result -> copyResult = result; copied.countDown() },
                    android.os.Handler(android.os.Looper.getMainLooper()))
            }
            check(copied.await(5, TimeUnit.SECONDS) && copyResult == android.view.PixelCopy.SUCCESS)
            File(test.targetContext.cacheDir, "rdp-certificate-prompt.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            click("Cancel")
            await("Connection failed")
            check(store.load(profile.host, profile.port) == null) { "Cancel saved an exception" }
            test.runOnMainSync { activity?.finish() }
            Thread.sleep(1000)
            activity = launch()
            await("Certificate can't be verified")
            click("Connect")
            connected(activity!!)
            check(store.load(profile.host, profile.port) == null) { "Unchecked remember persisted" }
            test.runOnMainSync { activity?.finish() }
            Thread.sleep(1200)
            activity = launch()
            await("Certificate can't be verified")
            click("Remember this certificate for this PC")
            click("Connect")
            connected(activity!!)
            val saved = checkNotNull(store.load(profile.host, profile.port))
            check(saved.getString("pem").contains("BEGIN CERTIFICATE"))
            test.runOnMainSync { activity?.finish() }
            Thread.sleep(1200)
            activity = launch()
            connected(activity!!)
            check(find("Certificate can't be verified") == null) { "Saved exception still prompted" }
            test.runOnMainSync { activity?.finish() }
            Thread.sleep(1200)
            // Simulate a stale saved fingerprint without rotating the real server's certificate.
            store.save(profile.host, profile.port, org.json.JSONObject(saved.toString()).put("fingerprint", "STALE-TEST-FINGERPRINT"))
            activity = launch()
            await("Certificate has changed")
            click("Remember this certificate for this PC")
            click("Connect")
            connected(activity!!)
            check(store.load(profile.host, profile.port)?.getString("fingerprint") == saved.getString("fingerprint"))
        } finally {
            test.runOnMainSync { activity?.finish() }
            Thread.sleep(1200)
        }
    }
}
