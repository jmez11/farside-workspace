package com.farside.workspace

import org.junit.Assert.*
import org.junit.Test

class QuestWindowManagerTest {
    @Test fun explicitlyDisabledNavigatorHidesTheShortcut() {
        assertFalse(QuestWindowManager.canShow(false, true))
    }

    @Test fun unreadableNavigatorUsesShortcutAvailability() {
        assertTrue(QuestWindowManager.canShow(null, true))
        assertFalse(QuestWindowManager.canShow(null, false))
    }

    @Test fun enabledNavigatorStillNeedsTheShortcutReceiver() {
        assertFalse(QuestWindowManager.canShow(true, false))
        assertTrue(QuestWindowManager.canShow(true, true))
    }
}
