package com.aicodemax.tools.gateway

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.map
import com.aicodemax.core.state.AppEvent
import com.aicodemax.core.state.EventBus
import com.aicodemax.data.audit.AuditLog
import com.aicodemax.tools.registry.ToolRegistry

interface ToolExecutor {
    val toolId: String
    suspend fun execute(call: ToolCall): Outcome<ToolResult>
}

interface ToolGateway {
    fun registerExecutor(executor: ToolExecutor)
    suspend fun call(call: ToolCall): Outcome<ToolResult>
}

/** Registry → permission → executor → audit pipeline (MASTER ARCHITECTURE §202). */
class DefaultToolGateway(
    private val registry: ToolRegistry,
    private val permissionGate: PermissionGate,
    private val audit: AuditLog,
    private val bus: EventBus,
) : ToolGateway {
    private val executors = mutableMapOf<String, ToolExecutor>()

    @Synchronized
    override fun registerExecutor(executor: ToolExecutor) {
        executors[executor.toolId] = executor
    }

    override suspend fun call(call: ToolCall): Outcome<ToolResult> {
        val descriptor = registry.get(call.toolId)
            ?: return Outcome.Failure(AppError("TOOL_UNKNOWN", "unknown tool '${call.toolId}'"))
        if (!descriptor.isRunnable()) {
            val reasons = descriptor.missingReasons().joinToString("; ")
            return Outcome.Failure(
                AppError("TOOL_NOT_RUNNABLE", "'${call.toolId}' is not runnable: $reasons"),
            )
        }
        val permission = permissionGate.check(call)
        if (permission is Outcome.Failure) {
            audit.append(
                actor = call.actor,
                action = "tool.call",
                toolId = call.toolId,
                detail = "${call.action} denied: ${permission.error.message}",
                allowed = false,
            )
            return Outcome.Failure(permission.error)
        }
        val executor = synchronized(this) { executors[call.toolId] }
            ?: return Outcome.Failure(AppError("TOOL_NO_EXECUTOR", "no executor for '${call.toolId}'"))
        val result = executor.execute(call)
        val auditEntry = audit.append(
            actor = call.actor,
            action = "tool.call",
            toolId = call.toolId,
            detail = "${call.action} ok=${result.fold({ it.ok }, { false })}",
            allowed = true,
        )
        val auditId = auditEntry.fold({ it.id }, { "" })
        bus.tryPublish(AppEvent.ToolOutput(call.toolId, call.id, result.fold({ it.output }, { it.message })))
        return result.map { it.copy(auditId = auditId) }
    }
}
