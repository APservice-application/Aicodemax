package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-79: mask + chroma + background ops. */
class MaskChromaBgTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun maskSetAndIdentityClears() {
        val t = TimelineOps.mask(timeline(), "c1", ClipMask(shape = "ellipse", feather = 20))
        assertEquals("ellipse 0,0 100x100 f20", t.tracks[0].clips[0].mask!!.summary())
        val cleared = TimelineOps.mask(t, "c1", ClipMask())
        assertEquals(null, cleared.tracks[0].clips[0].mask)
        try {
            TimelineOps.mask(timeline(), "c1", ClipMask(shape = "star"))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("rect/ellipse"))
        }
    }

    @Test
    fun chromaSetAndClear() {
        val t = TimelineOps.chroma(timeline(), "c1", ClipChroma())
        assertEquals("h120 t30", t.tracks[0].clips[0].chroma!!.summary())
        val cleared = TimelineOps.chroma(t, "c1", null)
        assertEquals(null, cleared.tracks[0].clips[0].chroma)
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.chroma(audio, "a1", ClipChroma())
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
    }

    @Test
    fun backgroundSetAndAssetGuard() {
        val t = TimelineOps.background(timeline(), ClipBackground(mode = "blur", blur = 6))
        assertEquals("blur6", t.background!!.summary())
        val cleared = TimelineOps.background(t, null)
        assertEquals(null, cleared.background)
        try {
            TimelineOps.background(timeline(), ClipBackground(mode = "color", color = "red"))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("hex"))
        }
        try {
            TimelineOps.background(timeline(), ClipBackground(mode = "image", assetId = "ghost"))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ไม่มีในไทม์ไลน์"))
        }
        val withImg = TimelineOps.background(timeline(), ClipBackground(mode = "image", assetId = "a1"))
        assertEquals("image:a1", withImg.background!!.summary())
    }
}
