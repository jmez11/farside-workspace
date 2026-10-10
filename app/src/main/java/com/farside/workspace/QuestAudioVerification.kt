package com.farside.workspace

import androidx.annotation.Keep

@Keep
internal object QuestAudioVerification {
    @JvmStatic external fun verifyPlaybackTeardown(): Boolean
}
