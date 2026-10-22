package com.farside.workspace

/** Session/layout identity may change while pixel dimensions remain the same. */
internal class MacLayoutRefresh {
    private var previous:ByteArray?=null
    fun changed(payload:ByteArray):Boolean {
        if(previous?.contentEquals(payload)==true) return false
        previous=payload.copyOf()
        return true
    }
}
