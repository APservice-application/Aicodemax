package com.aicodemax.ai.tasks

/** CP-29: recovery ladder (MASTER_ARCHITECTURE §34). Bounded — retry is NEVER unlimited. */
data class StepFailure(
    val stepId: String,
    val capabilityId: String,
    val error: String,
    val attempt: Int,
)

sealed interface RecoveryStep {
    /** Try the same step again (transient errors only). */
    data class Retry(val reason: String) : RecoveryStep

    /** Re-plan the step with a repair hint for the model. */
    data class Repair(val hint: String) : RecoveryStep

    /** Route the step to a fallback engine/capability. */
    data class SwitchEngine(val fromCapability: String, val toCapability: String) : RecoveryStep

    /** Roll the task back to its latest checkpoint, then stop. */
    data class RestoreLatest(val reason: String) : RecoveryStep

    /** Stop and ask the user to take over. */
    data class Escalate(val reason: String) : RecoveryStep

    /** Stop. No more attempts — the ladder is exhausted. */
    data class Abort(val reason: String) : RecoveryStep
}

class RecoveryLadderPolicy(
    private val maxAttempts: Int = 3,
    private val fallbacks: Map<String, String> = mapOf(
        "browser.open" to "browser.search",
        "terminal.exec" to "files.read",
    ),
) {
    /**
     * Ladder: RETRY (attempt 0) → SWITCH or REPAIR (attempt 1) →
     * RESTORE (attempt 2) → ESCALATE (attempt 3) → ABORT (beyond max).
     */
    fun decide(failure: StepFailure): RecoveryStep {
        if (failure.attempt >= maxAttempts.coerceAtLeast(1)) {
            return RecoveryStep.Abort("attempts exhausted (${failure.attempt}/$maxAttempts) for '${failure.stepId}'")
        }
        return when (failure.attempt) {
            0 -> RecoveryStep.Retry("first failure, retry once: ${failure.error.take(200)}")
            1 -> {
                val fallback = fallbacks[failure.capabilityId]
                if (fallback != null) {
                    RecoveryStep.SwitchEngine(failure.capabilityId, fallback)
                } else {
                    RecoveryStep.Repair("re-plan '${failure.stepId}' avoiding: ${failure.error.take(200)}")
                }
            }
            2 -> RecoveryStep.RestoreLatest("rolling back to latest checkpoint after: ${failure.error.take(200)}")
            else -> RecoveryStep.Escalate("needs user decision: ${failure.error.take(200)}")
        }
    }
}
