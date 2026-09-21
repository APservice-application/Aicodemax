package com.aicodemax.tools.gateway

import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.capability.CapabilityResolver

/**
 * CP-13: gateway ↔ resolver binding. Callers think in capabilities
 * ("git.commit"); the resolver picks the native-first adapter, then this runs
 * the normal registry → permission → executor → audit pipeline. A blocked
 * capability fails honestly here and never reaches an executor.
 */
suspend fun ToolGateway.callCapability(
    resolver: CapabilityResolver,
    capabilityId: String,
    args: Map<String, String> = emptyMap(),
    actor: String = "AI",
    needsPermission: Boolean = false,
): Outcome<ToolResult> {
    return when (val resolved = resolver.resolve(capabilityId, args)) {
        is Outcome.Failure -> resolved
        is Outcome.Success -> {
            val cap = resolved.value
            call(
                ToolCall(
                    id = Ids.newId("call"),
                    toolId = cap.toolId,
                    action = cap.action,
                    args = cap.args,
                    actor = actor,
                    needsPermission = needsPermission,
                ),
            )
        }
    }
}
