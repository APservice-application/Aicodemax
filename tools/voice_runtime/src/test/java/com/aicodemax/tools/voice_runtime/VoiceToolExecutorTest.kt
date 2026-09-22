package com.aicodemax.tools.voice_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.voice.InMemoryVoicePort
import com.aicodemax.tools.voice.VoiceInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceToolExecutorTest {
    private fun call(action: String, args: Map<String, String> = emptyMap()) =
        ToolCall(id = "c1", toolId = "voice", action = action, args = args)

    private fun run(exec: VoiceToolExecutor, action: String, args: Map<String, String> = emptyMap()): ToolResult =
        runBlocking { (exec.execute(call(action, args)) as Outcome.Success<ToolResult>).value }

    @Test
    fun listenReturnsTranscript() {
        val port = InMemoryVoicePort()
        port.feed(VoiceInput("เปิดไฟ", "th-TH"))
        val result = run(VoiceToolExecutor(port), "listen")
        assertTrue(result.ok)
        assertTrue(result.output.contains("เปิดไฟ"))
    }

    @Test
    fun listenTimeoutIsHonest() {
        val result = run(VoiceToolExecutor(InMemoryVoicePort()), "listen")
        assertTrue(!result.ok)
    }

    @Test
    fun speakRequiresText() {
        val exec = VoiceToolExecutor(InMemoryVoicePort())
        assertTrue(!run(exec, "speak").ok)
        val ok = run(exec, "speak", mapOf("text" to "สวัสดีครับ"))
        assertTrue(ok.ok)
        assertTrue(ok.output.contains("พูดแล้ว"))
    }

    @Test
    fun statusAndStop() {
        val exec = VoiceToolExecutor(InMemoryVoicePort())
        assertTrue(run(exec, "status").output.contains("STT"))
        assertTrue(run(exec, "stop").ok)
    }

    @Test
    fun unknownActionIsHonest() {
        val result = run(VoiceToolExecutor(InMemoryVoicePort()), "sing")
        assertTrue(!result.ok)
        assertTrue(result.error.contains("unknown action"))
        assertEquals("voice", VoiceToolExecutor().toolId)
    }
}
