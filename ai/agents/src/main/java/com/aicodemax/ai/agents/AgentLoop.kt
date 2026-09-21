package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/** Agent execution loop (CP-12 Agent Runtime backfill; MASTER §26). Think/plan stays in ai:core; this loop acts + observes with a step cap and stuck detection. */
data class AgentRunResult(
    val taskId: String,
    val completed: Boolean,
    val stepsExecuted: Int,
    val results: List<StepResult>,
    val stopReason: String,
)

class AgentLoop(
    private val executor: AgentExecutor,
    private val maxSteps: Int = 25,
) {
    suspend fun run(taskId: String, plan: Plan): Outcome<AgentRunResult> {
        val results = mutableListOf<StepResult>()
        var lastKey = ""
        var repeatCount = 0
        for (step in plan.steps.take(maxSteps.coerceAtLeast(1))) {
            val key = "${step.toolId}:${step.action}:${step.args.entries.sortedBy { it.key }}"
            repeatCount = if (key == lastKey) repeatCount + 1 else 1
            lastKey = key
            if (repeatCount >= STUCK_AFTER) {
                return Outcome.Success(
                    AgentRunResult(taskId, false, results.size, results.toList(), "STUCK: same step $STUCK_AFTER times"),
                )
            }
            val result = executor.executeStep(taskId, step).fold(
                onSuccess = { it },
                onFailure = {
                    return Outcome.Success(
                        AgentRunResult(taskId, false, results.size, results.toList(), "STEP_FAILED: ${it.message}"),
                    )
                },
            )
            results.add(result)
            if (!result.ok) {
                return Outcome.Success(
                    AgentRunResult(taskId, false, results.size, results.toList(), "STEP_ERROR: ${result.error}"),
                )
            }
        }
        val truncated = plan.steps.size > maxSteps.coerceAtLeast(1)
        return Outcome.Success(
            AgentRunResult(
                taskId = taskId,
                completed = !truncated,
                stepsExecuted = results.size,
                results = results.toList(),
                stopReason = if (truncated) "STEP_CAP: plan has ${plan.steps.size} steps, cap is $maxSteps" else "DONE",
            ),
        )
    }

    companion object {
        const val STUCK_AFTER = 3
    }
}
