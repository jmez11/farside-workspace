package com.farside.workspace.data

import android.content.Context
import org.json.JSONArray

/** Launcher metadata stays separate from connection and credential configuration. */
internal data class LauncherArrangement(val starred: Set<String> = emptySet(), val order: List<String> = emptyList()) {
    fun arrange(profiles: List<ConnectionProfile>): List<ConnectionProfile> {
        val indices = order.withIndex().associate { it.value to it.index }
        return profiles.sortedWith(compareBy<ConnectionProfile> { if (it.id in starred) 0 else 1 }
            .thenBy { indices[it.id] ?: Int.MAX_VALUE })
    }
}

internal class LauncherArrangementStore(context: Context, preferencesName: String = "launcher_arrangement") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun load(): LauncherArrangement {
        val order = try {
            val array = JSONArray(prefs.getString("order", "[]"))
            (0 until array.length()).map { array.getString(it) }.distinct()
        } catch (_: Exception) { emptyList() }
        return LauncherArrangement(prefs.getStringSet("starred", emptySet()).orEmpty().toSet(), order)
    }

    fun toggleStar(profile: String): LauncherArrangement {
        val current = load()
        val starred = current.starred.toMutableSet()
        if (!starred.remove(profile)) starred.add(profile)
        prefs.edit().putStringSet("starred", starred).apply()
        return current.copy(starred = starred)
    }

    /** Move to the target's displayed position, within the same starred/unstarred group. */
    fun move(profiles: List<ConnectionProfile>, profile: String, target: String): LauncherArrangement {
        val current = load()
        if (profile == target || (profile in current.starred) != (target in current.starred)) return current
        val ids = current.arrange(profiles).map { it.id }.toMutableList()
        val from = ids.indexOf(profile)
        val to = ids.indexOf(target)
        if (from < 0 || to < 0) return current
        ids.add(to, ids.removeAt(from))
        prefs.edit().putString("order", JSONArray(ids).toString()).apply()
        return current.copy(order = ids)
    }

    fun remove(profile: String): LauncherArrangement {
        val current = load()
        val next = current.copy(starred = current.starred - profile, order = current.order.filterNot { it == profile })
        prefs.edit().putStringSet("starred", next.starred).putString("order", JSONArray(next.order).toString()).apply()
        return next
    }
}
