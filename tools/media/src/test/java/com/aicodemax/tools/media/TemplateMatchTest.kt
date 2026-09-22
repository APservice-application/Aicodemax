package com.aicodemax.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-80: NCC template tracking on synthetic frames. */
class TemplateMatchTest {
    private fun frame(w: Int = 40, h: Int = 40, squareX: Int, squareY: Int, size: Int = 8): GrayFrame {
        val px = ByteArray(w * h)
        for (y in squareY until squareY + size) {
            for (x in squareX until squareX + size) {
                px[y * w + x] = 0xFF.toByte()
            }
        }
        return GrayFrame(w, h, px)
    }

    @Test
    fun matchFindsShiftedSquare() {
        val f0 = frame(squareX = 10, squareY = 10)
        val f1 = frame(squareX = 14, squareY = 12)
        val templ = TemplateMatch.crop(f0, 4, 4, 20, 20)
        val m = TemplateMatch.match(f1, templ, 4, 4, 8)
        assertEquals(4, m.dx)
        assertEquals(2, m.dy)
        assertTrue("score=${m.score}", m.score > 0.9)
    }

    @Test
    fun trackAbsoluteFollowsMotion() {
        val frames = listOf(
            frame(squareX = 8, squareY = 8),
            frame(squareX = 11, squareY = 8),
            frame(squareX = 14, squareY = 9),
        )
        val matches = TemplateMatch.trackAbsolute(frames, 4, 4, 20, 20, 8)
        assertEquals(3, matches.size)
        assertEquals(0, matches[0].dx)
        assertEquals(3, matches[1].dx)
        assertEquals(6, matches[2].dx)
        assertEquals(1, matches[2].dy)
    }

    @Test
    fun trackDifferentialStepsAndSmooth() {
        val frames = listOf(
            frame(squareX = 8, squareY = 8),
            frame(squareX = 10, squareY = 8),
            frame(squareX = 12, squareY = 8),
        )
        val steps = TemplateMatch.trackDifferential(frames, 4, 4, 20, 20, 6)
        assertEquals(2, steps.size)
        assertEquals(2, steps[0].dx)
        assertEquals(2, steps[1].dx)
        val smooth = TemplateMatch.smooth(listOf(0f to 0f, 10f to 0f, 0f to 0f), 3)
        assertEquals(10f / 3f, smooth[1].first, 0.001f)
    }

    @Test
    fun toPathUsesPercent() {
        val path = TemplateMatch.toPath(listOf(0L, 1000L), listOf(0f, 64f), listOf(0f, 36f), 128, 72)
        assertEquals(50f, path.points[1].dx, 0.001f)
        assertEquals(50f, path.points[1].dy, 0.001f)
        val (mx, my) = path.offsetAt(500)
        assertEquals(25f, mx, 0.001f)
        assertEquals(25f, my, 0.001f)
    }
}
