package com.aicodemax.ai.agents

import com.aicodemax.ai.core.AgentMemory
import com.aicodemax.ai.core.Plan
import com.aicodemax.ai.core.PlanStep
import com.aicodemax.ai.core.RePlanner
import com.aicodemax.ai.core.ReplanRequest
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.capability.CapabilityBinding
import com.aicodemax.tools.capability.CapabilityResolver

/**
 * CP-148 (spec §33–§34): the LLM rewrites the REMAINING plan after a failure.
 *
 * Input = goal + what already ran (observations) + the failed step + the
 * rule-based diagnosis (§32). Output = corrected remaining steps only — never
 * re-emits finished work. Same resolution/validation as [LlmPlanner].
 */
class LlmRePlanner(
    private val provider: () -> LlmProvider?,
    private val model: () -> String,
    private val resolver: CapabilityResolver,
    private val catalog: List<CapabilityBinding>,
    private val memory: AgentMemory? = null,
    private val maxSteps: Int = 6,
) : RePlanner {
    override suspend fun replan(req: ReplanRequest): Outcome<Plan> {
        val llm = provider()
            ?: return Outcome.Failure(AppError("BRAIN_OFF", "AI ในตัวยังไม่พร้อม"))
        val relevant = req.failedStep?.let { CatalogFilter.forTool(it.toolId, catalog) }
            ?: CatalogFilter.forTool("", catalog)
        if (relevant.isEmpty()) {
            return Outcome.Failure(AppError("PLAN_NO_TOOLS", "ไม่มี tool ให้วางแผนใหม่"))
        }
        val prompt = buildPrompt(req, relevant)
        val out = llm.chat(
            model().ifBlank { "default" },
            listOf(LlmMessage("user", prompt)),
            LlmPlanner.MAX_TOKENS,
        ).fold(onSuccess = { it.content }, onFailure = { return Outcome.Failure(it) })
        val parsed = PlanJson.parse(out)
            ?: return Outcome.Failure(AppError("PLAN_BAD_JSON", "AI วางแผนใหม่ไม่ได้ (ตอบกลับไม่ใช่ JSON)"))
        if (parsed.steps.isEmpty()) {
            val why = parsed.note.ifBlank { "ไม่มีทางแก้ด้วย tools ที่มี" }
            return Outcome.Failure(AppError("PLAN_NO_TOOLS", "AI วางแผนใหม่ไม่ได้: $why"))
        }
        val allowed = relevant.map { it.capabilityId }.toSet()
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
                            needsPermission = jsonStep.capability in LlmPlanner.SENSITIVE,
                            description = jsonStep.why.ifBlank { jsonStep.capability },
                        ),
                    )
                }
            }
        }
        return Outcome.Success(Plan(steps, note = parsed.note))
    }

    private fun buildPrompt(req: ReplanRequest, relevant: List<CapabilityBinding>): String {
        val done = req.executed.takeLast(6).joinToString("\n") {
            val r = it.result
            "- ${it.step.description.ifBlank { "${it.step.toolId}.${it.step.action}" }}: " +
                if (r.ok) "OK ${r.output.take(150)}" else "FAIL ${r.error.take(150)}"
        }
        val failed = req.failedStep?.let {
            "${it.toolId}.${it.action} ${it.args.entries.joinToString(" ") { e -> "${e.key}=${e.value}" }.take(200)}"
        } ?: "(verification of finished work failed)"
        val tools = relevant.joinToString("\n") { binding ->
            val inputs = binding.metadata.inputs.joinToString(",")
            val purpose = binding.metadata.purpose.ifBlank { binding.capabilityId }.take(60)
            "${binding.capabilityId}($inputs) - $purpose"
        }
        val mem = memory?.plannerContext()?.take(200).orEmpty()
        return buildString {
            appendLine("You are a re-planner. Goal: ${req.goal.take(300)}")
            if (done.isNotBlank()) appendLine("Already done (do NOT repeat):\n$done")
            appendLine("Failed: $failed")
            appendLine("Diagnosis: ${req.diagnosis.hint}")
            if (mem.isNotBlank()) appendLine("Memory:\n$mem")
            appendLine("Tools (capability_id(args) - purpose):")
            appendLine(tools)
            appendLine("Reply with ONLY this JSON (remaining steps, max ${maxSteps.coerceAtLeast(1)}):")
            append(PlanJson.SKELETON)
        }
    }
}
