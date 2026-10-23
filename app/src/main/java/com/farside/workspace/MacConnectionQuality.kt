package com.farside.workspace

internal data class MacConnectionQuality(
    val videoPackets: Long = 0,
    val frames: Long = 0,
    val missing: Long = 0,
    val gaps: Long = 0,
    val reordered: Long = 0,
    val duplicates: Long = 0,
    val localDrops: Long = 0,
    val decoderDrops: Long = 0,
    val skipped: Long = 0,
    val recoveries: Long = 0,
    val frameAgeMs: Long? = null,
    val recentGap: Boolean = false,
    val rtcp: MacRtcpDiagnostics.Snapshot? = null,
    val fallback: Boolean = false
) {
    // Absent decoded progress is not proof of transport failure: static desktops can be quiet.
    enum class State { STARTING, RECEIVING, DEGRADED, STALLED, FALLBACK }

    val state: State
        get() = when {
            fallback -> State.FALLBACK
            frames == 0L -> if (gaps > 0) State.DEGRADED else State.STARTING
            (frameAgeMs ?: 0) >= 3000 -> State.STALLED
            recentGap -> State.DEGRADED
            else -> State.RECEIVING
        }
}
