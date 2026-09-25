package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.memory.MemoryRecord
import com.aicodemax.data.memory.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-148: memory scopes + sensitive-data refusal (spec §35). */
class AgentMemoryTest {
    private class FakeStore : MemoryStore {
        val records = mutableListOf<MemoryRecord>()
        var tick = 1L
        override fun save(scope: String, key: String, value: String): Outcome<MemoryRecord> {
            val rec = MemoryRecord("m${tick}", scope, key, value, tick, tick)
            tick++
            records.removeAll { it.scope == scope && it.key == key }
            records.add(rec)
            return Outcome.Success(rec)
        }

        override fun recall(scope: String, key: String): Outcome<MemoryRecord> {
            val rec = records.firstOrNull { it.scope == scope && it.key == key }
            return if (rec == null) {
                Outcome.Failure(com.aicodemax.core.common.AppError("NO_MEMORY", "missing"))
            } else {
                Outcome.Success(rec)
            }
        }

        override fun search(query: String, scopePrefix: String, limit: Int): Outcome<List<MemoryRecord>> =
            Outcome.Success(records.filter { it.scope.startsWith(scopePrefix) }.take(limit))

        override fun delete(scope: String, key: String): Boolean =
            records.removeAll { it.scope == scope && it.key == key }
    }

    @Test
    fun refusesSensitiveKeysAndValues() {
        val mem = AgentMemory(FakeStore())
        assertTrue(mem.remember("PREFS", "api_key", "x") is Outcome.Failure)
        assertTrue(mem.remember("PREFS", "note", "password: 1234") is Outcome.Failure)
        assertTrue(mem.remember("PREFS", "theme", "dark") is Outcome.Success)
    }

    @Test
    fun shortNotesPrunedToCap() {
        val store = FakeStore()
        val mem = AgentMemory(store, FakeClock())
        repeat(15) { mem.shortNote("t1", "note$it") }
        val left = (store.search("", "SHORT:t1", 100) as Outcome.Success<List<MemoryRecord>>).value
        assertEquals(AgentMemory.SHORT_CAP, left.size)
        assertTrue(left.none { it.value == "note0" })
        assertTrue(left.any { it.value == "note14" })
    }

    @Test
    fun plannerContextIncludesPrefsAndLessons() {
        val mem = AgentMemory(FakeStore())
        mem.pref("lang", "th")
        mem.lesson("browser.open needs full URL")
        val ctx = mem.plannerContext()
        assertTrue(ctx.contains("lang=th"))
        assertTrue(ctx.contains("browser.open"))
    }

    @Test
    fun recallTextRoundTrips() {
        val mem = AgentMemory(FakeStore())
        mem.projectNote("p1", "goal", "ship v1")
        val back = mem.recallText("PROJECT:p1", "goal") as Outcome.Success<String>
        assertEquals("ship v1", back.value)
        assertTrue(mem.recallText("PROJECT:p1", "nope") is Outcome.Failure)
    }
}
