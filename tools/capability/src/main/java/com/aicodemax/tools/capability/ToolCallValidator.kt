package com.aicodemax.tools.capability

/**
 * CP-130 (spec Phase 15 + §15): validation pipeline for AI tool calls —
 * Schema → Permission → Precondition. Pure JVM, dependency-free.
 *
 * Works on primitives (not ToolCall) because tools:gateway depends on
 * tools:capability, never the reverse.
 */
object ToolCallValidator {
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val errors: List<String>) : Result
        data class NeedsApproval(val reason: String) : Result
    }

    fun validate(
        toolId: String,
        action: String,
        args: Map<String, String>,
        bindings: List<CapabilityBinding>,
        preconditionsMet: (String) -> Boolean = { true },
    ): Result {
        // -- Schema: capability must exist ---------------------------------
        val binding = bindings.firstOrNull { it.toolId == toolId && it.action == action }
            ?: return Result.Invalid(listOf(unknownActionError(toolId, action, bindings)))

        // -- Schema: required inputs present (trailing "?" = optional) -----
        val missing = requiredInputs(binding).filter { it !in args }
        if (missing.isNotEmpty()) {
            return Result.Invalid(missing.map { "missing arg: $it (${binding.capabilityId} needs ${requiredInputs(binding).joinToString(", ")})" })
        }

        // -- Precondition ---------------------------------------------------
        val unmet = binding.metadata.preconditions.filter { !preconditionsMet(it) }
        if (unmet.isNotEmpty()) {
            return Result.Invalid(unmet.map { "precondition unmet: $it (${binding.capabilityId})" })
        }

        // -- Permission (advisory: the gateway's PermissionGate decides) ----
        if (binding.metadata.risk == "high") {
            return Result.NeedsApproval("high-risk capability ${binding.capabilityId} requires approval")
        }
        return Result.Valid
    }

    /** Required input names (comma-split, "?" suffix = optional). */
    fun requiredInputs(binding: CapabilityBinding): List<String> =
        binding.metadata.inputs
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("?") }

    private fun unknownActionError(toolId: String, action: String, bindings: List<CapabilityBinding>): String {
        val sameTool = bindings.filter { it.toolId == toolId }.map { it.action }.distinct().sorted()
        return if (sameTool.isEmpty()) {
            val tools = bindings.map { it.toolId }.distinct().sorted()
            "unknown tool '$toolId' (have: ${tools.take(12).joinToString("/")}${if (tools.size > 12) "/…" else ""})"
        } else {
            "unknown action '$toolId.$action' (have: ${sameTool.joinToString("/")})"
        }
    }
}

/** Retry policy for invalid calls (§15: max ~3 attempts, then ask/stop). */
object ValidationRetry {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(failedAttempt: Int): Boolean = failedAttempt in 1..MAX_ATTEMPTS

    /** Feedback the agent uses to fix the next attempt. */
    fun feedback(invalid: ToolCallValidator.Result.Invalid, failedAttempt: Int): String = buildString {
        append("tool call ไม่ผ่าน (ครั้งที่ $failedAttempt/$MAX_ATTEMPTS): ")
        append(invalid.errors.joinToString("; "))
        if (shouldRetry(failedAttempt)) append(" — แก้ไขแล้วลองใหม่") else append(" — เกินจำนวนครั้งที่ลองได้ หยุด")
    }
}
