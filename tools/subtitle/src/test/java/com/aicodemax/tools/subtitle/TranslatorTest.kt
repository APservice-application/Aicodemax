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
    fun llmBatchesAndAligns() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val chat: suspend (String) -> com.aicodemax.core.common.Outcome<String> = { prompt ->
            calls++
            val body = prompt.substringAfter(":\n")
            com.aicodemax.core.common.Outcome.Success(body.lines().joinToString("\n") { "EN:$it" })
        }
        val text = (1..30).joinToString("\n") { "บรรทัด$it" }
        val out = Translator.translateViaLlm(text, "th-en", chat)
        assertTrue(out is com.aicodemax.core.common.Outcome.Success)
        assertEquals(2, calls)
        val tr = (out as com.aicodemax.core.common.Outcome.Success).value
        assertEquals(30, tr.text.lines().size)
        assertTrue(tr.text.startsWith("EN:บรรทัด1"))
    }

    @Test
    fun llmMisalignFailsHonestly() = kotlinx.coroutines.runBlocking {
        val chat: suspend (String) -> com.aicodemax.core.common.Outcome<String> =
            { com.aicodemax.core.common.Outcome.Success("only one line") }
        val out = Translator.translateViaLlm("a\nb\nc", "th-en", chat)
        assertTrue(out is com.aicodemax.core.common.Outcome.Failure)
        assertEquals("SUB_LLM_ALIGN", (out as com.aicodemax.core.common.Outcome.Failure).error.code)
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
