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
) : ChatBrain {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> {
        val active = provider()
            ?: return Outcome.Failure(AppError("BRAIN_OFF", "ยังไม่ต่อ LLM (ตั้งค่าที่หน้า Models)"))
        val activeModel = model().ifBlank { "default" }
        val messages = mutableListOf(LlmMessage("system", systemPrompt))
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
                val outcome = gateway.call(
                    ToolCall(Ids.newId("llm"), action.toolId, action.action, action.args, actor = "AI"),
                )
                val line = outcome.fold(
                    onSuccess = {
                        if (it.ok) "TOOL_RESULT ${action.toolId}.${action.action} OK ${it.output.take(1500)}"
                        else "TOOL_RESULT ${action.toolId}.${action.action} FAIL ${it.error.take(500)}"
                    },
                    onFailure = { "TOOL_RESULT ${action.toolId}.${action.action} ERROR ${it.message.take(500)}" },
                )
                messages.add(LlmMessage("user", line))
            }
        }
        return Outcome.Success(stripActionLines(lastContent).trim() + "\n(ถึงขีดจำกัด $maxSteps ขั้นตอน)")
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
