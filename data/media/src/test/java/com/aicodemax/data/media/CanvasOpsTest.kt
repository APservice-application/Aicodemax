package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-88: canvas op. */
class CanvasOpsTest {
    @Test
    fun setAndClearCanvas() {
        val t = TimelineOps.setCanvas(Timeline(), "9:16")
        assertEquals("9:16", t.canvas)
        assertEquals("", TimelineOps.setCanvas(t, "").canvas)
        try {
            TimelineOps.setCanvas(Timeline(), "21:9")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("สัดส่วน"))
        }
    }
}
