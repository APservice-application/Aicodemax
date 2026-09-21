package com.aicodemax.tools.capability

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.registry.ToolRegistry

/**
 * Capability-centric resolution (MASTER_ARCHITECTURE §8/§9/§63).
 *
 * The AI thinks in capabilities ("files.read", "git.commit") — never in shell
 * commands. Each capability binds to one or more adapters, tried NATIVE-FIRST:
 * NATIVE → MANAGED_RUNTIME → EXTERNAL_API → CLI_ADAPTER (terminal, last resort).
 * When nothing runnable exists the outcome is an honest BLOCKED, never a guess.
 */
enum class AdapterKind {
    NATIVE,
    MANAGED_RUNTIME,
    EXTERNAL_API,
    CLI_ADAPTER,
}

data class CapabilityBinding(
    val capabilityId: String,
    val toolId: String,
    val action: String,
    val adapterKind: AdapterKind,
    val note: String = "",
)

data class ResolvedCapability(
    val capabilityId: String,
    val toolId: String,
    val action: String,
    val adapterKind: AdapterKind,
    val args: Map<String, String> = emptyMap(),
    val descriptor: ToolDescriptor,
)

interface CapabilityResolver {
    fun register(binding: CapabilityBinding)
    fun bindingsFor(capabilityId: String): List<CapabilityBinding>
    fun resolve(capabilityId: String, args: Map<String, String> = emptyMap()): Outcome<ResolvedCapability>
}

class DefaultCapabilityResolver(
    private val registry: ToolRegistry,
) : CapabilityResolver {
    private val bindings = mutableMapOf<String, MutableList<CapabilityBinding>>()

    override fun register(binding: CapabilityBinding) {
        bindings.getOrPut(binding.capabilityId) { mutableListOf() }.add(binding)
    }

    override fun bindingsFor(capabilityId: String): List<CapabilityBinding> =
        bindings[capabilityId]?.toList().orEmpty()

    override fun resolve(
        capabilityId: String,
        args: Map<String, String>,
    ): Outcome<ResolvedCapability> {
        val candidates = bindings[capabilityId].orEmpty()
        if (candidates.isEmpty()) {
            return Outcome.Failure(
                AppError("CAPABILITY_UNKNOWN", "unknown capability '$capabilityId'"),
            )
        }
        val tried = mutableListOf<String>()
        for (binding in candidates.sortedBy { it.adapterKind.ordinal }) {
            val descriptor = registry.get(binding.toolId)
            if (descriptor != null && descriptor.isRunnable()) {
                return Outcome.Success(
                    ResolvedCapability(
                        capabilityId = capabilityId,
                        toolId = binding.toolId,
                        action = binding.action,
                        adapterKind = binding.adapterKind,
                        args = args,
                        descriptor = descriptor,
                    ),
                )
            }
            val reason = descriptor?.missingReasons()?.firstOrNull() ?: "tool not registered"
            tried.add("${binding.toolId}/${binding.adapterKind.name.lowercase()} ($reason)")
        }
        return Outcome.Failure(
            AppError(
                "CAPABILITY_BLOCKED",
                "capability '$capabilityId' has no runnable adapter — tried: ${tried.joinToString("; ")}",
            ),
        )
    }
}
