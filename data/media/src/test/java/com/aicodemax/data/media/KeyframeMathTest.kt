package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-76: easing curves, sampling, keyframe ops. */
class KeyframeMathTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun easingEndpointsAndMid() {
        assertEquals(0f, Easing.apply(KeyPoint(0, 0f, "linear"), 0f))
        assertEquals(1f, Easing.apply(KeyPoint(0, 0f, "linear"), 1f))
        assertEquals(0.5f, Easing.apply(KeyPoint(0, 0f, "linear"), 0.5f))
        assertEquals(0.25f, Easing.apply(KeyPoint(0, 0f, "easein"), 0.5f))
        assertEquals(0.75f, Easing.apply(KeyPoint(0, 0f, "easeout"), 0.5f))
        assertEquals(0.5f, Easing.apply(KeyPoint(0, 0f, "easeinout"), 0.5f))
        for (ease in listOf("linear", "easein", "easeout", "easeinout", "bezier")) {
            val v = Easing.apply(KeyPoint(0, 0f, ease), 0.37f)
            assertTrue("$ease=$v", v in 0f..1f)
        }
        // Single point holds; out-of-range clamps to ends.
        val keys = ClipKeyframes(scale = listOf(KeyPoint(1000, 150f)))
        assertEquals(150f, keys.valueAt("scale", 0))
        assertEquals(150f, keys.valueAt("scale", 9999))
        assertEquals(null, keys.valueAt("opacity", 500))
    }

    @Test
    fun valueAtInterpolates() {
        val keys = ClipKeyframes(scale = listOf(KeyPoint(0, 100f), KeyPoint(2000, 200f)))
        assertEquals(150f, keys.valueAt("scale", 1000))
        assertEquals(100f, keys.valueAt("scale", 0))
        assertEquals(200f, keys.valueAt("scale", 2000))
        val eased = ClipKeyframes(scale = listOf(KeyPoint(0, 100f, "easein"), KeyPoint(2000, 200f)))
        assertEquals(125f, eased.valueAt("scale", 1000))
    }

    @Test
    fun setRemoveClearRoundtrip() {
        var t = TimelineOps.setKeyframe(timeline(), "c1", "scale", 1000, 150f)
        t = TimelineOps.setKeyframe(t, "c1", "opacity", 0, 50f, "easeinout")
        val keys = t.tracks[0].clips[0].keyframes!!
        assertEquals(2, keys.count())
        assertEquals("scale×1 opacity×1", keys.summary())
        t = TimelineOps.removeKeyframe(t, "c1", "scale", 1050)
        assertEquals(null, t.tracks[0].clips[0].keyframes!!.points("scale").firstOrNull())
        t = TimelineOps.clearKeyframes(t, "c1")
        assertEquals(null, t.tracks[0].clips[0].keyframes)
    }

    @Test
    fun setKeyframeRejectsBadInput() {
        try {
            TimelineOps.setKeyframe(timeline(), "c1", "nope", 0, 1f)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("property"))
        }
        try {
            TimelineOps.setKeyframe(timeline(), "c1", "scale", 0, 999f)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1.0..400.0"))
        }
        try {
            TimelineOps.setKeyframe(timeline(), "c1", "scale", 9999, 150f)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เกินความยาว"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.setKeyframe(audio, "a1", "scale", 0, 150f)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
        val vok = TimelineOps.setKeyframe(audio, "a1", "volume", 0, 50f)
        assertEquals(50f, vok.tracks[0].clips[0].keyframes!!.valueAt("volume", 2000))
    }

    @Test
    fun splitShiftsRightKeys() {
        var t = TimelineOps.setKeyframe(timeline(), "c1", "scale", 500, 120f)
        t = TimelineOps.setKeyframe(t, "c1", "scale", 3000, 180f)
        // Clip atMs=1000, len 4000 → split at timeline 3000 (offset 2000).
        val split = TimelineOps.split(t, "c1", 3000, "c2")
        val clips = split.tracks[0].clips.sortedBy { it.atMs }
        assertEquals(listOf(500L), clips[0].keyframes!!.points("scale").map { it.atMs })
        assertEquals(listOf(1000L), clips[1].keyframes!!.points("scale").map { it.atMs })
    }
}
