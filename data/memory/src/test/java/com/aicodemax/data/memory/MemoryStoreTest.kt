package com.aicodemax.data.memory

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.aicodemax.core.common.isFailure

class FakeClock(var tick: Long = 7000L) : Clock {
    override fun nowMillis(): Long = tick++
}

class MemoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveRecallSearchDelete() {
        val store: MemoryStore = FileMemoryStore(tmp.root, FakeClock())
        store.save("GLOBAL", "username", "kris")
        store.save("PROJECT:p1", "stack", "kotlin compose")

        val recalled = (store.recall("GLOBAL", "username") as Outcome.Success<MemoryRecord>).value
        assertEquals("kris", recalled.value)

        val found = (store.search("kotlin") as Outcome.Success<List<MemoryRecord>>).value
        assertEquals(1, found.size)
        assertEquals("stack", found[0].key)

        assertTrue(store.delete("GLOBAL", "username"))
        assertTrue(store.recall("GLOBAL", "username").isFailure())
    }
}
