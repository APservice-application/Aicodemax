package com.aicodemax.tools.gateway

import com.aicodemax.core.common.Outcome

/** User decisions from the permission UI (MASTER_ARCHITECTURE §32). */
enum class PermissionDecision { ALLOW_ONCE, ALLOW_FOR_TASK, DENY }

/**
 * Grant store behind the permission gate (CP-05 logic).
 * - ALLOW_ONCE: consumed by the next matching gated call.
 * - ALLOW_FOR_TASK: valid for every matching call with the same taskId.
 * - DENY: explicit blacklist — denies even under AUTO_ALL.
 * The UI (CP-46) records decisions here; the gate consults them.
 */
interface PermissionManager {
    fun decide(toolId: String, action: String, taskId: String, decision: PermissionDecision): Outcome<Unit>
    fun isDenied(toolId: String, action: String): Boolean

    /** Returns true when a stored grant covers this call (consumes one-shots). */
    fun consumeGrant(toolId: String, action: String, taskId: String): Boolean
    fun revokeTask(taskId: String)
}

class InMemoryPermissionManager : PermissionManager {
    private data class Key(val toolId: String, val action: String, val taskId: String)

    private val denies = mutableSetOf<Pair<String, String>>()
    private val taskGrants = mutableSetOf<Key>()
    private val oneShots = mutableListOf<Key>()

    @Synchronized
    override fun decide(
        toolId: String,
        action: String,
        taskId: String,
        decision: PermissionDecision,
    ): Outcome<Unit> {
        val key = Key(toolId, action, taskId)
        when (decision) {
            PermissionDecision.ALLOW_ONCE -> oneShots.add(key)
            PermissionDecision.ALLOW_FOR_TASK -> taskGrants.add(key)
            PermissionDecision.DENY -> {
                denies.add(toolId to action)
                taskGrants.remove(key)
            }
        }
        return Outcome.Success(Unit)
    }

    @Synchronized
    override fun isDenied(toolId: String, action: String): Boolean =
        denies.contains(toolId to action)

    @Synchronized
    override fun consumeGrant(toolId: String, action: String, taskId: String): Boolean {
        val key = Key(toolId, action, taskId)
        if (taskGrants.contains(key)) return true
        val index = oneShots.indexOf(key)
        if (index < 0) return false
        oneShots.removeAt(index)
        return true
    }

    @Synchronized
    override fun revokeTask(taskId: String) {
        taskGrants.removeIf { it.taskId == taskId }
        oneShots.removeIf { it.taskId == taskId }
    }
}
