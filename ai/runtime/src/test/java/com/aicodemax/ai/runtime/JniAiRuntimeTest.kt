package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JniAiRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Fake native edge (records calls, streams scripted pieces). */
    private class FakeEdge(
        override val available: Boolean = true,
        var loadResult: Long = 7L,
        var genText: String = "สวัสดี",
        var genError: String = "",
    ) : JniAiRuntime.JniEdge {
        var lastPrompt: String = ""
        var stopped: Long = 0L
        var unloaded: Long = 0L

        override fun load(path: String, ctxSize: Int, threads: Int): Long = loadResult
        override fun generate(
            handle: Long, prompt: String, maxTokens: Int, temperature: Float,
            topP: Float, topK: Int, stops: Array<String>, sink: AicodeJni.TokenCallback?,
        ): String {
            lastPrompt = prompt
            // Stream in 2 pieces like the native bridge.
            val mid = genText.length / 2
            sink?.onToken(genText.substring(0, mid))
            sink?.onToken(genText.substring(mid))
            return genText
        }
        override fun stop(handle: Long) {
            stopped = handle
        }
        override fun unload(handle: Long) {
            unloaded = handle
        }
        override fun info(handle: Long): String = "ctx=2048"
        override fun lastError(): String = genError
    }

    @Test
    fun unavailableEdgeFailsHonestly() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val rt = JniAiRuntime(FakeEdge(available = false))
        val outcome = rt.loadModel(model.path)
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("Android"))
    }

    @Test
    fun loadGenerateUnloadCycle() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val edge = FakeEdge()
        val rt = JniAiRuntime(edge)
        val loaded = rt.loadModel(model.path) as Outcome.Success
        assertEquals("q.gguf", loaded.value.name)
        assertTrue(rt.isModelLoaded())

        val pieces = mutableListOf<String>()
        val chatml = "<|im_start|>system\nS\n<|im_end|>\n<|im_start|>user\nhi\n<|im_end|>\n<|im_start|>assistant\n"
        val gen = rt.generate(chatml, onToken = TokenSink { pieces.add(it) }) as Outcome.Success
        assertEquals("สวัสดี", gen.value.text)
        assertEquals("สวัสดี", pieces.joinToString(""))
        // CP-144: prompt passes through UNCHANGED (callers own the template).
        assertEquals(chatml, edge.lastPrompt)

        rt.unloadModel()
        assertTrue(!rt.isModelLoaded())
        assertEquals(7L, edge.unloaded)
    }

    @Test
    fun nativeErrorSurfacesHonestly() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val rt = JniAiRuntime(FakeEdge(genText = "", genError = "decode failed at step 3"))
        rt.loadModel(model.path)
        val outcome = rt.generate("hi")
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("decode failed"))
    }

    @Test
    fun blankResponseFailsHonestly() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val rt = JniAiRuntime(FakeEdge(genText = "  ", genError = ""))
        rt.loadModel(model.path)
        val outcome = rt.generate("hi")
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("ตอบว่าง"))
    }

    @Test
    fun loadZeroHandleFailsHonestly() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val rt = JniAiRuntime(FakeEdge(loadResult = 0L, genError = "bad file"))
        val outcome = rt.loadModel(model.path)
        assertTrue(outcome is Outcome.Failure)
        assertTrue(!rt.isModelLoaded())
    }

    @Test
    fun stopForwardsHandle() {
        val model = tmp.newFile("q.gguf").apply { writeBytes(ByteArray(8)) }
        val edge = FakeEdge()
        val rt = JniAiRuntime(edge)
        rt.loadModel(model.path)
        rt.stopGeneration()
        assertEquals(7L, edge.stopped)
    }
}
