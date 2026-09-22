package com.aicodemax.tools.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-98: offline dictionary translator. */
class TranslatorTest {
    @Test
    fun thaiToEnglish() {
        val tr = Translator.translate("สวัสดีครับ ขอบคุณที่รับชม", "th-en")
        assertEquals("Hello Thanks for watching", tr.text)
        assertEquals(100, tr.coveragePct)
    }

    @Test
    fun englishToThai() {
        val tr = Translator.translate("Hello everyone", "en-th")
        assertEquals("สวัสดีทุกคน", tr.text)
    }

    @Test
    fun unknownPassesThrough() {
        val tr = Translator.translate("สวัสดีครับ xyzzy", "th-en")
        assertTrue(tr.text.contains("xyzzy"))
        assertTrue(tr.coveragePct < 100)
        try {
            Translator.translate("hi", "th-jp")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("th-en"))
        }
    }
}
