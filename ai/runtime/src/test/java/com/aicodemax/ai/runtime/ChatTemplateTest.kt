package com.aicodemax.ai.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-147: Qwen3 template (ChatML + /no_think) and thinking-stripper. */
class ChatTemplateTest {

    private fun user(content: String) = listOf(ChatMessage(ChatRole.USER, content))

    @Test
    fun qwen3AddsNoThinkOnce() {
        val prompt = ChatTemplate.qwen3(user("สวัสดี"))
        assertTrue(prompt, prompt.contains("สวัสดี /no_think"))
        assertTrue(prompt, prompt.endsWith("<|im_start|>assistant\n"))
        // Exactly one occurrence — never doubled.
        assertTrue(prompt, prompt.split("/no_think").size == 2)
    }

    @Test
    fun qwen3RespectsExplicitThink() {
        val prompt = ChatTemplate.qwen3(user("อธิบาย /think"))
        assertTrue(prompt, !prompt.contains("/no_think"))
    }

    @Test
    fun qwen3FramingIsSingleChatML() {
        val prompt = ChatTemplate.qwen3(
            listOf(
                ChatMessage(ChatRole.SYSTEM, "S"),
                ChatMessage(ChatRole.USER, "U"),
                ChatMessage(ChatRole.ASSISTANT, "A"),
                ChatMessage(ChatRole.USER, "U2"),
            ),
        )
        // No double-wrap: 4 turns + 1 open assistant header.
        assertTrue(prompt, prompt.split("<|im_start|>").size == 6)
        assertTrue(prompt, prompt.contains("U2 /no_think"))
    }

    @Test
    fun stripThinkingRemovesClosedBlock() {
        val out = ChatTemplate.stripThinking("<think>reasoning here</think>สวัสดีครับ")
        assertTrue(out == "สวัสดีครับ")
    }

    @Test
    fun stripThinkingDropsUnterminatedBlock() {
        val out = ChatTemplate.stripThinking("สวัสดี<think>cut off")
        assertTrue(out == "สวัสดี")
    }

    @Test
    fun stripThinkingKeepsCleanText() {
        val out = ChatTemplate.stripThinking("สวัสดีครับ มีอะไรให้ช่วยไหม")
        assertTrue(out == "สวัสดีครับ มีอะไรให้ช่วยไหม")
        assertFalse(out.contains("<think>"))
    }
}
