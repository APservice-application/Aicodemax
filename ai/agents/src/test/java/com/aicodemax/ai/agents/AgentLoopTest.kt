package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private fun step(id: String) = PlanStep(id, "files", "read", mapOf("path" to "a.txt"))

    private fun okExecutor() = object : AgentExecutor {
        override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
            Outcome.Success(StepResult(ok = true, output = "ok", error = ""))
    }

    @Test
    fun completesAllOkPlan() = runBlocking {
        val result = (AgentLoop(okExecutor()).run("t1", Plan(listOf(step("s1"), step("s2"))))
            as Outcome.Success<AgentRunResult>).value
        assertTrue(result.completed)
        assertEquals(2, result.stepsExecuted)
        assertEquals("DONE", result.stopReason)
    }

    @Test
    fun stopsOnStepError() = runBlocking {
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                if (step.id == "bad") Outcome.Success(StepResult(ok = false, output = "", error = "denied"))
                else Outcome.Success(StepResult(ok = true, output = "ok", error = ""))
        }
        val result = (AgentLoop(executor).run("t1", Plan(listOf(step("s1"), step("bad"), step("s3"))))
            as Outcome.Success<AgentRunResult>).value
        assertTrue(!result.completed)
        assertEquals(2, result.stepsExecuted)
        assertTrue(result.stopReason.startsWith("STEP_ERROR"))
    }

    @Test
    fun detectsStuckLoop() = runBlocking {
        val same = step("same")
        val result = (AgentLoop(okExecutor()).run("t1", Plan(listOf(same, same, same, same)))
            as Outcome.Success<AgentRunResult>).value
        assertTrue(!result.completed)
        assertTrue(result.stopReason.startsWith("STUCK"))
        assertEquals(2, result.stepsExecuted)
    }

    @Test
    fun enforcesStepCap() = runBlocking {
        val steps = List(30) {
            PlanStep("s$it", "files", "read", mapOf("path" to "f$it.txt"))
        }
        val result = (AgentLoop(okExecutor(), maxSteps = 10).run("t1", Plan(steps))
            as Outcome.Success<AgentRunResult>).value
        assertTrue(!result.completed)
        assertEquals(10, result.stepsExecuted)
        assertTrue(result.stopReason.startsWith("STEP_CAP"))
    }

    @Test
    fun executorFailureStopsRun() = runBlocking {
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                Outcome.Failure(AppError("X", "gateway down"))
        }
        val result = (AgentLoop(executor).run("t1", Plan(listOf(step("s1"))))
            as Outcome.Success<AgentRunResult>).value
        assertTrue(!result.completed)
        assertTrue(result.stopReason.startsWith("STEP_FAILED"))
    }
}
