package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome

/** CP-59: pluggable LLM brain for open chat (rule-based intents keep priority). */
data class LlmTurn(val role: String, val content: String)

fun interface ChatBrain {
    suspend fun reply(text: String, history: List<LlmTurn>): Outcome<String>
}
