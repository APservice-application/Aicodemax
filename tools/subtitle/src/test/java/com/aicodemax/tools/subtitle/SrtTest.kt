package com.aicodemax.tools.subtitle

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {
    @Test
    fun stampFormats() {
        assertEquals("00:00:00,000", Srt.stamp(0))
        assertEquals("00:00:01,000", Srt.stamp(1000))
        assertEquals("01:02:03,456", Srt.stamp(3_723_456))
    }

    @Test
    fun roundTrip() {
        val cues = listOf(
            Cue(1000, 4000, listOf("สวัสดีครับ")),
            Cue(5000, 8000, listOf("line one", "line two")),
        )
        val parsed = (Srt.parse(Srt.format(cues)) as Outcome.Success<List<Cue>>).value
        assertEquals(cues, parsed)
    }

    @Test
    fun parseErrorsAreHonest() {
        assertTrue(Srt.parse("") is Outcome.Failure)
        assertTrue(Srt.parse("1\nno stamp here\ntext\n") is Outcome.Failure)
        assertTrue(Srt.parse("1\n00:00:05,000 --> 00:00:01,000\nbackwards\n") is Outcome.Failure)
    }

    @Test
    fun shiftMovesAndClamps() {
        val cues = listOf(Cue(1000, 2000, listOf("a")), Cue(5000, 6000, listOf("b")))
        val moved = Srt.shift(cues, 500)
        assertEquals(1500L, moved[0].startMs)
        assertEquals(6500L, moved[1].endMs)
        val back = Srt.shift(cues, -1500)
        assertEquals(0L, back[0].startMs)
        assertTrue(back[0].endMs > back[0].startMs)
    }

    @Test
    fun distributeCoversDuration() {
        val text = "สวัสดีครับทุกคนวันนี้เราจะมารีวิวของกินอร่อยๆกันนะครับฝากกดติดตามด้วยนะครับ ".repeat(3).trim()
        val cues = Srt.distribute(text, 10_000)
        assertTrue(cues.size >= 2)
        assertEquals(0L, cues.first().startMs)
        assertEquals(10_000L, cues.last().endMs)
        for (cue in cues) {
            assertTrue(cue.endMs > cue.startMs)
            assertTrue(cue.lines.size <= 2)
        }
        assertTrue(Srt.distribute("  ", 5000).isEmpty())
        assertTrue(Srt.distribute("hi", 0).isEmpty())
    }

    @Test
    fun wrapBalancesLines() {
        assertEquals(listOf("short"), Srt.wrap("short"))
        val two = Srt.wrap("this is a fairly long cue line that must split in two")
        assertEquals(2, two.size)
        assertTrue(two.all { it.length <= 42 })
    }
}
