package com.aicodemax.tools.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-78: histogram scopes (§101). */
class ColorScopesTest {
    @Test
    fun blackWhiteGrayVerdicts() {
        val black = ColorScopes.analyze(PixelImage(4, 4, IntArray(16) { argb(255, 0, 0, 0) }))
        assertEquals("มืดสนิท — ภาพมีปัญหา", black.verdict)
        assertEquals(100.0, black.darkPct, 0.001)
        val white = ColorScopes.analyze(PixelImage(4, 4, IntArray(16) { argb(255, 255, 255, 255) }))
        assertEquals("สว่างจ้า — ภาพมีปัญหา", white.verdict)
        val gray = ColorScopes.analyze(PixelImage(4, 4, IntArray(16) { argb(255, 128, 128, 128) }))
        assertEquals("ปกติ", gray.verdict)
        assertEquals(128.0, gray.meanLuma, 1.0)
        assertEquals(16, gray.sparkline().length)
        assertTrue(gray.summary().contains("ปกติ"))
    }

    @Test
    fun castAndRenderAccumulation() {
        val red = ColorScopes.analyze(PixelImage(4, 4, IntArray(16) { argb(255, 200, 40, 40) }))
        assertTrue(red.verdict.contains("ติดแดง"))
        val acc = RenderScopes()
        assertTrue(acc.isEmpty())
        acc.add(IntArray(64) { argb(255, 100, 100, 100) })
        acc.add(IntArray(64) { argb(255, 100, 100, 100) })
        assertEquals(2, acc.frames)
        val report = acc.report()
        assertTrue(report.pixels > 0)
        assertEquals(100.0, report.meanLuma, 1.0)
    }
}
