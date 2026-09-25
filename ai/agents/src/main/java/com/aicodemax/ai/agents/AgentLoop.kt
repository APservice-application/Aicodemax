package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.Diagnosis
import com.aicodemax.ai.core.ExecutedStep
import com.aicodemax.ai.core.FailureAction
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanDiagnoser
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.RePlanner
import com.aicodemax.ai.core.ReplanRequest
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.Outcome

/** Agent execution loop (CP-12 Agent Runtime backfill; MASTER §26). Think/plan stays in ai:core; this loop acts + observes with a step cap and stuck detection. */
data class AgentRunResult(
    val taskId: String,
    val completed: Boolean,
    val stepsExecuted: Int,
    val results: List<StepResult>,
    val stopReason: String,
    /** CP-148: how many LLM replans happened during this run. */
    val replans: Int = 0,
)

/** CP-147 (spec §8/§33–§34): the loop never reports success without verification. */
data class VerifyVerdict(
    val ok: Boolean,
    /** Why the outcome is wrong — the self-correction signal for re-planning. */
    val diagnosis: String = "",
)

/** CP-148: progress events (UI/audit may observe; null callback = silent). */
sealed interface AgentEvent {
    data class Step(val index: Int, val ok: Boolean) : AgentEvent
    data class Replanned(val attempt: Int, val steps: Int) : AgentEvent
    data class Stopped(val reason: String) : AgentEvent
}

class AgentLoop(
    private val executor: AgentExecutor,
    private val maxSteps: Int = 25,
    private val verify: ((plan: Plan, results: List<StepResult>) -> VerifyVerdict)? = null,
    /**
     * CP-148 (spec §34): null = v0 fail-fast with the EXACT old stop strings;
     * set = Diagnose → Correct → Execute Again with a bounded replan budget.
     */
    private val replan: RePlanner? = null,
    private val maxReplans: Int = 2,
    private val maxRetries: Int = 2,
) {
    suspend fun run(
        taskId: String,
        plan: Plan,
        goal: String = "",
        /** Prior run's outcomes (AUTH-resume path): seeded into verify + report. */
        prior: List<ExecutedStep> = emptyList(),
        onEvent: ((AgentEvent) -> Unit)? = null,
    ): Outcome<AgentRunResult> {
        val cap = maxSteps.coerceAtLeast(1)
        val initialTruncated = plan.steps.size > cap
        // context = every attempt (replan signal); finals = terminal outcomes (verify signal).
        val context = prior.toMutableList()
        val finals = prior.map { it.result }.toMutableList()
        val remaining = plan.steps.toMutableList()
        val attempts = mutableMapOf<String, Int>()
        var replansUsed = 0
        var newExecuted = 0
        var eventIndex = prior.size
        var lastKey = ""
        var repeatCount = 0

        fun record(step: PlanStep, result: StepResult, terminal: Boolean) {
            context.add(ExecutedStep(step, result))
            if (terminal) {
                finals.add(result)
                newExecuted++
                onEvent?.invoke(AgentEvent.Step(eventIndex++, result.ok))
            }
        }

        fun stop(completed: Boolean, reason: String): Outcome<AgentRunResult> {
            onEvent?.invoke(AgentEvent.Stopped(reason))
            return Outcome.Success(
                AgentRunResult(taskId, completed, newExecuted, finals.toList(), reason, replansUsed),
            )
        }

        suspend fun doReplan(failedStep: PlanStep?, diag: Diagnosis): Outcome<AgentRunResult>? {
            if (replansUsed >= maxReplans) return stop(false, "REPLAN_EXHAUSTED: ${diag.hint}")
            // The new plan supersedes the stale remainder (old assumptions died with the failure).
            when (val rp = replan!!.replan(ReplanRequest(goal, context.toList(), failedStep, diag, replansUsed + 1))) {
                is Outcome.Failure -> return stop(false, "REPLAN_FAILED: ${rp.error.message}")
                is Outcome.Success -> {
                    remaining.clear()
                    remaining.addAll(rp.value.steps)
                    replansUsed++
                    onEvent?.invoke(AgentEvent.Replanned(replansUsed, rp.value.steps.size))
                }
            }
            return null
        }

        suspend fun handleFailure(step: PlanStep, result: StepResult?, diag: Diagnosis, attempt: Int): Outcome<AgentRunResult>? {
            when (diag.action) {
                FailureAction.HANDOFF_AUTH -> {
                    if (result != null) record(step, result, terminal = true)
                    return stop(false, "AUTH_REQUIRED: ${diag.hint}")
                }
                FailureAction.ABORT -> {
                    if (result != null) record(step, result, terminal = true)
                    return stop(false, "ABORTED: ${diag.hint}")
                }
                FailureAction.RETRY -> {
                    if (attempt >= maxRetries) return doReplan(step, diag)
                    attempts[step.id] = attempt + 1
                    remaining.add(0, step)
                    return null
                }
                FailureAction.FIX_AND_RETRY -> {
                    if (attempt >= 1) return doReplan(step, diag)
                    attempts[step.id] = attempt + 1
                    remaining.add(0, step.copy(args = diag.fixedArgs))
                    return null
                }
                FailureAction.REPLAN -> return doReplan(step, diag)
            }
        }

        var verified = verify == null
        while (true) {
            while (remaining.isNotEmpty()) {
                if (newExecuted >= cap) {
                    val reason = if (replansUsed == 0 && initialTruncated && prior.isEmpty()) {
                        "STEP_CAP: plan has ${plan.steps.size} steps, cap is $maxSteps"
                    } else {
                        "STEP_CAP: executed $newExecuted steps (cap $maxSteps)"
                    }
                    return stop(false, reason)
                }
                val step = remaining.removeFirst()
                val key = "${step.toolId}:${step.action}:${step.args.entries.sortedBy { it.key }}"
                repeatCount = if (key == lastKey) repeatCount + 1 else 1
                lastKey = key
                if (repeatCount >= STUCK_AFTER) {
                    return stop(false, "STUCK: same step $STUCK_AFTER times")
                }
                val attempt = attempts[step.id] ?: 0
                when (val outcome = executor.executeStep(taskId, step)) {
                    is Outcome.Failure -> {
                        val err = outcome.error
                        if (replan == null) return stop(false, "STEP_FAILED: ${err.message}")
                        val diag = PlanDiagnoser.diagnose(
                            step.toolId, step.action, err.code, err.message, attempt, step.args,
                        )
                        handleFailure(step, null, diag, attempt)?.let { return it }
                    }
                    is Outcome.Success -> {
                        val result = outcome.value
                        if (!result.ok) {
                            if (replan == null) {
                                record(step, result, terminal = true)
                                return stop(false, "STEP_ERROR: ${result.error}")
                            }
                            val diag = PlanDiagnoser.diagnose(
                                step.toolId, step.action, "", result.error, attempt, step.args,
                            )
                            record(step, result, terminal = false)
                            handleFailure(step, result, diag, attempt)?.let { return it }
                        } else {
                            record(step, result, terminal = true)
                        }
                    }
                }
            }
            if (verified) break
            val verdict = verify!!(plan, finals.toList())
            if (verdict.ok) {
                verified = true
                break
            }
            if (replan == null) return stop(false, "VERIFY_FAILED: ${verdict.diagnosis}")
            val diag = PlanDiagnoser.diagnose("verify", "verify", "VERIFY_FAILED", verdict.diagnosis, replansUsed)
            if (diag.action == FailureAction.HANDOFF_AUTH) {
                return stop(false, "AUTH_REQUIRED: ${diag.hint}")
            }
            doReplan(null, diag)?.let { return it }
        }
        return stop(true, "DONE")
    }

    companion object {
        const val STUCK_AFTER = 3
    }
}
