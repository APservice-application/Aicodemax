package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-82: template instantiation + validation. */
class TemplateOpsTest {
    private fun template(): ProjectTemplate {
        val clip = Clip("c1", ProjectTemplate.placeholderAsset("main"), 0, 3000, 0)
        return ProjectTemplate(
            "t1", "T", "social", "",
            Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip)))),
            listOf(TemplateSlot("main", "c1", MediaKind.VIDEO, "หลัก")),
        )
    }

    @Test
    fun fromTemplateFillsSlots() {
        val t = TimelineOps.fromTemplate(template(), mapOf("main" to "a9"))
        assertEquals("a9", t.tracks[0].clips[0].assetId)
    }

    @Test
    fun fromTemplateRejectsProblems() {
        try {
            TimelineOps.fromTemplate(template(), emptyMap())
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ขาดมีเดียช่อง"))
        }
        try {
            TimelineOps.fromTemplate(template(), mapOf("main" to "a9", "ghost" to "a1"))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ไม่มีช่อง"))
        }
    }

    @Test
    fun templateValidation() {
        assertTrue(template().validate().isEmpty())
        assertTrue(template().copy(category = "nope").validate().isNotEmpty())
        val dup = template().copy(slots = template().slots + template().slots)
        assertTrue(dup.summary().contains("ช่อง2"))
        assertTrue(dup.validate().any { it.contains("ซ้ำ") })
        assertTrue(template().copy(name = "").validate().isNotEmpty())
    }

    @Test
    fun libraryMatches() {
        val item = LibraryItem("l1", "lut", "Warm Cinema", listOf("อุ่น", "หนัง"), "/luts/warm.cube")
        assertTrue(item.matches("warm"))
        assertTrue(item.matches("หนัง"))
        assertTrue(item.matches("LUT"))
        assertTrue(!item.matches("เย็น"))
        assertTrue(LibraryItem("x", "nope", "N").validate().isNotEmpty())
    }
}
