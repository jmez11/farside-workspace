package com.farside.workspace

import android.content.Context
import androidx.annotation.AnyRes

/** Resource-bearing validation error; never retains a UI context. */
interface LocalizableError {
    @get:AnyRes val resId: Int
    val formatArgs: Array<out Any>
    val quantity: Int? get() = null
}

/** Lists in format arguments are jump-route names, joined using the localized separator. */
fun LocalizableError.resolve(context: Context): String {
    val args = formatArgs.map { if (it is List<*>) it.joinToString(context.getString(R.string.jump_route_separator)) else it }.toTypedArray()
    return quantity?.let { context.resources.getQuantityString(resId, it, *args) }
        ?: context.getString(resId, *args)
}

open class LocalizableException(
    @get:AnyRes override val resId: Int,
    override val formatArgs: Array<out Any> = emptyArray(),
) : IllegalArgumentException(), LocalizableError

fun Throwable.localizedMessage(context: Context): String? =
    if (this is LocalizableError && resId != 0) resolve(context) else message
