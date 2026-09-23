package com.aicodemax.ai.agents

import com.aicodemax.ai.core.ChatBrain
import com.aicodemax.ai.core.LlmTurn
import com.aicodemax.ai.runtime.AiRuntimeManager
import com.aicodemax.ai.runtime.AiRuntimeState
import com.aicodemax.ai.runtime.ChatMessage
import com.aicodemax.ai.runtime.ChatRole
import com.aicodemax.ai.runtime.GenParams
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/**
 * CP-128: primary chat brain — on-device model via [AiRuntimeManager].
 * Offline-first: works without network once the model is installed.
 * Honest about every non-ready state (guides toward install/recover).
 */
class LocalChatBrain(
    private val ai: AiRuntimeManager,
    private val params: GenParams = GenParams(),
) : ChatBrain {
    override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> {
        when (ai.state.value) {
            AiRuntimeState.READY -> Unit
            AiRuntimeState.OFFLINE ->
                return Outcome.Failure(AppError("BRAIN_OFFLINE", offlineGuidance()))
            AiRuntimeState.UNINITIALIZED, AiRuntimeState.INITIALIZING, AiRuntimeState.LOADING_MODEL ->
                return Outcome.Failure(AppError("BRAIN_BUSY", "AI กำลังเตรียมตัว (${ai.state.value}) — รอสักครู่แล้วลองใหม่"))
            AiRuntimeState.GENERATING ->
                return Outcome.Failure(AppError("BRAIN_BUSY", "AI กำลังตอบอยู่ — รอสักครู่"))
            AiRuntimeState.ERROR ->
                return Outcome.Failure(AppError("BRAIN_ERROR", "AI ขัดข้อง: ${ai.error.value ?: "?"} — ลองใหม่หรือ recover"))
            AiRuntimeState.STOPPING, AiRuntimeState.UNLOADING, AiRuntimeState.RECOVERING ->
                return Outcome.Failure(AppError("BRAIN_BUSY", "AI กำลัง${ai.state.value} — รอสักครู่"))
        }
        val messages = history.mapNotNull { turn ->
            when (turn.role.lowercase()) {
                "user" -> ChatMessage(ChatRole.USER, turn.content)
                "assistant" -> ChatMessage(ChatRole.ASSISTANT, turn.content)
                "system" -> ChatMessage(ChatRole.SYSTEM, turn.content)
                else -> null
            }
        } + ChatMessage(ChatRole.USER, text)
        return ai.chat(messages, params).fold(
            onSuccess = { Outcome.Success(it.text) },
            onFailure = { Outcome.Failure(it) },
        )
    }

    private fun offlineGuidance(): String {
        val reason = ai.error.value.orEmpty()
        return if (reason.contains("ยังไม่ติดตั้ง")) {
            "ยังไม่มีโมเดลบนเครื่อง — ใช้ model.download เพื่อโหลด Qwen ~400MB (ครั้งเดียว) แล้ว AI จะพร้อมแบบออฟไลน์"
        } else {
            "AI ออฟไลน์: $reason"
        }
    }
}

/**
 * CP-128: hybrid chat (§27) — try [primary] (local), fall back to [secondary]
 * (cloud/routed) only when the primary fails.
 */
class FallbackChatBrain(
    private val primary: ChatBrain,
    private val secondary: ChatBrain?,
) : ChatBrain {
    override suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String> {
        when (val first = primary.reply(text, history)) {
            is Outcome.Success -> return first
            is Outcome.Failure -> {
                val backup = secondary ?: return first
                // Secondary success wins; if both fail, the primary (local)
                // error is more actionable (install/recover guidance).
                return when (val second = backup.reply(text, history)) {
                    is Outcome.Success -> second
                    is Outcome.Failure -> first
                }
            }
        }
    }
}
