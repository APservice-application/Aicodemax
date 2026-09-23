package com.aicodemax.tools.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfBenchTest {
    @Test
    fun medianMath() {
        assertEquals(2.0, PerfBench.median(listOf(1.0, 2.0, 3.0)), 0.0)
        assertEquals(2.5, PerfBench.median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
        assertEquals(0.0, PerfBench.median(emptyList()), 0.0)
    }

    @Test
    fun runRecordsIters() {
        var ran = 0
        val r = PerfBench.run("t", 100, warmup = 1, repeat = 2) { ran++ }
        assertEquals(100, r.iters)
        assertEquals(3, ran)
        assertTrue(r.medianMs >= 0.0)
        assertTrue(r.line().contains("t: 100 รอบ"))
    }

    @Test
    fun quickSuiteHasThreeKernels() {
        val results = PerfBench.suite(quick = true)
        assertEquals(3, results.size)
        val text = PerfBench.format(results)
        assertTrue(text.contains("ฮิสโตแกรม") && text.contains("เรียงลำดับ") && text.contains("ฟอร์แมตซับ"))
    }
}
