package com.farside.workspace

import com.farside.workspace.ui.setup.MacLoginNames
import org.junit.Assert.*
import org.junit.Test

class MacLoginNamesTest {
    @Test fun derivesPortableShortNameWithoutAccentsSpacesOrPunctuation() {
        assertEquals("englebertrumplestiltskin",MacLoginNames.shortName("Englebert Rumplestiltskin"))
        assertEquals("elodie_m.-7",MacLoginNames.shortName("\u00c9lodie_M. -7!"))
    }
    @Test fun rejectsEmptyUppercaseAndInvalidOverrides() {
        assertTrue(MacLoginNames.validShort("a_1.-"))
        listOf("","Upper","a b","\u00e9").forEach {assertFalse(MacLoginNames.validShort(it))}
    }
}
