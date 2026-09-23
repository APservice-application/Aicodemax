package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.memory.FileMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearningEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun engine() = LearningEngine(FileMemoryStore(tmp.root))

    private fun Outcome<LearningEngine.Lesson>.lesson(): LearningEngine.Lesson =
        (this as Outcome.Success<LearningEngine.Lesson>).value

    @Test
    fun nothingPromotedBeforeMinObservations() {
        val learn = engine()
        repeat(2) { learn.observe("terminal.exec", false, "E") }
        val lesson = learn.observe("terminal.exec", false, "E").lesson()
        assertEquals(3, lesson.observations)
        // 3 fails → promoted flaky at exactly minObservations.
        assertTrue(learn.isPromoted(lesson))
        assertEquals("flaky", learn.verdict(lesson))
        assertEquals(-1, learn.preference("terminal.exec"))
    }

    @Test
    fun mixedResultsStayWatching() {
        val learn = engine()
        learn.observe("files.read", true)
        learn.observe("files.read", false, "E")
        val lesson = learn.observe("files.read", true).lesson()
        assertEquals(3, lesson.observations)
        assertTrue(!learn.isPromoted(lesson))
        assertEquals("watching", learn.verdict(lesson))
        assertEquals(0, learn.preference("files.read"))
    }

    @Test
    fun reliableToolPromotesPositive() {
        val learn = engine()
        repeat(4) { learn.observe("memory.save", true) }
        val lesson = learn.observe("memory.save", true).lesson()
        assertEquals(5, lesson.observations)
        assertTrue(learn.isPromoted(lesson))
        assertEquals("reliable", learn.verdict(lesson))
        assertEquals(1, learn.preference("memory.save"))
        assertNull(learn.flakyWarning("memory.save"))
    }

    @Test
    fun flakyWarningNamesToolAndCounts() {
        val learn = engine()
        repeat(4) { learn.observe("video.proxy", false, "PROBE_FAIL") }
        val warning = learn.flakyWarning("video.proxy")!!
        assertTrue(warning.contains("video.proxy"))
        assertTrue(warning.contains("4/4"))
        assertTrue(warning.contains("PROBE_FAIL"))
    }

    @Test
    fun confidenceGrowsWithEvidence() {
        val learn = engine()
        var last = 0.0
        repeat(6) {
            val lesson = learn.observe("skill.list", true).lesson()
            assertTrue(lesson.confidence > last)
            assertTrue(lesson.confidence < 1.0)
            last = lesson.confidence
        }
    }

    @Test
    fun lessonsListFlakyFirst() {
        val learn = engine()
        repeat(3) { learn.observe("good.tool", true) }
        repeat(3) { learn.observe("bad.tool", false, "X") }
        val lessons = (learn.lessons() as Outcome.Success<List<LearningEngine.Lesson>>).value
        assertEquals(2, lessons.size)
        assertEquals("bad.tool", lessons[0].toolAction)
        assertEquals("good.tool", lessons[1].toolAction)
    }

    @Test
    fun lessonsPersistAcrossEngineInstances() {
        engine().observe("render.run", true)
        engine().observe("render.run", true)
        val fresh = engine()
        val lessons = (fresh.lessons() as Outcome.Success<List<LearningEngine.Lesson>>).value
        assertEquals(1, lessons.size)
        assertEquals(2, lessons[0].observations)
    }

    @Test
    fun blankActionFailsHonestly() {
        val result = engine().observe("  ", true)
        assertTrue(result is Outcome.Failure)
    }
}
