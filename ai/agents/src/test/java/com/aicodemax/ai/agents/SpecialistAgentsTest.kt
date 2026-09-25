package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialistAgentsTest {
    private class EchoExecutor : AgentExecutor {
        var calls = 0
        override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
            calls++
            return Outcome.Success(StepResult(ok = true, output = "echo"))
        }
    }

    private fun step(toolId: String) = PlanStep("s1", toolId, "go")

    @Test
    fun fileAgentAllowsFilesOnly() = runBlocking {
        val base = EchoExecutor()
        val agent = fileAgentRunner(base)
        assertTrue((agent.executeStep("t", step("files")) as Outcome.Success).value.ok)
        assertTrue((agent.executeStep("t", step("editor")) as Outcome.Success).value.ok)
        val denied = (agent.executeStep("t", step("terminal")) as Outcome.Success).value
        assertTrue(!denied.ok)
        assertTrue(denied.error.contains("terminal"))
        assertEquals(2, base.calls)
    }

    @Test
    fun shellAgentAllowsTerminalOnly() = runBlocking {
        val base = EchoExecutor()
        val agent = shellAgentRunner(base)
        assertTrue((agent.executeStep("t", step("terminal")) as Outcome.Success).value.ok)
        val denied = (agent.executeStep("t", step("files")) as Outcome.Success).value
        assertTrue(!denied.ok)
        assertEquals(1, base.calls)
    }

    @Test
    fun registryHoldsMultiAgents() {
        val registry = AgentRegistry()
        val base = EchoExecutor()
        registry.register(RegisteredAgent("local", "Local", listOf("files", "terminal"), base))
        val files = fileAgentRunner(base)
        registry.register(RegisteredAgent(files.agentId, files.agentName, files.allowedTools.toList(), files))
        assertEquals(2, registry.list().size)
        assertEquals("File Agent", registry.get("files")!!.name)
        assertTrue(registry.get("ghost") == null)
    }
}
