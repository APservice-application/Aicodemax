package com.aicodemax.ai.tasks

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.state.AppEvent
import com.aicodemax.core.state.EventBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aicodemax.core.common.isSuccess
import com.aicodemax.core.common.isFailure

class FakeClock(var tick: Long = 100L) : Clock {
    override fun nowMillis(): Long = tick++
}

class RecordingBus : EventBus {
    private val flow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AppEvent> = flow
    val published = mutableListOf<AppEvent>()
    override suspend fun publish(event: AppEvent) {
        published.add(event)
        flow.emit(event)
    }
    override fun tryPublish(event: AppEvent): Boolean {
        published.add(event)
        return flow.tryEmit(event)
    }
}

class TaskEngineTest {
    private fun engine(bus: RecordingBus = RecordingBus()): TaskEngine =
        DefaultTaskEngine(bus, FakeClock())

    private fun Outcome<AiTask>.task(): AiTask = (this as Outcome.Success<AiTask>).value

    @Test
    fun happyPathToCompleted() {
        val bus = RecordingBus()
        val engine = engine(bus)
        val id = engine.create("demo").task().id
        val path = listOf(
            TaskState.QUEUED, TaskState.PLANNING, TaskState.READY,
            TaskState.RUNNING, TaskState.VERIFYING, TaskState.COMPLETED,
        )
        for (state in path) {
            assertTrue("transition to $state", engine.transition(id, state).isSuccess())
        }
        assertEquals(TaskState.COMPLETED, engine.get(id).task().state)
        // created + 6 transitions = 7 events
        assertEquals(7, bus.published.size)
    }

    @Test
    fun illegalTransitionFailsHonestly() {
        val engine = engine()
        val id = engine.create("demo").task().id
        val result = engine.transition(id, TaskState.RUNNING)
        assertTrue(result.isFailure())
        assertEquals("ILLEGAL_TRANSITION", (result as Outcome.Failure).error.code)
        assertEquals(TaskState.CREATED, engine.get(id).task().state)
    }

    @Test
    fun failRetryAndExhaustion() {
        val engine = engine()
        val id = engine.create("flaky").task().id
        engine.transition(id, TaskState.QUEUED)
        engine.transition(id, TaskState.PLANNING)
        engine.transition(id, TaskState.READY)
        engine.transition(id, TaskState.RUNNING)

        assertTrue(engine.fail(id, "boom").isSuccess())
        assertEquals("boom", engine.get(id).task().lastError)

        repeat(3) { round ->
            val retried = engine.retry(id)
            assertTrue("retry ${round + 1}", retried.isSuccess())
            assertEquals(round + 1, engine.get(id).task().attempts)
            assertTrue(engine.fail(id, "boom again").isSuccess())
        }
        val exhausted = engine.retry(id)
        assertTrue(exhausted.isFailure())
        assertEquals("RETRY_EXHAUSTED", (exhausted as Outcome.Failure).error.code)
    }

    @Test
    fun cannotCancelTerminalTask() {
        val engine = engine()
        val id = engine.create("done").task().id
        engine.transition(id, TaskState.QUEUED)
        engine.transition(id, TaskState.PLANNING)
        engine.transition(id, TaskState.READY)
        engine.transition(id, TaskState.RUNNING)
        engine.transition(id, TaskState.VERIFYING)
        engine.transition(id, TaskState.COMPLETED)
        val result = engine.cancel(id)
        assertTrue(result.isFailure())
        assertEquals("TASK_TERMINAL", (result as Outcome.Failure).error.code)
    }

    @Test
    fun waitingAndBlockedLoops() {
        val engine = engine()
        val id = engine.create("loops").task().id
        engine.transition(id, TaskState.QUEUED)
        engine.transition(id, TaskState.PLANNING)
        engine.transition(id, TaskState.READY)
        engine.transition(id, TaskState.RUNNING)
        assertTrue(engine.transition(id, TaskState.WAITING_USER).isSuccess())
        assertTrue(engine.transition(id, TaskState.RUNNING).isSuccess())
        assertTrue(engine.transition(id, TaskState.BLOCKED).isSuccess())
        assertTrue(engine.transition(id, TaskState.READY).isSuccess())
        assertTrue(engine.cancel(id).isSuccess())
        assertEquals(TaskState.CANCELLED, engine.get(id).task().state)
    }
}
