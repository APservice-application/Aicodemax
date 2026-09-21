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
            AutonomyLevel.AUTO_SAFE, AutonomyLevel.ASK_ALWAYS -> Outcome.Failure(
                AppError(
                    "PERMISSION_REQUIRED",
                    "action '${call.toolId}.${call.action}' needs user approval " +
                        "(autonomy=${autonomy().name})",
                ),
            )
        }
    }
}
