package com.aicodemax.tools.gateway

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.state.AutonomyLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCenterTest {
    private fun gatedCall(toolId: String = "files", action: String = "delete") = ToolCall(
        id = "c1",
        toolId = toolId,
        action = action,
        args = mapOf("path" to "a.txt", "taskId" to "t1"),
        actor = "AI",
        needsPermission = true,
    )

    @Test
    fun riskAdvisorMarksReadsSafe() {
        assertEquals(RiskLevel.SAFE, RiskAdvisor.suggest("files", "read").level)
        assertEquals(RiskLevel.SAFE, RiskAdvisor.suggest("git", "status").level)
        assertEquals(RiskLevel.RISKY, RiskAdvisor.suggest("files", "delete").level)
        assertEquals(RiskLevel.RISKY, RiskAdvisor.suggest("terminal", "exec").level)
        assertEquals(RiskLevel.RISKY, RiskAdvisor.suggest("mystery", "zap").level)
    }

    @Test
    fun autoSafePassesReadsAsksWrites() = runBlocking {
        val grants = InMemoryPermissionManager()
        var asked = 0
        val gate = AutonomyPermissionGate({ AutonomyLevel.AUTO_SAFE }, grants) { _ ->
            asked += 1
            Outcome.Success(PermissionDecision.ALLOW_ONCE)
        }
        assertTrue(gate.check(gatedCall(action = "read")) is Outcome.Success)
        assertEquals(0, asked)
        assertTrue(gate.check(gatedCall(action = "delete")) is Outcome.Success)
        assertEquals(1, asked)
    }

    @Test
    fun denyPropagatesAndBlacklists() = runBlocking {
        val grants = InMemoryPermissionManager()
        val gate = AutonomyPermissionGate({ AutonomyLevel.ASK_ALWAYS }, grants) { call ->
            grants.decide(call.toolId, call.action, "t1", PermissionDecision.DENY)
            Outcome.Success(PermissionDecision.DENY)
        }
        val result = gate.check(gatedCall())
        assertTrue(result is Outcome.Failure)
        assertEquals("PERMISSION_DENIED", (result as Outcome.Failure).error.code)
        assertTrue(grants.isDenied("files", "delete"))
    }

    @Test
    fun centerRoundtripThroughFlow() = runBlocking {
        val grants = InMemoryPermissionManager()
        val center = ApprovalCenter(grants, defaultTimeoutMs = 5_000)
        val job = async { center.requestApproval(gatedCall()) }
        // Wait until the request is visible, then approve for the task.
        var waited = 0
        while (center.pending.value.isEmpty() && waited < 5_000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        val request = center.pending.value.single()
        assertEquals("files.delete", request.what)
        assertTrue(request.scope.contains("a.txt"))
        assertTrue(center.decide(request.id, PermissionDecision.ALLOW_FOR_TASK))
        assertEquals(
            PermissionDecision.ALLOW_FOR_TASK,
            (job.await() as Outcome.Success<PermissionDecision>).value,
        )
        assertTrue(center.pending.value.isEmpty())
        assertTrue(grants.consumeGrant("files", "delete", "t1"))
    }

    @Test
    fun timeoutDenies() = runBlocking {
        val grants = InMemoryPermissionManager()
        val center = ApprovalCenter(grants, defaultTimeoutMs = 50)
        val result = center.requestApproval(gatedCall())
        assertTrue(result is Outcome.Failure)
        assertEquals("APPROVAL_TIMEOUT", (result as Outcome.Failure).error.code)
        assertTrue(grants.isDenied("files", "delete"))
    }
}
