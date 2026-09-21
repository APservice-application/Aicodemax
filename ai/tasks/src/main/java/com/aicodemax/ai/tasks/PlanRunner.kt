package com.aicodemax.ai.tasks

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.fold
import java.util.concurrent.ConcurrentHashMap

/** Plan → step → tool → verify loop (CP-12 Agent Runtime backfill; MASTER §24). */
data class PlanStep(
    val id: String,
    val capabilityId: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    val verify: String = "",
)

data class StepObservation(
    val stepId: String,
    val ok: Boolean,
    val output: String,
    val attempt: Int,
    val observedAt: Long,
)

data class PlanOutcome(
    val taskId: String,
    val completed: Boolean,
    val failedStepId: String = "",
    val observations: List<StepObservation>,
)

/** Executes one step (capability call). Task/agent layers provide the impl. */
interface StepRunner {
    suspend fun run(task: AiTask, step: PlanStep): Outcome<String>
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseBackoffMs: Long = 1_000,
) {
    fun shouldRetry(task: AiTask): Boolean = task.attempts < task.maxAttempts.coerceAtMost(maxAttempts)

    fun backoffMs(attempt: Int): Long = baseBackoffMs * (1L shl attempt.coerceIn(0, 10))

    companion object {
        val NONE = RetryPolicy(maxAttempts = 1, baseBackoffMs = 0)
    }
}

class PlanRunner(
    private val engine: TaskEngine,
    private val clock: Clock = SystemClock,
) {
    private val history = ConcurrentHashMap<String, MutableList<StepObservation>>()

    fun history(taskId: String): List<StepObservation> = history[taskId]?.toList().orEmpty()

    /**
     * Runs [steps] in order. On step failure the task is marked FAILED and,
     * when [policy] allows, retried from the failed step (fresh attempt).
     */
    suspend fun run(
        taskId: String,
        steps: List<PlanStep>,
        runner: StepRunner,
        policy: RetryPolicy = RetryPolicy(),
    ): Outcome<PlanOutcome> {
        val task = engine.get(taskId).fold(
            onSuccess = { it },
            onFailure = { return Outcome.Failure(it) },
        )
        if (task.state != TaskState.CREATED && task.state != TaskState.RUNNING) {
            return Outcome.Failure(AppError("PLAN_BAD_STATE", "task ${task.state} cannot run a plan"))
        }
        val observations = history.getOrPut(taskId) { mutableListOf() }
        var attempt = 0
        var index = 0
        if (task.state == TaskState.CREATED) {
            // Walk the legal path CREATED -> QUEUED -> PLANNING -> READY -> RUNNING.
            for (next in listOf(TaskState.QUEUED, TaskState.PLANNING, TaskState.READY, TaskState.RUNNING)) {
                val moved = engine.transition(taskId, next, "plan started (${steps.size} steps)")
                if (moved is Outcome.Failure) return moved
            }
        }
        while (index < steps.size) {
            val step = steps[index]
            val current = (engine.get(taskId) as? Outcome.Success)?.value
                ?: return Outcome.Failure(AppError("TASK_UNKNOWN", "task '$taskId' vanished"))
            if (current.state == TaskState.CANCELLED) {
                return Outcome.Success(PlanOutcome(taskId, completed = false, step.id, observations.toList()))
            }
            if (current.state == TaskState.PAUSED) {
                return Outcome.Success(PlanOutcome(taskId, completed = false, step.id, observations.toList()))
            }
            when (val result = runner.run(current, step)) {
                is Outcome.Success -> {
                    observations.add(StepObservation(step.id, true, result.value, attempt, clock.nowMillis()))
                    index += 1
                }
                is Outcome.Failure -> {
                    observations.add(StepObservation(step.id, false, result.error.message, attempt, clock.nowMillis()))
                    engine.fail(taskId, "${step.id}: ${result.error.message}")
                    val failed = (engine.get(taskId) as Outcome.Success<AiTask>).value
                    if (!policy.shouldRetry(failed)) {
                        return Outcome.Success(PlanOutcome(taskId, completed = false, step.id, observations.toList()))
                    }
                    engine.retry(taskId)
                    engine.transition(taskId, TaskState.RUNNING, "retry attempt ${failed.attempts + 1}")
                    attempt += 1
                }
            }
        }
        // RUNNING -> VERIFYING -> COMPLETED (transition table is law).
        val verifying = engine.transition(taskId, TaskState.VERIFYING, "plan finished, verifying")
        if (verifying is Outcome.Failure) return verifying
        val completed = engine.transition(taskId, TaskState.COMPLETED, "plan completed")
        if (completed is Outcome.Failure) return completed
        return Outcome.Success(PlanOutcome(taskId, completed = true, observations = observations.toList()))
    }
}
