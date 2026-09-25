package com.aicodemax.ai.agents

import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.runtime.ChatMessage
import com.aicodemax.ai.runtime.ChatRole
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-148: embedded brain speaks LlmProvider (role mapping + readiness gate). */
class BuiltinLlmProviderTest {
    @Test
    fun mapsRolesAndForwardsText() = runBlocking {
        var seen: List<ChatMessage> = emptyList()
        val provider = BuiltinLlmProvider(
            generate = { messages, _ -> seen = messages; Outcome.Success("สวัสดี") },
            ready = { true },
        )
        val reply = provider.chat(
            "m",
            listOf(
                LlmMessage("system", "sys"),
                LlmMessage("user", "hi"),
                LlmMessage("assistant", "yo"),
                LlmMessage("tool", "out"),
            ),
        ) as Outcome.Success
        assertEquals("สวัสดี", reply.value.content)
        assertEquals(
            listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL),
            seen.map { it.role },
        )
    }

    @Test
    fun refusesWhenNotReady() = runBlocking {
        val provider = BuiltinLlmProvider(
            generate = { _, _ -> Outcome.Success("never") },
            ready = { false },
        )
        assertTrue(provider.chat("m", listOf(LlmMessage("user", "hi"))) is Outcome.Failure)
        assertTrue(provider.health() is Outcome.Failure)
    }

    @Test
    fun healthOkWhenReady() = runBlocking {
        val provider = BuiltinLlmProvider(
            generate = { _, _ -> Outcome.Success("x") },
            ready = { true },
        )
        assertTrue((provider.health() as Outcome.Success<String>).value.contains("builtin"))
    }
}
