package com.aicodemax.tools.video_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.video.InMemoryVideoPort
import com.aicodemax.tools.video.VideoInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoToolExecutorTest {
    private fun port(): InMemoryVideoPort = InMemoryVideoPort().also {
        it.put("/tmp/a.mp4", VideoInfo("/tmp/a.mp4", "MP4", 30000, 1280, 720, hasAudio = true))
        it.put("/tmp/silent.mp4", VideoInfo("/tmp/silent.mp4", "MP4", 5000, 640, 480, hasAudio = false))
    }

    private fun run(port: InMemoryVideoPort, action: String, args: Map<String, String>): ToolResult =
        runBlocking {
            val call = ToolCall(id = "c1", toolId = "video", action = action, args = args)
            (VideoToolExecutor(port).execute(call) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun infoReportsFacts() {
        val r = run(port(), "info", mapOf("path" to "/tmp/a.mp4"))
        assertTrue(r.ok)
        assertTrue(r.output.contains("1280x720"))
    }

    @Test
    fun thumbTrimExtract() {
        val p = port()
        assertTrue(run(p, "thumbnail", mapOf("src" to "/tmp/a.mp4", "timeMs" to "2000")).ok)
        assertEquals(1280, p.get("/tmp/a-thumb.png")!!.width)
        assertTrue(!run(p, "thumbnail", mapOf("src" to "/tmp/a.mp4", "timeMs" to "999999")).ok)
        assertTrue(run(p, "trim", mapOf("src" to "/tmp/a.mp4", "startMs" to "0", "endMs" to "10000")).ok)
        assertEquals(10000L, p.get("/tmp/a-cut.mp4")!!.durationMs)
        assertTrue(run(p, "extractAudio", mapOf("src" to "/tmp/a.mp4")).ok)
        assertEquals("M4A", p.get("/tmp/a-audio.m4a")!!.format)
        assertTrue(!run(p, "extractAudio", mapOf("src" to "/tmp/silent.mp4")).ok)
    }

    @Test
    fun missingArgsAreHonest() {
        val p = port()
        assertTrue(!run(p, "info", emptyMap()).ok)
        assertTrue(!run(p, "trim", mapOf("src" to "/tmp/a.mp4")).ok)
        val r = run(p, "transcode", mapOf("src" to "/tmp/a.mp4"))
        assertTrue(!r.ok)
        assertTrue(r.error.contains("unknown action"))
    }
}
