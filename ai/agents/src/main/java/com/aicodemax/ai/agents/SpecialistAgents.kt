package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentExecutor
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.StepResult
import com.aicodemax.core.common.Outcome

/** One runnable agent: identity + capability allowlist + executor. */
data class RegisteredAgent(
    val id: String,
    val name: String,
    val capabilities: List<String>,
    val executor: AgentExecutor,
)

/** CP-69 multi-agent registry (local + specialists). Thread-safe. */
class AgentRegistry {
    private val lock = Any()
    private val agents = mutableMapOf<String, RegisteredAgent>()

    fun register(agent: RegisteredAgent) {
        synchronized(lock) { agents[agent.id] = agent }
    }

    fun get(id: String): RegisteredAgent? = synchronized(lock) { agents[id] }

    fun list(): List<RegisteredAgent> = synchronized(lock) { agents.values.sortedBy { it.id } }
}

/**
 * CP-69 specialist (also closes the CP-12 remainder): enforces a tool
 * allowlist before delegating to the base executor. Denials are honest
 * step errors, never silent skips.
 */
class AllowlistedAgentRunner(
    val agentId: String,
    val agentName: String,
    val allowedTools: Set<String>,
    private val base: AgentExecutor,
) : AgentExecutor {
    override suspend fun executeStep(taskId: String, step: PlanStep): Outcome<StepResult> {
        if (step.toolId !in allowedTools) {
            return Outcome.Success(
                StepResult(
                    ok = false,
                    error = "เอเจนต์ $agentName ใช้ ${step.toolId} ไม่ได้ " +
                        "(อนุญาต: ${allowedTools.sorted().joinToString()})",
                ),
            )
        }
        return base.executeStep(taskId, step)
    }
}

fun fileAgentRunner(base: AgentExecutor): AllowlistedAgentRunner =
    AllowlistedAgentRunner("files", "File Agent", setOf("files", "editor"), base)

fun shellAgentRunner(base: AgentExecutor): AllowlistedAgentRunner =
    AllowlistedAgentRunner("shell", "Shell Agent", setOf("terminal"), base)
