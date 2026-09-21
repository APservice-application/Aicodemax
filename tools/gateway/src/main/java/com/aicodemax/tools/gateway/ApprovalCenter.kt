package com.aicodemax.tools.gateway

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** CP-05: WHAT/WHY/SCOPE/RISK approval flow. The gate suspends until the user decides (or it times out = deny). */
enum class RiskLevel { SAFE, RISKY }

data class RiskVerdict(val level: RiskLevel, val reason: String)

object RiskAdvisor {
    private val safeActions: Map<String, Set<String>> = mapOf(
        "files" to setOf("list", "read", "exists", "search", "metadata"),
        "editor" to setOf("open", "preview"),
        "git" to setOf("status", "log", "branches", "diff", "conflicts"),
        "browser" to setOf("list"),
    )

    fun suggest(toolId: String, action: String): RiskVerdict {
        if (safeActions[toolId]?.contains(action) == true) {
            return RiskVerdict(RiskLevel.SAFE, "read-only $toolId.$action")
        }
        return RiskVerdict(RiskLevel.RISKY, "$toolId.$action changes state or leaves the device")
    }
}

data class ApprovalRequest(
    val id: String,
    val toolId: String,
    val action: String,
    val actor: String,
    val taskId: String,
    /** WHAT */
    val what: String,
    /** WHY */
    val why: String,
    /** SCOPE */
    val scope: String,
    /** RISK */
    val risk: RiskVerdict,
)

class ApprovalCenter(
    private val grants: PermissionManager,
    private val defaultTimeoutMs: Long = 120_000,
) {
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<PermissionDecision>>()

    /** Suspends until the user decides. Timeout (or missing UI) = DENY. */
    suspend fun requestApproval(call: ToolCall, timeoutMs: Long = defaultTimeoutMs): Outcome<PermissionDecision> {
        val request = ApprovalRequest(
            id = Ids.newId("approval"),
            toolId = call.toolId,
            action = call.action,
            actor = call.actor,
            taskId = call.args["taskId"].orEmpty(),
            what = "${call.toolId}.${call.action}",
            why = if (call.actor == "AI") {
                "AI ขอทำ action นี้" + (call.args["taskId"]?.let { " (งาน $it)" } ?: "")
            } else {
                "${call.actor} ขอทำ action นี้"
            },
            scope = describeScope(call),
            risk = RiskAdvisor.suggest(call.toolId, call.action),
        )
        val waiter = CompletableDeferred<PermissionDecision>()
        waiters[request.id] = waiter
        _pending.value = _pending.value + request
        val decision = withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { waiter.await() }
        waiters.remove(request.id)
        _pending.value = _pending.value.filterNot { it.id == request.id }
        if (decision == null) {
            grants.decide(request.toolId, request.action, request.taskId, PermissionDecision.DENY)
            return Outcome.Failure(AppError("APPROVAL_TIMEOUT", "no decision in ${timeoutMs}ms — denied"))
        }
        grants.decide(request.toolId, request.action, request.taskId, decision)
        return Outcome.Success(decision)
    }

    /** Called by the approval UI. Returns false when the request is gone. */
    fun decide(requestId: String, decision: PermissionDecision): Boolean {
        val waiter = waiters[requestId] ?: return false
        return waiter.complete(decision)
    }

    private fun describeScope(call: ToolCall): String {
        val interesting = call.args.filterKeys { it != "taskId" }
        if (interesting.isEmpty()) return "(ไม่มีพารามิเตอร์)"
        return interesting.entries.joinToString(" • ") { "${it.key}=${it.value.take(80)}" }.take(300)
    }
}
