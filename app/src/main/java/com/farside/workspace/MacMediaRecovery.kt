package com.farside.workspace

/** No-frame watchdog: solicit an IDR before escalating an idle-looking stream over TCP. */
internal class MacMediaRecovery {
    enum class Action { KEYFRAME, MEDIA_OFFER, DISPLAY }

    private var frames = 0L
    private var progressedAt = 0L
    private var stage = 0
    private var lastActionAt = 0L
    private var armed = false

    fun poll(now: Long, decoded: Long, mediaReady: Boolean = false): Action? {
        if (!armed) {
            if (decoded == 0L && !mediaReady) return null
            armed = true
            frames = decoded
            progressedAt = now
            lastActionAt = now
            return null
        }
        if (decoded != frames) {
            frames = decoded
            progressedAt = now
            stage = 0
            lastActionAt = now
            return null
        }
        val age = now - progressedAt
        val action = when {
            stage == 0 && age >= KEYFRAME_AFTER_MS -> Action.KEYFRAME
            stage == 1 && age >= MEDIA_OFFER_AFTER_MS -> Action.MEDIA_OFFER
            stage == 2 && age >= DISPLAY_AFTER_MS -> Action.DISPLAY
            stage >= 3 && now - lastActionAt >= RETRY_AFTER_MS -> Action.MEDIA_OFFER
            else -> return null
        }
        stage++
        lastActionAt = now
        return action
    }

    private companion object {
        const val KEYFRAME_AFTER_MS = 3000L
        const val MEDIA_OFFER_AFTER_MS = 8000L
        const val DISPLAY_AFTER_MS = 15000L
        const val RETRY_AFTER_MS = 15000L
    }
}
