package com.aicodemax.data.memory

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun engine() = MemoryEngine(FileMemoryStore(tmp.root))

    @Test
    fun scopesAreIsolated() {
        val engine = engine()
        engine.rememberProject("p1", "stack", "kotlin")
        engine.rememberTask("t1", "stack", "python")
        engine.rememberGlobal("stack", "none")

        assertEquals("kotlin", (engine.recallProject("p1", "stack") as Outcome.Success<MemoryRecord>).value.value)
        assertEquals("python", (engine.recallTask("t1", "stack") as Outcome.Success<MemoryRecord>).value.value)
        assertEquals("none", (engine.recall("GLOBAL", "stack") as Outcome.Success<MemoryRecord>).value.value)

        assertTrue(engine.forget("PROJECT:p1", "stack"))
        assertTrue(engine.recallProject("p1", "stack") is Outcome.Failure)
    }

    @Test
    fun compactKeepsNewestAndSummarizes() {
        val engine = engine()
        repeat(5) { engine.rememberProject("p1", "k$it", "v$it") }

        val deleted = (engine.compact("PROJECT:p1", keepLatest = 2) as Outcome.Success<Int>).value
        assertEquals(3, deleted)
        val remaining = (engine.recent("PROJECT:p1") as Outcome.Success<List<MemoryRecord>>).value
        assertEquals(2, remaining.size)

        val summary = (engine.summarizeForPrompt("PROJECT:p1", maxChars = 20) as Outcome.Success<String>).value
        assertTrue(summary.endsWith("…[truncated]"))
    }
}
