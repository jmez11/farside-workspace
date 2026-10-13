package com.farside.workspace

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** System-verified access gate. Never reads, hashes, or stores the headset passcode. */
object DeviceCredentialAuth {
    // Quest currently rejects Farside's prompts through its OS allowlist.
    internal const val SYSTEM_PIN_SWITCH_VISIBLE = false
    private val grant = UnlockGrant()
    private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        context.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { clear() }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        initialized = true
    }
    internal class UnlockGrant {
        private var until = 0L
        @Synchronized fun clear() { until = 0L }
        @Synchronized fun authenticated(remember: Boolean, now: Long = SystemClock.elapsedRealtime()) {
            until = if (remember) now + 300_000L else 0L
        }
        @Synchronized fun valid(secure: Boolean, locked: Boolean, now: Long = SystemClock.elapsedRealtime()): Boolean {
            if (!secure || locked || now >= until) { until = 0L; return false }
            return true
        }
    }
    // Horizon uses its own passcode service: KeyguardManager.isDeviceSecure can be false
    // while DEVICE_CREDENTIAL is available. Query the service that owns the prompt.
    fun isSecure(context: Context): Boolean = context.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    fun hasRememberedUnlock(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return grant.valid(isSecure(context), keyguard?.isDeviceLocked != false)
    }
    fun clear() = grant.clear()
    fun authenticate(activity: Activity, title: String, remember: Boolean, onResult: (String?) -> Unit): CancellationSignal {
        initialize(activity)
        val cancellation = CancellationSignal()
        if (!isSecure(activity)) {
            clear()
            onResult(activity.getString(R.string.headset_passcode_required))
            return cancellation
        }
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                cancellation.cancel()
                clear()
                onResult(activity.getString(R.string.headset_auth_timeout))
            }
        }
        cancellation.setOnCancelListener { completed.set(true); handler.removeCallbacks(timeout) }
        handler.postDelayed(timeout, 30_000L)
        fun complete(error: String?, rememberUnlock: Boolean? = null) {
            if (!cancellation.isCanceled && completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                if (error == null && rememberUnlock != null) grant.authenticated(rememberUnlock)
                onResult(error)
            }
        }
        try {
            BiometricPrompt.Builder(activity).setTitle(title)
                .setDescription(activity.getString(R.string.headset_auth_description))
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                .authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cancellation.isCanceled || completed.get()) return
                        if (result.authenticationType != BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL || !isSecure(activity)) {
                            clear(); complete(activity.getString(R.string.headset_auth_unverified))
                        } else { complete(null, remember) }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (!cancellation.isCanceled) { clear(); complete(errString?.toString() ?: activity.getString(R.string.headset_auth_rejected)) }
                    }
                })
        } catch (_: RuntimeException) {
            clear(); complete(activity.getString(R.string.headset_auth_unavailable))
        }
        return cancellation
    }
}
