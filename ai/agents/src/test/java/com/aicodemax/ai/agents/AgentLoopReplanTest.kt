package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.ExecutedStep
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.RePlanner
import com.aicodemax.ai.core.ReplanRequest
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-148: Diagnose → Correct → Execute Again inside the loop. */
class AgentLoopReplanTest {
    private fun step(id: String, args: Map<String, String> = emptyMap()) =
        PlanStep(id, "browser", "open", args)

    private class ScriptRePlanner(private val plans: List<Outcome<Plan>>) : RePlanner {
        val seen = mutableListOf<ReplanRequest>()
        override suspend fun replan(req: ReplanRequest): Outcome<Plan> {
            seen.add(req)
            return plans.getOrElse(seen.size - 1) { plans.last() }
        }
    }

    @Test
    fun replanOnElementNotFoundThenSuccess() = runBlocking {
        var calls = 0
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
                calls++
                return if (calls == 1) Outcome.Success(StepResult(false, "", "ELEMENT_NOT_FOUND #q"))
                else Outcome.Success(StepResult(true, "opened", ""))
            }
        }
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
            executor,
            replan = ScriptRePlanner(listOf(Outcome.Success(Plan(listOf(step("fixed")))))),
        )
        val result = (loop.run("t1", Plan(listOf(step("s1"))), "open x", onEvent = { events.add(it) })
            as Outcome.Success<AgentRunResult>).value
        assertTrue(result.completed)
        assertEquals(1, result.replans)
        assertEquals("DONE", result.stopReason)
        assertTrue(events.any { it is AgentEvent.Replanned })
        assertTrue(events.last() is AgentEvent.Stopped)
    }

    @Test
    fun blindRetryRecoversTransientFailure() = runBlocking {
        var calls = 0
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
                calls++
                return if (calls == 1) Outcome.Success(StepResult(false, "", "boom"))
                else Outcome.Success(StepResult(true, "ok", ""))
            }
        }
        val loop = AgentLoop(executor, replan = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList())))))
        val result = (loop.run("t1", Plan(listOf(step("s1")))) as Outcome.Success<AgentRunResult>).value
        assertTrue(result.completed)
        assertEquals(0, result.replans)
        assertEquals(1, result.stepsExecuted)
        // Only terminal outcomes reach results/verify.
        assertEquals(1, result.results.size)
    }

    @Test
    fun replanBudgetExhaustsHonestly() = runBlocking {
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                Outcome.Success(StepResult(false, "", "ELEMENT_NOT_FOUND #q"))
        }
        val loop = AgentLoop(
            executor, replan = ScriptRePlanner(listOf(Outcome.Success(Plan(listOf(step("n1")))))),
            maxReplans = 1,
        )
        val result = (loop.run("t1", Plan(listOf(step("s1")))) as Outcome.Success<AgentRunResult>).value
        assertTrue(!result.completed)
        assertTrue(result.stopReason.startsWith("REPLAN_EXHAUSTED"))
        assertEquals(1, result.replans)
    }

    @Test
    fun authStopsAndPriorSeedsResume() = runBlocking {
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                Outcome.Success(StepResult(false, "", "AUTH_REQUIRED please login"))
        }
        val loop = AgentLoop(executor, replan = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList())))))
        val first = (loop.run("t1", Plan(listOf(step("s1")))) as Outcome.Success<AgentRunResult>).value
        assertTrue(!first.completed)
        assertTrue(first.stopReason.startsWith("AUTH_REQUIRED"))

        // After the user logs in: resume with prior outcomes visible to verify.
        var verifiedSize = 0
        val okExec = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                Outcome.Success(StepResult(true, "opened", ""))
        }
        val resume = AgentLoop(okExec, verify = { _, results ->
            verifiedSize = results.size
            VerifyVerdict(true)
        })
        val prior = listOf(ExecutedStep(step("s1"), StepResult(false, "", "AUTH_REQUIRED please login")))
        val second = (resume.run("t1", Plan(listOf(step("s2"))), prior = prior)
            as Outcome.Success<AgentRunResult>).value
        assertTrue(second.completed)
        assertEquals(2, verifiedSize)
        assertEquals(1, second.stepsExecuted)
    }

    @Test
    fun verifyFailureTriggersReplan() = runBlocking {
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                Outcome.Success(StepResult(true, "ok", ""))
        }
        var verifyCalls = 0
        val loop = AgentLoop(
            executor,
            verify = { _, _ ->
                verifyCalls++
                if (verifyCalls == 1) VerifyVerdict(false, "cart is empty") else VerifyVerdict(true)
            },
            replan = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList())))),
        )
        val result = (loop.run("t1", Plan(listOf(step("s1"))), "buy x")
            as Outcome.Success<AgentRunResult>).value
        assertTrue(result.completed)
        assertEquals(1, result.replans)
        assertEquals(2, verifyCalls)
    }

    @Test
    fun invalidUrlFixesArgsAndRetries() = runBlocking {
        val seenUrls = mutableListOf<String?>()
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
                seenUrls.add(step.args["url"])
                return if (seenUrls.size == 1) Outcome.Success(StepResult(false, "", "INVALID_URL bad"))
                else Outcome.Success(StepResult(true, "opened", ""))
            }
        }
        val loop = AgentLoop(executor, replan = ScriptRePlanner(listOf(Outcome.Success(Plan(emptyList())))))
        val result = (loop.run("t1", Plan(listOf(step("s1", mapOf("url" to "example.com")))))
            as Outcome.Success<AgentRunResult>).value
        assertTrue(result.completed)
        assertEquals(listOf("example.com", "https://example.com"), seenUrls)
    }

    @Test
    fun replanFailureStopsHonestly() = runBlocking {
        val executor = object : AgentExecutor {
            override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> =
                Outcome.Success(StepResult(false, "", "ELEMENT_NOT_FOUND #q"))
        }
        val loop = AgentLoop(
            executor,
            replan = ScriptRePlanner(listOf(Outcome.Failure(AppError("BRAIN_OFF", "down")))),
        )
        val result = (loop.run("t1", Plan(listOf(step("s1")))) as Outcome.Success<AgentRunResult>).value
        assertTrue(!result.completed)
        assertTrue(result.stopReason.startsWith("REPLAN_FAILED"))
    }
}
