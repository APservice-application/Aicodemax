package com.aicodemax.ai.tasks

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.state.SharedFlowEventBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRunnerTest {
    private fun engine() = DefaultTaskEngine(SharedFlowEventBus())

    private fun okRunner(outputs: Map<String, String> = emptyMap()) = object : StepRunner {
        override suspend fun run(task: AiTask, step: PlanStep): Outcome<String> =
            Outcome.Success(outputs[step.id] ?: "ok:${step.id}")
    }

    @Test
    fun runsPlanToCompletion() = runBlocking {
        val engine = engine()
        val task = (engine.create("demo") as Outcome.Success<AiTask>).value
        val steps = listOf(
            PlanStep("s1", "files.read", "read"),
            PlanStep("s2", "files.write", "write"),
        )
        val outcome = (PlanRunner(engine).run(task.id, steps, okRunner()) as Outcome.Success<PlanOutcome>).value
        assertTrue(outcome.completed)
        assertEquals(2, outcome.observations.size)
        assertEquals(TaskState.COMPLETED, (engine.get(task.id) as Outcome.Success<AiTask>).value.state)
    }

    @Test
    fun retriesFailedStepThenSucceeds() = runBlocking {
        val engine = engine()
        val task = (engine.create("flaky") as Outcome.Success<AiTask>).value
        var calls = 0
        val flaky = object : StepRunner {
            override suspend fun run(task: AiTask, step: PlanStep): Outcome<String> {
                calls += 1
                return if (calls == 1) {
                    Outcome.Failure(com.aicodemax.core.common.AppError("E", "boom"))
                } else {
                    Outcome.Success("recovered")
                }
            }
        }
        val outcome = (PlanRunner(engine).run(task.id, listOf(PlanStep("s1", "x", "y")), flaky)
            as Outcome.Success<PlanOutcome>).value
        assertTrue(outcome.completed)
        assertEquals(2, outcome.observations.size)
        assertEquals(false, outcome.observations[0].ok)
        assertEquals(1, (engine.get(task.id) as Outcome.Success<AiTask>).value.attempts)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runBlocking {
        val engine = engine()
        val task = (engine.create("doomed") as Outcome.Success<AiTask>).value
        val always = object : StepRunner {
            override suspend fun run(task: AiTask, step: PlanStep): Outcome<String> =
                Outcome.Failure(com.aicodemax.core.common.AppError("E", "nope"))
        }
        val outcome = (PlanRunner(engine)
            .run(task.id, listOf(PlanStep("s1", "x", "y")), always, RetryPolicy(maxAttempts = 2))
            as Outcome.Success<PlanOutcome>).value
        assertTrue(!outcome.completed)
        assertEquals("s1", outcome.failedStepId)
        assertEquals(TaskState.FAILED, (engine.get(task.id) as Outcome.Success<AiTask>).value.state)
        assertEquals(RetryPolicy.NONE.backoffMs(0), 0L)
        assertEquals(4_000L, RetryPolicy().backoffMs(2))
    }
}
