package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome

/**
 * CP-124 (spec Phase 6+9): provider abstraction. The agent talks to
 * [ModelProvider], never to llama.cpp/JNI/HTTP directly — providers can be
 * added or swapped without touching UI or agent code.
 */
interface ModelProvider {
    val providerId: String
    val displayName: String

    fun status(): ProviderStatus

    /**
     * Chat completion. Calls [onToken] per streamed piece (at least once on
     * success). Implementations must be safe to call from Dispatchers.IO.
     */
    fun chat(req: ChatRequest, onToken: TokenSink = TokenSink {}): Outcome<ChatReply>

    /** Release provider resources (model stays loaded unless provider owns it). */
    fun close()
}

sealed interface ProviderStatus {
    data object Available : ProviderStatus
    data class Unavailable(val reason: String) : ProviderStatus
    data class NeedsSetup(val reason: String) : ProviderStatus
}

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(val role: ChatRole, val content: String)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val params: GenParams = GenParams(),
)

data class ChatReply(
    val text: String,
    val stoppedEarly: Boolean = false,
    /** providerId that actually answered (useful for hybrid routing). */
    val via: String = "",
)

/** ChatML framing per model family (pure functions, unit-tested). */
object ChatTemplate {
    /**
     * CP-147: Qwen3 ChatML — same framing as Qwen2.5 plus `/no_think` on the
     * last user turn. Qwen3 thinks by default (slow on-device, raw `<think>`
     * in output); `/no_think` yields direct answers. A caller-provided
     * `/think` is respected (never overridden).
     */
    fun qwen3(messages: List<ChatMessage>): String {
        val adjusted = messages.toMutableList()
        val lastUser = adjusted.indexOfLast { it.role == ChatRole.USER }
        if (lastUser >= 0) {
            val content = adjusted[lastUser].content
            if (!content.contains("/no_think") && !content.contains("/think")) {
                adjusted[lastUser] = adjusted[lastUser].copy(content = content.trimEnd() + " /no_think")
            }
        }
        return qwen25(adjusted)
    }

    /**
     * CP-147: defensive — drop any `<think>...</think>` block Qwen3 may still
     * emit (e.g. explicit `/think` requests) so chat never shows raw thinking.
     */
    fun stripThinking(text: String): String {
        var out = text.replace(Regex("(?s)<think>.*?</think>"), "")
        val open = out.indexOf("<think>")
        if (open >= 0) out = out.substring(0, open)
        return out.trim()
    }

    /** Qwen2.5 ChatML (kept for the optional 1.5B model). */
    fun qwen25(messages: List<ChatMessage>): String = buildString {
        var hasSystem = false
        for (msg in messages) {
            when (msg.role) {
                ChatRole.SYSTEM -> {
                    append("<|im_start|>system\n")
                    append(msg.content.ifBlank { "You are a helpful assistant." })
                    append("\n<|im_end|>\n")
                    hasSystem = true
                }
                ChatRole.USER -> append("<|im_start|>user\n${msg.content}\n<|im_end|>\n")
                ChatRole.ASSISTANT -> append("<|im_start|>assistant\n${msg.content}\n<|im_end|>\n")
                ChatRole.TOOL -> append("<|im_start|>user\n[tool]\n${msg.content}\n<|im_end|>\n")
            }
        }
        if (!hasSystem) {
            insert(0, "<|im_start|>system\nYou are a helpful assistant.\n<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    fun singleTurn(system: String, user: String): String =
        qwen25(listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, user)))
}
