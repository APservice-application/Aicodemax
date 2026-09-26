package com.aicodemax.ai.agents

import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.ai.models.LlmReply
import com.aicodemax.ai.runtime.AiRuntimeManager
import com.aicodemax.ai.runtime.AiRuntimeState
import com.aicodemax.ai.runtime.ChatMessage
import com.aicodemax.ai.runtime.ChatRole
import com.aicodemax.ai.runtime.GenParams
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/**
 * CP-148: the EMBEDDED Qwen3 speaks the [LlmProvider] protocol.
 *
 * This is what lets the planner / re-planner / tool brain run on-device with
 * zero API keys and zero network (standing requirement: core AI never depends
 * on the Models page). Functional seams keep it unit-testable; production
 * uses [fromManager].
 */
class BuiltinLlmProvider(
    private val generate: suspend (messages: List<ChatMessage>, maxTokens: Int) -> Outcome<String>,
    private val ready: () -> Boolean,
    private val modelName: String = "qwen3-1.7b-builtin",
) : LlmProvider {
    override val id: String = "builtin"

    override suspend fun chat(
        model: String,
        messages: List<LlmMessage>,
        maxTokens: Int,
    ): Outcome<LlmReply> {
        if (!ready()) {
            return Outcome.Failure(AppError("BUILTIN_NOT_READY", "AI ในตัวยังไม่พร้อม"))
        }
        val mapped = messages.map {
            val extra = if (it.imageBase64 != null) "\n[image omitted — builtin model is text-only]" else ""
            ChatMessage(mapRole(it.role), it.content + extra)
        }
        return generate(mapped, maxTokens).fold(
            onSuccess = { Outcome.Success(LlmReply(it)) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    override suspend fun health(): Outcome<String> =
        if (ready()) Outcome.Success("$modelName ready")
        else Outcome.Failure(AppError("BUILTIN_NOT_READY", "AI ในตัวยังไม่พร้อม"))

    companion object {
        fun mapRole(role: String): ChatRole = when (role.lowercase()) {
            "system" -> ChatRole.SYSTEM
            "assistant" -> ChatRole.ASSISTANT
            "tool" -> ChatRole.TOOL
            else -> ChatRole.USER
        }

        /** Production wiring: generations go through the real runtime (thinking already stripped). */
        fun fromManager(manager: AiRuntimeManager, modelName: String = "qwen3-1.7b-builtin"): BuiltinLlmProvider =
            BuiltinLlmProvider(
                generate = { messages, maxTokens ->
                    manager.chat(messages, GenParams(maxTokens = maxTokens)).fold(
                        onSuccess = { Outcome.Success(it.text) },
                        onFailure = { Outcome.Failure(it) },
                    )
                },
                ready = { manager.state.value == AiRuntimeState.READY },
                modelName = modelName,
            )
    }
}
