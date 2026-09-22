package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimelineOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun splitDividesSourceRange() {
        val next = TimelineOps.split(timeline(), "c1", 3000, "c2")
        val clips = next.tracks[0].clips.sortedBy { it.atMs }
        assertEquals(2, clips.size)
        assertEquals(Clip("c1", "a1", 0, 2000, 1000), clips[0])
        assertEquals(Clip("c2", "a1", 2000, 4000, 3000), clips[1])
    }

    @Test
    fun splitOutsideRangeFails() {
        try {
            TimelineOps.split(timeline(), "c1", 900, "c2")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("จุดแยก"))
        }
        try {
            TimelineOps.split(timeline(), "missing", 2000, "c2")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ไม่มีคลิป"))
        }
    }

    @Test
    fun trimMoveDeleteDuplicate() {
        var t = TimelineOps.trim(timeline(), "c1", 500, 3500, null)
        assertEquals(500, t.tracks[0].clips[0].startMs)
        try {
            TimelineOps.trim(t, "c1", null, 99999, null, maxEndMs = 4000)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("4000"))
        }
        t = TimelineOps.move(t, "c1", 8000, null, MediaKind.VIDEO)
        assertEquals(8000, t.tracks[0].clips[0].atMs)
        t = TimelineOps.duplicate(t, "c1", null, "c9")
        assertEquals(2, t.tracks[0].clips.size)
        assertEquals(11000, t.tracks[0].clips.first { it.id == "c9" }.atMs)
        t = TimelineOps.delete(t, "c1")
        assertEquals(listOf("c9"), t.tracks[0].clips.map { it.id })
    }

    @Test
    fun moveAcrossKindFails() {
        val t = timeline().copy(tracks = timeline().tracks + Track("A1", MediaKind.AUDIO))
        try {
            TimelineOps.move(t, "c1", 0, "A1", MediaKind.VIDEO)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ข้ามชนิด"))
        }
    }

    @Test
    fun markersAndFlags() {
        var t = TimelineOps.addMarker(timeline(), TimelineMarker("m1", 2000, "hi"))
        assertEquals(1, t.markers.size)
        t = TimelineOps.removeMarker(t, "m1")
        assertTrue(t.markers.isEmpty())
        t = TimelineOps.trackFlags(t, "V1", locked = true, muted = null, hidden = true, color = "red")
        assertTrue(t.tracks[0].locked && t.tracks[0].hidden)
        try {
            TimelineOps.delete(t, "c1")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun transformAppliesAndIdentityClears() {
        var t = TimelineOps.transform(timeline(), "c1", ClipTransform(rotation = 90, scale = 150))
        assertEquals(90, t.tracks[0].clips[0].transform!!.rotation)
        assertEquals(150, t.tracks[0].clips[0].transform!!.scale)
        t = TimelineOps.transform(t, "c1", ClipTransform())
        assertEquals(null, t.tracks[0].clips[0].transform)
    }

    @Test
    fun transformRejectsBadInput() {
        try {
            TimelineOps.transform(timeline(), "c1", ClipTransform(rotation = 45))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0/90/180/270"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("ca", "aa", 0, 1000, 0)))))
        try {
            TimelineOps.transform(audio, "ca", ClipTransform(rotation = 90))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
        val locked = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(Clip("c1", "a1", 0, 4000, 1000)), locked = true)))
        try {
            TimelineOps.transform(locked, "c1", ClipTransform(scale = 200))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun insertHoldSplitsRipplesAndInserts() {
        val still = Clip("s1", "img1", 0, 2000, 0)
        val t = TimelineOps.insertHold(timeline(), "c1", 3000, 2000, still)
        val clips = t.tracks[0].clips.sortedBy { it.atMs }
        assertEquals(3, clips.size)
        assertEquals(Clip("c1", "a1", 0, 2000, 1000), clips[0].copy(transform = null))
        assertEquals("s1", clips[1].id)
        assertEquals(3000, clips[1].atMs)
        assertEquals(2000, clips[1].durationMs)
        assertEquals(2000, clips[2].startMs)
        assertEquals(5000, clips[2].atMs)
    }

    @Test
    fun insertHoldEdgesKeepNoEmptyClips() {
        val t = TimelineOps.insertHold(timeline(), "c1", 1000, 1000, Clip("s1", "img1", 0, 1000, 0))
        assertEquals(2, t.tracks[0].clips.size)
        assertTrue(t.tracks[0].clips.all { it.durationMs > 0 })
        val t2 = TimelineOps.insertHold(timeline(), "c1", 5000, 1000, Clip("s2", "img1", 0, 1000, 0))
        assertEquals(2, t2.tracks[0].clips.size)
        assertTrue(t2.tracks[0].clips.all { it.durationMs > 0 })
        try {
            TimelineOps.insertHold(timeline(), "c1", 3000, 50, Clip("s3", "img1", 0, 50, 0))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("100..30000"))
        }
    }

    @Test
    fun textAddUpdateRemove() {
        var t = TimelineOps.addText(timeline(), OverlayText("t1", "สวัสดี", 0, 3000))
        assertEquals(1, t.texts.size)
        t = TimelineOps.updateText(t, "t1") { it.copy(text = "แก้แล้ว", animIn = "fade") }
        assertEquals("แก้แล้ว", t.texts[0].text)
        assertEquals("fade", t.texts[0].animIn)
        t = TimelineOps.removeText(t, "t1")
        assertTrue(t.texts.isEmpty())
        try {
            TimelineOps.addText(t, OverlayText("t2", "", 0, 1000))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ว่าง"))
        }
        try {
            TimelineOps.removeText(t, "missing")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ไม่มีข้อความ"))
        }
    }

    @Test
    fun textPresetsValidate() {
        for (name in listOf("title", "lower", "caption", "hook", "cta", "nope")) {
            val preset = OverlayText.preset(name).copy(id = "x", text = "T", startMs = 0, endMs = 1000)
            assertTrue(name, preset.validate().isEmpty())
        }
    }

    @Test
    fun speedMappingMath() {
        val fast = ClipSpeed(rate = 200)
        assertEquals(2000, fast.outputDuration(4000))
        assertEquals(2000, fast.outputToSource(1000, 4000))
        assertEquals(1000, fast.sourceToOutput(2000, 4000))
        val slow = ClipSpeed(rate = 50)
        assertEquals(8000, slow.outputDuration(4000))
        assertEquals(0, slow.outputToSource(0, 4000))
        assertEquals(4000, slow.outputToSource(8000, 4000))
        // Curve endpoints are exact, midpoint is shaped.
        val ramp = ClipSpeed(rate = 100, curve = ClipSpeed.preset("easein"))
        assertEquals(4000, ramp.outputDuration(4000))
        assertEquals(0, ramp.outputToSource(0, 4000))
        assertEquals(4000, ramp.outputToSource(4000, 4000))
        val mid = ramp.outputToSource(2000, 4000)
        assertTrue("mid=$mid", mid in 1..3999)
        // Roundtrip through the inverse walk.
        assertTrue(kotlin.math.abs(ramp.sourceToOutput(mid, 4000) - 2000) <= 2)
    }

    @Test
    fun splitRespectsSpeed() {
        val sped = TimelineOps.speed(timeline(), "c1", ClipSpeed(rate = 200))
        assertEquals("2x", sped.tracks[0].clips[0].speed!!.summary())
        // Timeline end is now 1000 + 2000 = 3000; split at 2000 → source cut at 2000.
        val t = TimelineOps.split(sped, "c1", 2000, "c2")
        val clips = t.tracks[0].clips.sortedBy { it.atMs }
        assertEquals(2000, clips[0].endMs)
        assertEquals(2000, clips[1].startMs)
        assertEquals(2000, clips[1].atMs)
        try {
            TimelineOps.speed(t, "c1", ClipSpeed(rate = 999))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("25..400"))
        }
        try {
            TimelineOps.speed(t, "c1", ClipSpeed(reverse = true, curve = ClipSpeed.preset("hero")))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("curve"))
        }
    }
}
