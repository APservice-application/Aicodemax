package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-148: spec §32 diagnosis rules + planner cascade. */
class ReplanTest {
    private fun diag(code: String, msg: String, attempt: Int = 0, args: Map<String, String> = emptyMap()) =
        PlanDiagnoser.diagnose("browser", "open", code, msg, attempt, args)

    @Test
    fun authRequiredParksForUser() {
        val d = diag("AUTH_REQUIRED", "login wall")
        assertEquals(FailureAction.HANDOFF_AUTH, d.action)
        assertTrue(d.hint.contains("ล็อกอิน"))
    }

    @Test
    fun invalidUrlFixesArgs() {
        val d = diag("INVALID_URL", "bad", 0, mapOf("url" to "example.com/x"))
        assertEquals(FailureAction.FIX_AND_RETRY, d.action)
        assertEquals("https://example.com/x", d.fixedArgs["url"])
    }

    @Test
    fun networkRetriesTwiceThenAborts() {
        assertEquals(FailureAction.RETRY, diag("NETWORK_ERROR", "down", 0).action)
        assertEquals(FailureAction.RETRY, diag("NETWORK_ERROR", "down", 1).action)
        assertEquals(FailureAction.ABORT, diag("TIMEOUT", "timed out", 2).action)
    }

    @Test
    fun pageLoadRetriesOnceThenReplans() {
        assertEquals(FailureAction.RETRY, diag("PAGE_LOAD_FAILED", "x", 0).action)
        val d = diag("PAGE_LOAD_FAILED", "x", 1)
        assertEquals(FailureAction.REPLAN, d.action)
        assertTrue(d.hint.contains("วางแผนใหม่"))
    }

    @Test
    fun elementNotFoundReplans() {
        assertEquals(FailureAction.REPLAN, diag("ELEMENT_NOT_FOUND", "#q", 0).action)
    }

    @Test
    fun permissionDeniedAborts() {
        assertEquals(FailureAction.ABORT, diag("PERMISSION_DENIED", "no", 0).action)
    }

    @Test
    fun genericFailsRetryOnceThenReplan() {
        assertEquals(FailureAction.RETRY, diag("TOOL_FAILED", "boom", 0).action)
        assertEquals(FailureAction.REPLAN, diag("TOOL_FAILED", "boom", 1).action)
    }

    @Test
    fun fixUrlAddsScheme() {
        assertEquals("https://a.b", PlanDiagnoser.fixUrl("a.b"))
        assertEquals("http://a.b", PlanDiagnoser.fixUrl("http://a.b"))
    }

    @Test
    fun cascadeUsesPrimaryWhenOk() = runBlocking {
        val plan = Plan(listOf(PlanStep("s", "files", "read")))
        val cascade = CascadePlanner(
            object : Planner {
                override suspend fun plan(intent: UserIntent): Outcome<Plan> = Outcome.Success(plan)
            },
            object : Planner {
                override suspend fun plan(intent: UserIntent): Outcome<Plan> =
                    Outcome.Failure(AppError("NO", "must not be called"))
            },
        )
        val intent = UserIntent(IntentType.READ_FILE, "อ่านไฟล์ a", mapOf("path" to "a"))
        assertEquals(plan, (cascade.plan(intent) as Outcome.Success<Plan>).value)
    }

    @Test
    fun cascadeFallsBackAndMergesErrors() = runBlocking {
        val plan = Plan(listOf(PlanStep("s", "files", "read")))
        val cascade = CascadePlanner(
            object : Planner {
                override suspend fun plan(intent: UserIntent): Outcome<Plan> =
                    Outcome.Failure(AppError("P1", "primary bad"))
            },
            object : Planner {
                override suspend fun plan(intent: UserIntent): Outcome<Plan> = Outcome.Success(plan)
            },
        )
        val intent = UserIntent(IntentType.READ_FILE, "อ่านไฟล์ a", mapOf("path" to "a"))
        assertEquals(plan, (cascade.plan(intent) as Outcome.Success<Plan>).value)

        val both = CascadePlanner(
            object : Planner {
                override suspend fun plan(intent: UserIntent): Outcome<Plan> =
                    Outcome.Failure(AppError("P1", "primary bad"))
            },
            object : Planner {
                override suspend fun plan(intent: UserIntent): Outcome<Plan> =
                    Outcome.Failure(AppError("P2", "fallback bad"))
            },
        )
        val err = (both.plan(intent) as Outcome.Failure).error
        assertTrue(err.message.contains("primary bad") && err.message.contains("fallback bad"))
    }
}
