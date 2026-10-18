package com.farside.workspace

/** Tested support policy: 207 fails physical drops; 209 works; 208 is unverified. */
internal object WindowPlacementSupport {
    fun isAvailable(readSdk: () -> Int = { metavr.os.Build.Version.getApiVersion() }): Boolean = try {
        readSdk() >= 209
    } catch (_: LinkageError) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
