package com.farside.workspace

/** Check cancellation between authentication stages without deliberately delaying a login. */
internal class MacAuthenticationGate(private val cancelled: ()->Boolean) {
    fun await() { check(!cancelled()) { "Session closed" } }
}
