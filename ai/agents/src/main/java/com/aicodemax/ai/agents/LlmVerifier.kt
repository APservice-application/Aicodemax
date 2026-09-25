package com.aicodemax.ai.agents

import com.aicodemax.ai.core.RuleVerifier
import com.aicodemax.ai.core.StepResult
import com.aicodemax.ai.core.Verifier
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CP-148 (spec §33–§34): semantic verification — did the goal ACTUALLY happen?
 *
 * The LLM judges goal vs outputs (`{"ok":bool,"reason":"..."}`). When the
 * brain is off or answers garbage, it degrades to [RuleVerifier] (every step
 * ok) instead of inventing a verdict.
 */
class LlmVerifier(
    private val provider: () -> LlmProvider?,
    private val model: () -> String,
    private val goal: String,
) : Verifier {
    private val json = Json { ignoreUnknownKeys = true }
    private val rule = RuleVerifier()

    override suspend fun verify(outputs: List<StepResult>): Outcome<Unit> {
        val llm = provider() ?: return rule.verify(outputs)
        val shown = outputs.takeLast(8).joinToString("\n") {
            "- ok=${it.ok} out=${it.output.take(200)} err=${it.error.take(200)}"
        }
        val prompt = "Goal: ${goal.take(300)}\nTool outputs:\n$shown\n" +
            "Did the goal REALLY happen (not just steps ran)? Reply ONLY {\"ok\":true/false,\"reason\":\"...\"}"
        val content = llm.chat(
            model().ifBlank { "default" },
            listOf(LlmMessage("user", prompt)),
            128,
        ).fold(onSuccess = { it.content }, onFailure = { return rule.verify(outputs) })
        val verdict = parse(content) ?: return rule.verify(outputs)
        return if (verdict.first) Outcome.Success(Unit)
        else Outcome.Failure(AppError("VERIFY_FAILED", verdict.second.ifBlank { "goal not achieved" }))
    }

    private fun parse(text: String): Pair<Boolean, String>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: return null
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
            ok to reason
        } catch (_: Exception) {
            null
        }
    }
}
