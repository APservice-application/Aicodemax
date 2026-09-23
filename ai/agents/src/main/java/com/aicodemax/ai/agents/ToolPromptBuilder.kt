package com.aicodemax.ai.agents

import com.aicodemax.tools.capability.CapabilityBinding
import com.aicodemax.tools.capability.FewShotStore
import com.aicodemax.tools.capability.StandardCapabilities
import com.aicodemax.tools.capability.ToolRetriever

/**
 * CP-131 (spec Phase 17 + §13/§16): per-turn tools section — never send all
 * tools. Top retrieved candidates with arg/risk hints + few-shot examples.
 */
class ToolPromptBuilder(
    private val bindings: List<CapabilityBinding> = StandardCapabilities.bindings(),
    val fewShot: FewShotStore = FewShotStore(),
    private val history: () -> List<String> = { emptyList() },
) {
    fun section(query: String): String = buildString {
        val candidates = ToolRetriever.retrieve(query, bindings, history(), limit = 5)
        appendLine("## Candidate tools (use ONLY these unless discovery finds more)")
        if (candidates.isEmpty()) {
            appendLine("(none match — answer directly, or discover: ACTION debug.tools {\"query\":\"<keywords>\"})")
        } else {
            candidates.forEach {
                val meta = it.binding.metadata
                val inputs = meta.inputs.ifEmpty { listOf("(no args)") }.joinToString(", ")
                appendLine("• ${it.binding.capabilityId} — ${meta.purpose} [args: $inputs; risk: ${meta.risk}]")
            }
            appendLine("Need another tool? Discover: ACTION debug.tools {\"query\":\"<keywords>\"}")
        }
        val examples = fewShot.relevant(query, limit = 3)
        if (examples.isNotEmpty()) {
            appendLine("## Examples that worked before")
            examples.forEach {
                val args = if (it.argsSkeleton.isEmpty()) "{}" else it.argsSkeleton.entries.joinToString(", ", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
                appendLine("User: ${it.query} → ACTION ${it.capabilityId} $args")
            }
        }
    }

    fun recordSuccess(query: String, capabilityId: String, args: Map<String, String>) {
        // Skeleton: keep keys, drop long values (prompt hygiene).
        val skeleton = args.mapValues { (_, v) -> if (v.length > 60) "…" else v }
        fewShot.record(query, capabilityId, skeleton, ok = true)
    }
}
