package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolGateway

data class AgentDescriptor(
    val id: String,
    val name: String,
    val capabilities: List<String> = emptyList(),
)

/** Local agent: runs plan steps through the ToolGateway (permission + audit enforced). */
class LocalAgentRunner(private val gateway: ToolGateway) : AgentExecutor {
    val descriptor = AgentDescriptor("local", "Local Agent", listOf("files", "editor"))

    override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
        val call = ToolCall(
            id = Ids.newId("call"),
            toolId = step.toolId,
            action = step.action,
            args = step.args + ("taskId" to taskId),
            actor = "AI",
            needsPermission = step.needsPermission,
        )
        return gateway.call(call).fold(
            onSuccess = { result ->
                Outcome.Success(StepResult(ok = result.ok, output = result.output, error = result.error))
            },
            onFailure = { Outcome.Failure(it) },
        )
    }
}
