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
    fun unavailableRuntimesReportHonestly() = runBlocking {
        for (type in listOf(IntentType.RUN_COMMAND, IntentType.OPEN_URL, IntentType.BUILD_PROJECT)) {
            val result = planner.plan(UserIntent(type, "t"))
            assertTrue("$type", result is Outcome.Failure)
            assertEquals("PLAN_UNAVAILABLE", (result as Outcome.Failure).error.code)
        }
        val chat = planner.plan(UserIntent(IntentType.CHAT, "hi"))
        assertEquals("PLAN_NOT_ACTIONABLE", (chat as Outcome.Failure).error.code)
    }
}
