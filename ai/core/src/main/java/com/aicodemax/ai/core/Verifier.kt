package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

interface Verifier {
    suspend fun verify(outputs: List<StepResult>): Outcome<Unit>
}

/** v0: every step must report ok. Semantic verification plugs in later. */
class RuleVerifier : Verifier {
    override suspend fun verify(outputs: List<StepResult>): Outcome<Unit> {
        val failed = outputs.firstOrNull { !it.ok }
        return if (failed == null) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError("VERIFY_FAILED", failed.error.ifBlank { "a step reported failure" }))
        }
    }
}
