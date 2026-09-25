package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentMemory
import com.aicodemax.ai.core.IntentType
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.Planner
import com.aicodemax.ai.core.UserIntent
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.capability.CapabilityBinding
import com.aicodemax.tools.capability.CapabilityResolver

/**
 * CP-148 (spec §33): the LLM writes the Task Plan.
 *
 * Prompt = goal + memory context + the ~28 relevant capabilities (ctx is 2048
 * tokens — never the full catalog). Every emitted step is resolved through the
 * SAME [CapabilityResolver] as the rule planner (native-first, honest BLOCKED
 * when nothing runs). Unknown capabilities fail the plan — the model must
 * never invent tools.
 */
class LlmPlanner(
    private val provider: () -> LlmProvider?,
    private val model: () -> String,
    private val resolver: CapabilityResolver,
    private val catalog: List<CapabilityBinding>,
    private val memory: AgentMemory? = null,
    private val maxSteps: Int = 8,
) : Planner {
    companion object {
        /** Same gate as RuleBasedPlanner: destructive steps need permission. */
        val SENSITIVE = setOf("files.delete", "skill.remove")
        const val MAX_TOKENS = 384
    }

    override suspend fun plan(intent: UserIntent): Outcome<Plan> {
        if (intent.type == IntentType.CHAT || intent.type == IntentType.UNKNOWN) {
            return Outcome.Failure(AppError("PLAN_NOT_ACTIONABLE", "nothing to plan for chat"))
        }
        val llm = provider()
            ?: return Outcome.Failure(AppError("BRAIN_OFF", "AI ในตัวยังไม่พร้อม"))
        val relevant = CatalogFilter.forIntent(intent, catalog)
        if (relevant.isEmpty()) {
            return Outcome.Failure(AppError("PLAN_NOT_ACTIONABLE", "nothing to plan for ${intent.type}"))
        }
        val prompt = buildPrompt(intent, relevant)
        val first = chatText(llm, prompt)
        var parsed = first?.let { PlanJson.parse(it) }
        if (parsed == null && first != null) {
            // One correction round: show the bad output, demand JSON only.
            val retry = chatText(
                llm,
                "$prompt\n\nYour previous output was not valid JSON. Reply with ONLY the JSON object, no other text.\nBad output:\n${first.take(500)}",
            )
            parsed = retry?.let { PlanJson.parse(it) }
        }
        if (parsed == null) {
            return Outcome.Failure(AppError("PLAN_BAD_JSON", "AI วางแผนไม่ได้ (ตอบกลับไม่ใช่ JSON)"))
        }
        if (parsed.steps.isEmpty()) {
            val why = parsed.note.ifBlank { "ไม่มี tool ที่เหมาะ" }
            return Outcome.Failure(AppError("PLAN_NO_TOOLS", "AI วางแผนไม่ได้: $why"))
        }
        return resolve(parsed, relevant.map { it.capabilityId }.toSet())
    }

    private suspend fun chatText(llm: LlmProvider, prompt: String): String? =
        llm.chat(model().ifBlank { "default" }, listOf(LlmMessage("user", prompt)), MAX_TOKENS).fold(
            onSuccess = { it.content },
            onFailure = { null },
        )

    private fun resolve(parsed: PlanJson.ParsedPlan, allowed: Set<String>): Outcome<Plan> {
        val steps = mutableListOf<PlanStep>()
        for (jsonStep in parsed.steps.take(maxSteps.coerceAtLeast(1))) {
            if (jsonStep.capability !in allowed) {
                return Outcome.Failure(
                    AppError("PLAN_UNKNOWN_TOOL", "AI เรียก tool ที่ไม่มีจริง: ${jsonStep.capability}"),
                )
            }
            when (val resolved = resolver.resolve(jsonStep.capability, jsonStep.args)) {
                is Outcome.Failure -> return resolved
                is Outcome.Success -> {
                    val cap = resolved.value
                    steps.add(
                        PlanStep(
                            Ids.newId("step"),
                            cap.toolId,
                            cap.action,
                            cap.args,
                            needsPermission = jsonStep.capability in SENSITIVE,
                            description = jsonStep.why.ifBlank { jsonStep.capability },
                        ),
                    )
                }
            }
        }
        return Outcome.Success(Plan(steps, note = parsed.note))
    }

    private fun buildPrompt(intent: UserIntent, relevant: List<CapabilityBinding>): String {
        val goal = intent.rawText.ifBlank { intent.type.name }
        val params = intent.parameters.entries.sortedBy { it.key }
            .joinToString("\n") { "- ${it.key}: ${it.value}" }
        val tools = relevant.joinToString("\n") { binding ->
            val inputs = binding.metadata.inputs.joinToString(",")
            val purpose = binding.metadata.purpose.ifBlank { binding.capabilityId }.take(60)
            "${binding.capabilityId}($inputs) - $purpose"
        }
        val mem = memory?.plannerContext()?.take(300).orEmpty()
        return buildString {
            appendLine("You are a planner. Goal: $goal")
            if (params.isNotBlank()) appendLine("Known parameters:\n$params")
            if (mem.isNotBlank()) appendLine("Memory:\n$mem")
            appendLine("Tools (capability_id(args) - purpose):")
            appendLine(tools)
            appendLine("Reply with ONLY this JSON (max ${maxSteps.coerceAtLeast(1)} steps, use only listed capabilities):")
            append(PlanJson.SKELETON)
        }
    }
}
