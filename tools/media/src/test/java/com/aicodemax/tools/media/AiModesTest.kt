package com.aicodemax.tools.media

import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-96: 5 AI content modes. */
class AiModesTest {
    @Test
    fun allModesProducePlans() {
        for (mode in AiModes.MODES) {
            val plan = AiModes.plan(mode, "กาแฟดริป", "tiktok")
            assertTrue(mode, plan.contains("กาแฟดริป") && plan.contains("CTA"))
        }
    }

    @Test
    fun unknownModeFailsHonestly() {
        try {
            AiModes.plan("nope", "x")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("โหมดไม่รู้จัก"))
        }
    }
}
