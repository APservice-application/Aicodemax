package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedPlannerTest {
    private val planner: Planner = RuleBasedPlanner()

    @Test
    fun createFilePlansTwoEditorSteps() = runBlocking {
        val intent = UserIntent(IntentType.CREATE_FILE, "t", mapOf("path" to "a.txt", "content" to "hi"))
        val plan = (planner.plan(intent) as Outcome.Success<Plan>).value
        assertEquals(2, plan.steps.size)
        assertEquals("editor", plan.steps[0].toolId)
        assertEquals("set", plan.steps[0].action)
        assertEquals("a.txt", plan.steps[0].args["path"])
        assertEquals("save", plan.steps[1].action)
    }

    @Test
    fun missingPathFailsHonestly() = runBlocking {
        val intent = UserIntent(IntentType.READ_FILE, "t")
        val result = planner.plan(intent)
        assertTrue(result is Outcome.Failure)
        assertEquals("PLAN_NO_PATH", (result as Outcome.Failure).error.code)
    }

    @Test
    fun deleteRequiresPermission() = runBlocking {
        val intent = UserIntent(IntentType.DELETE_PATH, "t", mapOf("path" to "a.txt"))
        val plan = (planner.plan(intent) as Outcome.Success<Plan>).value
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps[0].needsPermission)
        assertEquals("delete", plan.steps[0].action)
    }

    @Test
    fun openUrlAndGitPlanRealSteps() = runBlocking {
        val open = UserIntent(IntentType.OPEN_URL, "t", mapOf("url" to "https://example.com"))
        val openPlan = (planner.plan(open) as Outcome.Success<Plan>).value
        assertEquals(1, openPlan.steps.size)
        assertEquals("browser", openPlan.steps[0].toolId)
        assertEquals("open", openPlan.steps[0].action)

        val status = UserIntent(IntentType.GIT_ACTION, "t", mapOf("action" to "status", "repo" to ""))
        val statusPlan = (planner.plan(status) as Outcome.Success<Plan>).value
        assertEquals("git", statusPlan.steps[0].toolId)
        assertEquals("status", statusPlan.steps[0].action)

        val commit = UserIntent(
            IntentType.GIT_ACTION, "t",
            mapOf("action" to "commit", "repo" to "", "message" to "done"),
        )
        val commitPlan = (planner.plan(commit) as Outcome.Success<Plan>).value
        assertEquals(listOf("stage", "commit"), commitPlan.steps.map { it.action })

        val push = planner.plan(UserIntent(IntentType.GIT_ACTION, "t", mapOf("action" to "push")))
        assertTrue(push is Outcome.Failure)
        assertEquals("PLAN_UNSUPPORTED", (push as Outcome.Failure).error.code)
    }

    @Test
    fun unavailableRuntimesReportHonestly() = runBlocking {
        // Terminal CLI adapter exists but is unrunnable today → honest BLOCKED.
        val run = planner.plan(UserIntent(IntentType.RUN_COMMAND, "t", mapOf("command" to "ls")))
        assertTrue(run is Outcome.Failure)
        assertEquals("CAPABILITY_BLOCKED", (run as Outcome.Failure).error.code)

        // No build/test engines registered yet → honest UNKNOWN.
        for (type in listOf(IntentType.BUILD_PROJECT, IntentType.RUN_TESTS)) {
            val result = planner.plan(UserIntent(type, "t"))
            assertTrue("$type", result is Outcome.Failure)
            assertEquals("CAPABILITY_UNKNOWN", (result as Outcome.Failure).error.code)
        }
        val chat = planner.plan(UserIntent(IntentType.CHAT, "hi"))
        assertEquals("PLAN_NOT_ACTIONABLE", (chat as Outcome.Failure).error.code)
    }
}
