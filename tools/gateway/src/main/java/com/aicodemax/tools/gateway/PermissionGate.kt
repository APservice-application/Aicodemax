package com.aicodemax.tools.gateway

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.state.AutonomyLevel

interface PermissionGate {
    suspend fun check(call: ToolCall): Outcome<Unit>
}

/**
 * ASK_ALWAYS denies permission-gated calls until the user approves them in UI;
 * AUTO_SAFE allows only safe calls; AUTO_ALL allows everything. Default is ASK_ALWAYS.
 */
class AutonomyPermissionGate(
    private val autonomy: () -> AutonomyLevel,
    private val grants: PermissionManager? = null,
    /** Present in the app (dialog UI); absent in headless/test contexts. */
    private val approver: (suspend (ToolCall) -> Outcome<PermissionDecision>)? = null,
) : PermissionGate {
    override suspend fun check(call: ToolCall): Outcome<Unit> {
        if (!call.needsPermission) return Outcome.Success(Unit)
        val taskId = call.args["taskId"].orEmpty()
        if (grants?.isDenied(call.toolId, call.action) == true) {
            return Outcome.Failure(
                AppError("PERMISSION_DENIED", "user denied '${call.toolId}.${call.action}'"),
            )
        }
        if (grants?.consumeGrant(call.toolId, call.action, taskId) == true) {
            return Outcome.Success(Unit)
        }
        return when (autonomy()) {
            AutonomyLevel.AUTO_ALL -> Outcome.Success(Unit)
            AutonomyLevel.AUTO_SAFE -> {
                // Safe reads pass; everything else asks the user.
                if (RiskAdvisor.suggest(call.toolId, call.action).level == RiskLevel.SAFE) {
                    Outcome.Success(Unit)
                } else {
                    ask(call)
                }
            }
            AutonomyLevel.ASK_ALWAYS -> ask(call)
        }
    }

    private suspend fun ask(call: ToolCall): Outcome<Unit> {
        val approver = approver ?: return Outcome.Failure(
            AppError(
                "PERMISSION_REQUIRED",
                "action '${call.toolId}.${call.action}' needs user approval " +
                    "(autonomy=${autonomy().name})",
            ),
        )
        return when (val decision = approver(call)) {
            is Outcome.Failure -> Outcome.Failure(decision.error)
            is Outcome.Success -> when (decision.value) {
                PermissionDecision.DENY -> Outcome.Failure(
                    AppError("PERMISSION_DENIED", "user denied '${call.toolId}.${call.action}'"),
                )
                PermissionDecision.ALLOW_ONCE, PermissionDecision.ALLOW_FOR_TASK -> Outcome.Success(Unit)
            }
        }
    }
}
