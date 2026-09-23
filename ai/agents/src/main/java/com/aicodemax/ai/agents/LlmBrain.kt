package com.aicodemax.ai.agents

import com.aicodemax.ai.core.ChatBrain
import com.aicodemax.ai.core.LlmTurn
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CP-59: LLM brain that USES our tools (automation loop).
 * The model calls tools by emitting lines: `ACTION toolId.action {"arg":"value"}`
 * Calls run through the gateway — permission gates + audit still apply.
 */
class LlmBrain(
    private val provider: () -> LlmProvider?,
    private val model: () -> String,
    private val gateway: ToolGateway,
    private val systemPrompt: String,
    private val maxSteps: Int = 8,
    /** CP-131: per-turn candidate-tools section (null = static prompt only). */
    private val toolsSection: ((String) -> String)? = null,
    private val bindings: List<com.aicodemax.tools.capability.CapabilityBinding>? = null,
    private val promptBuilder: ToolPromptBuilder? = null,
    private val preconditionsMet: (String) -> Boolean = { true },
) : ChatBrain {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> {
        val active = provider()
            ?: return Outcome.Failure(AppError("BRAIN_OFF", "ยังไม่ต่อ LLM (ตั้งค่าที่หน้า Models)"))
        val activeModel = model().ifBlank { "default" }
        val system = toolsSection?.let { systemPrompt + "\n\n" + it(text) } ?: systemPrompt
        val messages = mutableListOf(LlmMessage("system", system))
        // CP-131: self-correction budget per tool call (spec Phase 19).
        val failCounts = mutableMapOf<String, Int>()
        history.takeLast(10).forEach { messages.add(LlmMessage(it.role, it.content)) }
        messages.add(LlmMessage("user", text))

        var lastContent = ""
        repeat(maxSteps) {
            val content = when (val result = active.chat(activeModel, messages.toList())) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value.content
            }
            lastContent = content
            val actions = parseActions(content)
            if (actions.isEmpty()) {
                return Outcome.Success(stripActionLines(content).trim().ifBlank { "(ว่างเปล่า)" })
            }
            messages.add(LlmMessage("assistant", content))
            actions.forEach { action ->
                val line = executeWithCorrection(text, action, failCounts)
                messages.add(LlmMessage("user", line))
            }
        }
        return Outcome.Success(stripActionLines(lastContent).trim() + "\n(ถึงขีดจำกัด $maxSteps ขั้นตอน)")
    }

    /** Validate → call → record. Invalid calls never reach the gateway. */
    private suspend fun executeWithCorrection(
        userText: String,
        action: ParsedAction,
        failCounts: MutableMap<String, Int>,
    ): String {
        val id = "${action.toolId}.${action.action}"
        val known = bindings
        if (known != null) {
            val verdict = com.aicodemax.tools.capability.ToolCallValidator.validate(
                action.toolId, action.action, action.args, known, preconditionsMet,
            )
            if (verdict is com.aicodemax.tools.capability.ToolCallValidator.Result.Invalid) {
                val attempt = (failCounts[id] ?: 0) + 1
                failCounts[id] = attempt
                val feedback = com.aicodemax.tools.capability.ValidationRetry.feedback(verdict, attempt)
                val extra = if (com.aicodemax.tools.capability.ValidationRetry.shouldRetry(attempt)) ""
                else " — เลิกใช้ $id แล้วเลือก tool อื่น"
                return "TOOL_RESULT $id FAIL $feedback$extra"
            }
        }
        val outcome = gateway.call(
            ToolCall(Ids.newId("llm"), action.toolId, action.action, action.args, actor = "AI"),
        )
        return outcome.fold(
            onSuccess = {
                if (it.ok) {
                    failCounts.remove(id)
                    promptBuilder?.recordSuccess(userText, id, action.args)
                    "TOOL_RESULT $id OK ${it.output.take(1500)}"
                } else {
                    "TOOL_RESULT $id FAIL ${it.error.take(500)}"
                }
            },
            onFailure = { "TOOL_RESULT $id ERROR ${it.message.take(500)}" },
        )
    }

    companion object {
        private val ACTION_LINE = Regex("""^ACTION\s+([A-Za-z0-9_.-]+)\s+(\{.*\})\s*$""")

        data class ParsedAction(val toolId: String, val action: String, val args: Map<String, String>)

        fun parseActions(content: String): List<ParsedAction> =
            content.lines().mapNotNull { line ->
                val match = ACTION_LINE.find(line.trim()) ?: return@mapNotNull null
                val id = match.groupValues[1]
                val toolId = id.substringBefore(".")
                val action = id.substringAfter(".", "")
                if (toolId.isBlank() || action.isBlank()) return@mapNotNull null
                val args = try {
                    Json.parseToJsonElement(match.groupValues[2]).jsonObject.entries.associate { (key, value) ->
                        key to (value.jsonPrimitive.contentOrNull ?: value.toString())
                    }
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                ParsedAction(toolId, action, args)
            }

        fun stripActionLines(content: String): String =
            content.lines().filterNot { ACTION_LINE.matches(it.trim()) }.joinToString("\n")
    }
}
