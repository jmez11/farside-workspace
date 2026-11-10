package com.farside.workspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.lang.reflect.Proxy

/** Home owns this shortcut; its receiver exists even when Navigator is disabled. */
internal object QuestWindowManager {
    private const val PACKAGE = "com.oculus.vrshell"
    private const val RECEIVER = "com.oculus.vrshell.ShellControlBroadcastReceiver"
    private const val ACTION = "com.oculus.vrshell.intent.action.TOGGLE_WINDOW_MANAGER"
    private const val PREFERENCES_SERVICE = "preferences_manager"
    private const val NAVIGATOR_ENABLED = "navigator_enabled"

    private val component = ComponentName(PACKAGE, RECEIVER)

    internal fun canShow(navigatorEnabled: Boolean?, receiverAvailable: Boolean): Boolean =
        navigatorEnabled != false && receiverAvailable

    // VrShell reads this preference. Denied/unknown uses the receiver fallback, never OS version.
    internal fun navigatorEnabled(context: Context): Boolean? = runCatching {
        val manager = context.getSystemService(PREFERENCES_SERVICE) ?: return@runCatching null
        manager.javaClass.getMethod("getBoolean", String::class.java)
            .invoke(manager, NAVIGATOR_ENABLED) as? Boolean
    }.getOrNull()

    internal fun receiverAvailable(context: Context): Boolean = context.packageManager
        .queryBroadcastReceivers(Intent(ACTION).setPackage(PACKAGE), 0)
        .any { it.activityInfo?.let { receiver ->
            receiver.name == RECEIVER && receiver.enabled && receiver.exported
        } == true }

    fun isAvailable(context: Context): Boolean = canShow(navigatorEnabled(context), receiverAvailable(context))

    @Composable
    fun rememberAvailability(context: Context): Boolean {
        val app = context.applicationContext
        val owner = LocalLifecycleOwner.current
        var available by remember(app) { mutableStateOf(isAvailable(app)) }
        DisposableEffect(app, owner) {
            val main = Handler(Looper.getMainLooper())
            var active = true
            val refresh = { main.post { if (active) available = isAvailable(app) }; Unit }
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
            owner.lifecycle.addObserver(observer)
            // Observe feature rollout/settings changes even when another Home window changes it.
            val registration = runCatching {
                if (navigatorEnabled(app) == null) return@runCatching null
                val manager = app.getSystemService(PREFERENCES_SERVICE) ?: return@runCatching null
                val listenerClass = Class.forName("horizonos.os.preferences.PreferencesManager\$PreferencesListener")
                val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                    when (method.name) {
                        "onChanged" -> { refresh(); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "Farside Navigator preference listener"
                        else -> null
                    }
                }
                manager.javaClass.getMethod("addListener", Set::class.java, listenerClass)
                    .invoke(manager, setOf(NAVIGATOR_ENABLED), listener)
                Triple(manager, listenerClass, listener)
            }.getOrNull()
            onDispose {
                active = false
                owner.lifecycle.removeObserver(observer)
                registration?.let { (manager, listenerClass, listener) ->
                    runCatching { manager.javaClass.getMethod("removeListener", listenerClass).invoke(manager, listener) }
                }
            }
        }
        return available
    }

    fun toggle(context: Context): Boolean {
        if (!isAvailable(context)) return false
        context.sendBroadcast(Intent(ACTION).setComponent(component))
        return true
    }
}
