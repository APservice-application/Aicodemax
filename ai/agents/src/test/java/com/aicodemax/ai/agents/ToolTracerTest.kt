package com.aicodemax.ai.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTracerTest {
    @Test
    fun fullChainIsRecordedInOrder() {
        val tracer = ToolTracer()
        tracer.candidates("export วิดีโอ", listOf("media.timeline.export"))
        tracer.decision("media.timeline.export")
        tracer.validation("media.timeline.export", "valid")
        tracer.result("media.timeline.export", true, "wrote file")
        val steps = tracer.snapshot()
        assertEquals(4, steps.size)
        assertTrue(steps[0].startsWith("retrieve"))
        assertTrue(steps[1].startsWith("decide"))
        assertTrue(steps[2].startsWith("validate"))
        assertTrue(steps[3].startsWith("result"))
    }

    @Test
    fun sinkReceivesEveryStep() {
        val seen = mutableListOf<String>()
        val tracer = ToolTracer(traceSink = { seen.add(it) })
        tracer.decision("files.read")
        assertEquals(listOf("decide files.read"), seen)
    }

    @Test
    fun capEvictsOldest() {
        val tracer = ToolTracer(maxSteps = 2)
        tracer.decision("a.x")
        tracer.decision("b.y")
        tracer.decision("c.z")
        assertEquals(listOf("decide b.y", "decide c.z"), tracer.snapshot())
        tracer.clear()
        assertTrue(tracer.snapshot().isEmpty())
    }
}
