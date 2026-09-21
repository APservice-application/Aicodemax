package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/** CP-28: verification engine — per-engine read-back checks (MASTER_ARCHITECTURE §33). Engines self-verify; this engine collects the verdicts. */
data class EngineCheckResult(
    val engineId: String,
    val passed: Boolean,
    val evidence: String,
)

data class VerificationReport(
    val results: List<EngineCheckResult>,
) {
    val allOk: Boolean get() = results.all { it.passed }
    val failures: List<EngineCheckResult> get() = results.filter { !it.passed }
}

/** A self-check an engine runs to prove its last operation really happened. */
interface EngineCheck {
    val engineId: String
    suspend fun check(): Outcome<String>
}

class VerificationEngine(private val checks: List<EngineCheck> = emptyList()) {
    suspend fun verifyAll(): Outcome<VerificationReport> {
        val results = checks.map { check ->
            check.check().fold(
                onSuccess = { EngineCheckResult(check.engineId, true, it) },
                onFailure = { EngineCheckResult(check.engineId, false, "${it.code}: ${it.message}") },
            )
        }
        return Outcome.Success(VerificationReport(results))
    }

    suspend fun verifyEngine(engineId: String): Outcome<EngineCheckResult> {
        val check = checks.firstOrNull { it.engineId == engineId }
            ?: return Outcome.Failure(
                com.aicodemax.core.common.AppError("VERIFY_NO_ENGINE", "no check registered for '$engineId'"),
            )
        return Outcome.Success(
            check.check().fold(
                onSuccess = { EngineCheckResult(engineId, true, it) },
                onFailure = { EngineCheckResult(engineId, false, "${it.code}: ${it.message}") },
            ),
        )
    }
}
