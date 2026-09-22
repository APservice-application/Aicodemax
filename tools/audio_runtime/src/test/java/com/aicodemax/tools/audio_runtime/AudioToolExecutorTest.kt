package com.aicodemax.tools.audio_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.audio.InMemoryAudioPort
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioToolExecutorTest {
    private fun port(): InMemoryAudioPort = InMemoryAudioPort().also {
        it.put("/tmp/a.wav", PcmAudio(8000, 1, FloatArray(8000) { 0.5f }))
        it.put("/tmp/b.wav", PcmAudio(8000, 1, FloatArray(4000) { 0.25f }))
    }

    private fun run(port: InMemoryAudioPort, action: String, args: Map<String, String>): ToolResult =
        runBlocking {
            val call = ToolCall(id = "c1", toolId = "audio", action = action, args = args)
            (AudioToolExecutor(port).execute(call) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun infoReportsDuration() {
        val r = run(port(), "info", mapOf("path" to "/tmp/a.wav"))
        assertTrue(r.ok)
        assertTrue(r.output.contains("1000ms"))
    }

    @Test
    fun trimConcatGainFade() {
        val p = port()
        assertTrue(run(p, "trim", mapOf("src" to "/tmp/a.wav", "startMs" to "0", "endMs" to "500")).ok)
        assertEquals(4000, p.get("/tmp/a-cut.wav")!!.frames)
        assertTrue(run(p, "concat", mapOf("srcs" to "/tmp/a.wav|/tmp/b.wav")).ok)
        assertEquals(12000, p.get("/tmp/a-joined.wav")!!.frames)
        assertTrue(run(p, "gain", mapOf("src" to "/tmp/b.wav", "db" to "6")).ok)
        assertEquals(0.25f * 1.9952624f, p.get("/tmp/b-vol.wav")!!.samples[0], 0.001f)
        assertTrue(run(p, "fade", mapOf("src" to "/tmp/a.wav", "inMs" to "100", "outMs" to "100")).ok)
        assertTrue(p.get("/tmp/a-fade.wav")!!.samples[0] < 0.5f)
    }

    @Test
    fun voicefxSynthBeatsFlow() {
        val p = port()
        assertTrue(run(p, "voicefx", mapOf("src" to "/tmp/a.wav", "semitones" to "7")).ok)
        assertTrue(p.get("/tmp/a-fx.wav") != null)
        assertTrue(run(p, "synthmusic", mapOf("style" to "calm", "seconds" to "2", "dst" to "/tmp/bed.wav")).ok)
        assertEquals(22050 * 2, p.get("/tmp/bed.wav")!!.samples.size)
        assertTrue(run(p, "synthsfx", mapOf("kind" to "click", "dst" to "/tmp/click.wav")).ok)
        assertTrue(p.get("/tmp/click.wav") != null)
        assertTrue(!run(p, "synthsfx", mapOf("kind" to "nope", "dst" to "/tmp/x.wav")).ok)
    }

    @Test
    fun speechFlow() {
        val p = port()
        val r = run(p, "speech", mapOf("src" to "/tmp/a.wav"))
        assertTrue(r.output, r.ok)
    }

    @Test
    fun missingArgsAreHonest() {
        val p = port()
        assertTrue(!run(p, "info", emptyMap()).ok)
        assertTrue(!run(p, "trim", mapOf("src" to "/tmp/a.wav")).ok)
        assertTrue(!run(p, "concat", emptyMap()).ok)
        assertTrue(!run(p, "gain", mapOf("src" to "/tmp/a.wav")).ok)
        val r = run(p, "autotune", mapOf("src" to "/tmp/a.wav"))
        assertTrue(!r.ok)
        assertTrue(r.error.contains("unknown action"))
    }
}
