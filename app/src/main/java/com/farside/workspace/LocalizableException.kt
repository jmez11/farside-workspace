package com.farside.workspace

import android.content.Context
import androidx.annotation.StringRes

/** Resource-bearing validation error; never retains a UI context. */
open class LocalizableException(
    @StringRes val resId: Int,
    val formatArgs: Array<out Any> = emptyArray(),
) : IllegalArgumentException() {
    fun resolve(context: Context): String = context.getString(resId, *formatArgs)
}

fun Throwable.localizedMessage(context: Context): String? =
    if (this is LocalizableException) resolve(context) else message
