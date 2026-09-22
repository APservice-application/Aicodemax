package com.aicodemax.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-90: match math. */
class ColorMatchTest {
    @Test
    fun identicalStatsYieldNeutral() {
        val s = ChannelStats(128.0, 128.0, 128.0, 10, 128, 245)
        assertEquals(com.aicodemax.data.media.ClipColor(), ColorMatch.match(s, s))
    }

    @Test
    fun blueTargetWarmsTowardNeutralRef() {
        val target = ChannelStats(100.0, 128.0, 170.0, 10, 128, 245)
        val ref = ChannelStats(128.0, 128.0, 128.0, 10, 128, 245)
        val grade = ColorMatch.match(target, ref)
        assertTrue("temp=${grade.temperature}", grade.temperature > 0)
    }

    @Test
    fun darkTargetOpensExposure() {
        val target = ChannelStats(60.0, 60.0, 60.0, 2, 60, 150)
        val ref = ChannelStats(128.0, 128.0, 128.0, 10, 128, 245)
        val grade = ColorMatch.match(target, ref)
        assertTrue("exp=${grade.exposure}", grade.exposure > 0)
    }
}
