package com.aicodemax.tools.subtitle_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.subtitle.Cue
import com.aicodemax.tools.subtitle.InMemorySubtitlePort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleToolExecutorTest {
    private fun run(port: InMemorySubtitlePort, action: String, args: Map<String, String>): ToolResult =
        runBlocking {
            val call = ToolCall(id = "c1", toolId = "subtitle", action = action, args = args)
            (SubtitleToolExecutor(port).execute(call) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun makeParseShiftFlow() {
        val port = InMemorySubtitlePort()
        val made = run(port, "make", mapOf("transcript" to "สวัสดีครับทุกคน", "durationMs" to "5000"))
        assertTrue(made.ok)
        assertTrue(made.output.contains("cues"))
        val parsed = run(port, "parse", mapOf("path" to "out.srt"))
        assertTrue(parsed.ok)
        val shifted = run(port, "shift", mapOf("src" to "out.srt", "offsetMs" to "500"))
        assertTrue(shifted.ok)
    }

    @Test
    fun burnNeedsSrt() {
        val port = InMemorySubtitlePort()
        assertTrue(!run(port, "burn", mapOf("src" to "a.mp4", "srt" to "a.srt")).ok)
        port.put("a.srt", listOf(Cue(0, 1000, listOf("hi"))))
        val burned = run(port, "burn", mapOf("src" to "a.mp4", "srt" to "a.srt"))
        assertTrue(burned.ok)
    }

    @Test
    fun missingArgsAreHonest() {
        val port = InMemorySubtitlePort()
        assertTrue(!run(port, "make", emptyMap()).ok)
        assertTrue(!run(port, "shift", mapOf("src" to "a.srt")).ok)
        assertTrue(!run(port, "burn", mapOf("src" to "a.mp4")).ok)
        val r = run(port, "translate", mapOf("src" to "a.srt"))
        assertTrue(!r.ok)
        assertTrue(r.error.contains("unknown action"))
    }
}
