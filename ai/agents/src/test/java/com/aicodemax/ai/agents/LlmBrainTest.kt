package com.aicodemax.ai.agents

import com.aicodemax.ai.core.LlmTurn
import com.aicodemax.ai.models.LlmMessage
import com.aicodemax.ai.models.LlmProvider
import com.aicodemax.ai.models.LlmReply
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolGateway
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmBrainTest {
    private class ScriptProvider(private val replies: List<String>) : LlmProvider {
        override val id: String = "script"
        var calls = 0

        override suspend fun chat(model: String, messages: List<LlmMessage>, maxTokens: Int): Outcome<LlmReply> {
            val reply = replies.getOrElse(calls) { replies.last() }
            calls += 1
            return Outcome.Success(LlmReply(reply))
        }

        override suspend fun health(): Outcome<String> = Outcome.Success("ok")
    }

    private class FakeGateway(val results: Map<String, ToolResult> = emptyMap()) : ToolGateway {
        val seen = mutableListOf<ToolCall>()
        override fun registerExecutor(executor: ToolExecutor) = Unit
        override suspend fun call(call: ToolCall): Outcome<ToolResult> {
            seen.add(call)
            return Outcome.Success(results["${call.toolId}.${call.action}"] ?: ToolResult(ok = true, output = "ok"))
        }
    }

    @Test
    fun directAnswerHasNoToolCalls() = runBlocking {
        val gateway = FakeGateway()
        val brain = LlmBrain({ ScriptProvider(listOf("สวัสดีครับ")) }, { "m" }, gateway, "sys")
        val reply = (brain.reply("hi", emptyList()) as Outcome.Success<String>).value
        assertEquals("สวัสดีครับ", reply)
        assertTrue(gateway.seen.isEmpty())
    }

    @Test
    fun actionLoopExecutesThenAnswers() = runBlocking {
        val gateway = FakeGateway(mapOf("files.read" to ToolResult(ok = true, output = "hello-file")))
        val brain = LlmBrain(
            {
                ScriptProvider(
                    listOf(
                        "ขอดูก่อนครับ\nACTION files.read {\"path\":\"notes.txt\"}",
                        "ในไฟล์มี: hello-file ครับ",
                    ),
                )
            },
            { "m" },
            gateway,
            "sys",
        )
        val reply = (brain.reply("อ่านไฟล์ให้หน่อย", emptyList()) as Outcome.Success<String>).value
        assertEquals(1, gateway.seen.size)
        assertEquals("files", gateway.seen.single().toolId)
        assertEquals("notes.txt", gateway.seen.single().args["path"])
        assertEquals("AI", gateway.seen.single().actor)
        assertTrue(reply.contains("hello-file"))
        assertTrue(!reply.contains("ACTION"))
    }

    @Test
    fun invalidActionNeverReachesGateway() = runBlocking {
        // CP-131: pre-validation + self-correction feedback, no gateway call.
        val gateway = FakeGateway()
        val bindings = com.aicodemax.tools.capability.StandardCapabilities.bindings()
        val brain = LlmBrain(
            { ScriptProvider(listOf("ACTION files.frobnicate {}", "ขอโทษครับ ไม่มีคำสั่งนั้น")) },
            { "m" }, gateway, "sys", bindings = bindings,
        )
        val reply = (brain.reply("ทำอะไรแปลกๆ", emptyList()) as Outcome.Success<String>).value
        assertTrue(gateway.seen.isEmpty())
        assertTrue(reply.contains("ขอโทษครับ"))
    }

    @Test
    fun successIsRecordedAsFewShot() = runBlocking {
        val gateway = FakeGateway(mapOf("files.read" to ToolResult(ok = true, output = "data")))
        val bindings = com.aicodemax.tools.capability.StandardCapabilities.bindings()
        val builder = ToolPromptBuilder(bindings)
        val brain = LlmBrain(
            { ScriptProvider(listOf("ACTION files.read {\"path\":\"a.txt\"}", "เสร็จครับ")) },
            { "m" }, gateway, "sys", bindings = bindings, promptBuilder = builder,
        )
        brain.reply("อ่านไฟล์ a", emptyList())
        val examples = builder.fewShot.relevant("อ่านไฟล์ a")
        assertEquals(1, examples.size)
        assertEquals("files.read", examples.single().capabilityId)
    }

    @Test
    fun toolsSectionIsInjectedPerTurn() {
        val builder = ToolPromptBuilder(com.aicodemax.tools.capability.StandardCapabilities.bindings())
        val section = builder.section("export วิดีโอให้หน่อย")
        assertTrue(section.contains("## Candidate tools"))
        assertTrue(section.contains("media."))
        val empty = builder.section("และ ใน ให้")
        assertTrue(empty.contains("debug.tools"))
    }

    @Test
    fun malformedActionIsTreatedAsText() {
        assertTrue(LlmBrain.parseActions("ACTION nope").isEmpty())
        assertTrue(LlmBrain.parseActions("ACTION files.read {oops").isEmpty())
        val parsed = LlmBrain.parseActions("ACTION files.read {\"path\":\"a\"}")
        assertEquals(1, parsed.size)
        assertEquals("a", parsed.single().args["path"])
    }

    @Test
    fun missingProviderIsHonest() = runBlocking {
        val brain = LlmBrain({ null }, { "m" }, FakeGateway(), "sys")
        val result = brain.reply("hi", listOf(LlmTurn("user", "old")))
        assertTrue(result is Outcome.Failure)
        assertEquals("BRAIN_OFF", (result as Outcome.Failure).error.code)
    }
}
