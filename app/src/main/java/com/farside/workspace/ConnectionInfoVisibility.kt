package com.farside.workspace

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*

/** Global debug preference; active desktop windows observe changes immediately. */
internal object ConnectionInfoVisibility {
    private const val KEY = "show_connection_info"
    fun preferences(context: Context) = context.getSharedPreferences("debug_options", Context.MODE_PRIVATE)
    fun get(context: Context) = preferences(context).getBoolean(KEY, false)
    fun set(context: Context, visible: Boolean) { preferences(context).edit().putBoolean(KEY, visible).apply() }

    @Composable fun observe(context: Context): State<Boolean> {
        val state = remember(context) { mutableStateOf(get(context)) }
        DisposableEffect(context) {
            val prefs = preferences(context)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY) state.value = get(context)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            state.value = get(context)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return state
    }
}
