package com.farside.workspace

/** Apple hardware AVC cannot reliably restart for an odd-sized capture surface. */
internal data class MacVideoSize(val width:Int,val height:Int) {
    companion object {
        fun fromWindow(w:Int,h:Int)=MacVideoSize(w.coerceIn(200,8192) and -2,h.coerceIn(200,8192) and -2)
    }
}
