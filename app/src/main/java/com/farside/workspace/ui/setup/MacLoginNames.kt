package com.farside.workspace.ui.setup

import java.text.Normalizer
import java.util.Locale

internal object MacLoginNames {
    fun shortName(full:String):String=Normalizer.normalize(full,Normalizer.Form.NFD)
        .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"),"").replace(Regex("[^a-z0-9_.-]"),"")
    fun validShort(value:String)=value.matches(Regex("[a-z0-9_.-]+"))
}
