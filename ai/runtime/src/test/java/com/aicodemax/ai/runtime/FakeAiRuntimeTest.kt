package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeAiRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun loadRequiresFile() {
        val rt = FakeAiRuntime()
        val outcome = rt.loadModel(tmp.root.path + "/no.gguf")
        assertTrue(outcome is Outcome.Failure)
        assertTrue(!rt.isModelLoaded())
    }

    @Test
    fun loadGenerateUnloadCycle() {
        val model = tmp.newFile("tiny.gguf").apply { writeBytes(ByteArray(16)) }
        val rt = FakeAiRuntime(script = { "ตอบ: $it" }, streamPieces = 2)
        val loaded = rt.loadModel(model.path) as Outcome.Success
        assertEquals("tiny.gguf", loaded.value.name)
        assertEquals(16L, loaded.value.bytes)
        assertTrue(rt.isModelLoaded())

        val pieces = mutableListOf<String>()
        val gen = rt.generate("สวัสดี", onToken = TokenSink { pieces.add(it) }) as Outcome.Success
        assertEquals("ตอบ: สวัสดี", gen.value.text)
        assertEquals(gen.value.text, pieces.joinToString(""))
        assertTrue(pieces.size >= 2)
        assertTrue(!gen.value.stoppedEarly)

        rt.unloadModel()
        assertTrue(!rt.isModelLoaded())
        assertEquals(null, rt.getModelInfo())
    }

    @Test
    fun generateWithoutModelIsHonest() {
        val rt = FakeAiRuntime()
        val outcome = rt.generate("hi")
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("โหลดโมเดล"))
    }

    @Test
    fun stopDuringGenerateMarksStoppedEarly() {
        val model = tmp.newFile("tiny.gguf").apply { writeBytes(ByteArray(16)) }
        val rt = FakeAiRuntime(script = { "x".repeat(100) }, streamPieces = 100)
        rt.loadModel(model.path)
        val gen = rt.generate("go", onToken = TokenSink { rt.stopGeneration() }) as Outcome.Success
        assertTrue(gen.value.stoppedEarly)
        assertTrue(gen.value.text.length < 100)
    }

    @Test
    fun runtimeInfoReflectsState() {
        val model = tmp.newFile("tiny.gguf").apply { writeBytes(ByteArray(16)) }
        val rt = FakeAiRuntime()
        assertTrue(!rt.getRuntimeInfo().modelLoaded)
        rt.loadModel(model.path)
        val info = rt.getRuntimeInfo()
        assertTrue(info.modelLoaded)
        assertEquals("fake/1.0", info.engineId)
    }

    @Test
    fun setParamsRoundTrips() {
        val rt = FakeAiRuntime()
        rt.setParams(GenParams(temperature = 0.1f, maxTokens = 7))
        assertEquals(0.1f, rt.currentParams().temperature)
        assertEquals(7, rt.currentParams().maxTokens)
    }
}
