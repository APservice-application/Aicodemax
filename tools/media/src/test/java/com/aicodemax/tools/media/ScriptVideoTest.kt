package com.aicodemax.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-97: script beats. */
class ScriptVideoTest {
    @Test
    fun splitsBeats() {
        val beats = ScriptVideo.parse("สวัสดีครับ\n\nวันนี้รีวิวกาแฟ\n# คอมเมนต์\n\nขอบคุณครับ")
        assertEquals(3, beats.size)
        assertTrue(beats[1].contains("กาแฟ"))
    }

    @Test
    fun emptyAndLongFailHonestly() {
        try {
            ScriptVideo.parse("   ")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ว่าง"))
        }
        try {
            ScriptVideo.parse((1..25).joinToString("\n\n") { "ช่วง $it" })
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ยาวไป"))
        }
    }
}
