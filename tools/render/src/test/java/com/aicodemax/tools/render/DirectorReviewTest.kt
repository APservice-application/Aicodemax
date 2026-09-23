package com.aicodemax.tools.render

import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorReviewTest {
    private fun clip(id: String, atMs: Long, durMs: Long = 2000): Clip =
        Clip(id, "a1", 0, durMs, atMs)

    private fun videoTrack(vararg clips: Clip): Track =
        Track("V1", MediaKind.VIDEO, clips.toList())

    @Test
    fun emptyFails() {
        val report = DirectorReview.review(Timeline())
        assertEquals(DirectorLevel.FAIL, report.verdict)
        assertTrue(report.verdictText().contains("ไม่มีคลิป"))
    }

    @Test
    fun overlapFails() {
        val timeline = Timeline(listOf(videoTrack(clip("c1", 0), clip("c2", 1500))))
        val report = DirectorReview.review(timeline)
        assertEquals(DirectorLevel.FAIL, report.verdict)
        assertTrue(report.notes.any { it.rule == "overlap" })
    }

    @Test
    fun gapWarns() {
        val timeline = Timeline(listOf(videoTrack(clip("c1", 0), clip("c2", 3000))))
        val report = DirectorReview.review(timeline)
        assertEquals(DirectorLevel.WARN, report.verdict)
        assertTrue(report.notes.any { it.rule == "gap" })
    }

    @Test
    fun flashAndUnsafeTextWarn() {
        val timeline = Timeline(
            listOf(videoTrack(clip("c1", 0, 200))),
            texts = listOf(OverlayText("t1", "hi", 0, 1000, xPct = 2, yPct = 50)),
        )
        val report = DirectorReview.review(timeline)
        assertEquals(DirectorLevel.WARN, report.verdict)
        assertTrue(report.notes.any { it.rule == "flash" })
        assertTrue(report.notes.any { it.rule == "safe" })
    }

    @Test
    fun cleanPasses() {
        val timeline = Timeline(
            listOf(videoTrack(clip("c1", 0), clip("c2", 2000))),
            texts = listOf(OverlayText("t1", "สวัสดี", 0, 3000)),
        )
        val report = DirectorReview.review(timeline)
        assertEquals(DirectorLevel.PASS, report.verdict)
        assertTrue(report.verdictText().contains("ผ่านฉลุย"))
    }
}
