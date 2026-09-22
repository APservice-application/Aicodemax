package com.aicodemax.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-88: pan-scan math. */
class ReframeTest {
    @Test
    fun portraitCropFromLandscape() {
        val t = Reframe.plan(1920, 1080, "9:16")
        // 1080*9/16 = 607px wide → ~31%.
        assertTrue("w=${t.cropW}", t.cropW in 28..34)
        assertTrue("h=${t.cropH}", t.cropH in 97..100)
        assertTrue("x=${t.cropX}", t.cropX in 30..36)
    }

    @Test
    fun subjectLeftClamps() {
        val t = Reframe.plan(1920, 1080, "9:16", subjectX = 0.0)
        assertEquals(0, t.cropX)
    }

    @Test
    fun punchZoomsIn() {
        val a = Reframe.plan(1920, 1080, "1:1")
        val b = Reframe.plan(1920, 1080, "1:1", punch = 2.0)
        assertTrue(b.cropW < a.cropW)
        try {
            Reframe.plan(1920, 1080, "21:9")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("สัดส่วน"))
        }
    }
}
