package com.aicodemax.data.checkpoint

import com.aicodemax.core.common.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.aicodemax.core.common.isFailure

class FakeClock(var tick: Long = 1000L) : Clock {
    override fun nowMillis(): Long = tick++
}

class CheckpointStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveLoadAndLatest() {
        val store: CheckpointStore = FileCheckpointStore(tmp.root, FakeClock())
        val first = (store.save("t1", "step1", "{}") as com.aicodemax.core.common.Outcome.Success<Checkpoint>).value
        val second = (store.save("t1", "step2", "{}") as com.aicodemax.core.common.Outcome.Success<Checkpoint>).value

        val loaded = store.load(first.id) as com.aicodemax.core.common.Outcome.Success<Checkpoint>
        assertEquals("step1", loaded.value.label)

        val latest = store.loadLatest("t1") as com.aicodemax.core.common.Outcome.Success<Checkpoint>
        assertEquals(second.id, latest.value.id)

        val list = store.list("t1") as com.aicodemax.core.common.Outcome.Success<List<Checkpoint>>
        assertEquals(2, list.value.size)
    }

    @Test
    fun missingCheckpointFailsHonestly() {
        val store: CheckpointStore = FileCheckpointStore(tmp.root, FakeClock())
        assertTrue(store.load("cp_nope").isFailure())
        assertTrue(store.loadLatest("unknown-task").isFailure())
    }
}
