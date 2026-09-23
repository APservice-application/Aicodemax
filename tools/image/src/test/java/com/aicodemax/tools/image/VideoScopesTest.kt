package com.aicodemax.tools.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScopesTest {
    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun waveSeesClipAndCrush() {
        val w = 40
        val h = 10
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            when {
                x < 10 -> argb(255, 255, 255)
                x < 20 -> argb(0, 0, 0)
                else -> argb(128, 128, 128)
            }
        }
        val wave = VideoScopes.waveform(pixels, w, h)
        assertEquals(10, wave.clippedCols())
        assertEquals(10, wave.crushedCols())
        assertTrue(wave.ascii().contains("@"))
    }

    @Test
    fun vectorSeesSaturation() {
        val gray = IntArray(100) { argb(128, 128, 128) }
        val red = IntArray(100) { argb(255, 0, 0) }
        val flat = VideoScopes.vectorscope(gray)
        val hot = VideoScopes.vectorscope(red)
        assertTrue(flat.meanSat < 0.05)
        assertTrue(hot.meanSat > 0.5)
        assertTrue(hot.ascii().contains("+"))
    }

    @Test
    fun paradeSeesCast() {
        val w = 40
        val h = 4
        val pixels = IntArray(w * h) { argb(200, 100, 100) }
        val parade = VideoScopes.parade(pixels, w, h)
        assertTrue(parade.summary().contains("แดงเด่น"))
        assertTrue(parade.ascii().contains("R"))
    }

    @Test
    fun reportBundlesAll() {
        val pixels = IntArray(64) { argb(100, 120, 140) }
        val report = VideoScopes.report(pixels, 8, 8)
        val text = report.fullText()
        assertTrue(text.contains("ฮิสโตแกรม"))
        assertTrue(text.contains("เวฟฟอร์ม"))
        assertTrue(text.contains("เวกเตอร์สโคป"))
        assertTrue(text.contains("พาเหรด"))
    }
}
