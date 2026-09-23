package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineShortcutsTest {
    @Test
    fun resolveMappings() {
        assertEquals(TimelineShortcut.UNDO, TimelineShortcuts.resolve(54, ctrl = true))
        assertEquals(TimelineShortcut.REDO, TimelineShortcuts.resolve(55, ctrl = true))
        assertEquals(TimelineShortcut.REDO, TimelineShortcuts.resolve(54, ctrl = true, shift = true))
        assertEquals(TimelineShortcut.NEXT_CLIP, TimelineShortcuts.resolve(22, ctrl = false))
        assertEquals(TimelineShortcut.NEXT_CLIP, TimelineShortcuts.resolve(61, ctrl = false))
        assertEquals(TimelineShortcut.PREV_CLIP, TimelineShortcuts.resolve(21, ctrl = false))
        assertEquals(TimelineShortcut.DELETE_CLIP, TimelineShortcuts.resolve(67, ctrl = false))
        assertNull(TimelineShortcuts.resolve(22, ctrl = true))
        assertNull(TimelineShortcuts.resolve(99, ctrl = false))
    }

    @Test
    fun helpMentionsAll() {
        val help = TimelineShortcuts.help
        assertTrue(help.contains("Ctrl+Z") && help.contains("Ctrl+Y"))
        assertTrue(help.contains("Del"))
    }
}
