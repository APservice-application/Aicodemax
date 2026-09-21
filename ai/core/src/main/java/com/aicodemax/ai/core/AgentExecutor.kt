package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome

data class StepResult(
    val ok: Boolean,
    val output: String = "",
    val error: String = "",
)

/** Port: executes one plan step. Implemented by :ai:agents. */
interface AgentExecutor {
    suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult>
}
