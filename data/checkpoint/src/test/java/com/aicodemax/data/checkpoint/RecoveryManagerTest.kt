package com.aicodemax.data.checkpoint

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class TickClock(var tick: Long = 1000L) : com.aicodemax.core.common.Clock {
        override fun nowMillis(): Long = tick++
    }

    private fun store() = FileCheckpointStore(tmp.root, TickClock())

    @Test
    fun planRestoreLatestRoundtrip() {
        val store = store()
        val first = (store.save("t1", "step1", "{}", listOf("a.txt")) as Outcome.Success<Checkpoint>).value
        val second = (store.save("t1", "step2", "{\"s\":2}", listOf("a.txt", "b.txt")) as Outcome.Success<Checkpoint>).value

        val recovery = RecoveryManager(store)
        val plan = (recovery.plan(second.id) as Outcome.Success<RestorePlan>).value
        assertEquals(2, plan.fileCount)

        val restoredFiles = mutableListOf<String>()
        val result = (recovery.restoreLatest("t1", FileRestorer { files ->
            restoredFiles.addAll(files)
            Outcome.Success(Unit)
        }) as Outcome.Success<RestoreResult>).value
        assertEquals(second.id, result.checkpointId)
        assertEquals(listOf("a.txt", "b.txt"), restoredFiles)
        assertEquals("{\"s\":2}", result.stateJson)
        assertEquals(first.id, first.id) // silence unused
    }

    @Test
    fun missingCheckpointFailsHonestly() {
        val recovery = RecoveryManager(store())
        assertTrue(recovery.plan("ghost") is Outcome.Failure)
        assertTrue(recovery.restoreLatest("ghost", FileRestorer { Outcome.Success(Unit) }) is Outcome.Failure)
    }

    @Test
    fun pruneKeepsNewest() {
        val store = store()
        repeat(4) { store.save("t1", "s$it", "{}", emptyList()) }
        val recovery = RecoveryManager(store)
        assertEquals(2, (recovery.prune("t1", keepLatest = 2) as Outcome.Success<Int>).value)
        assertEquals(2, (store.list("t1") as Outcome.Success<List<Checkpoint>>).value.size)
    }
}
