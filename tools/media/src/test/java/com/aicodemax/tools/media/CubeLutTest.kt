package com.aicodemax.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-81: .cube LUT parse + sample. */
class CubeLutTest {
    private val identity2 = """
        TITLE "identity 2"
        LUT_3D_SIZE 2
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test
    fun parseIdentityAndSample() {
        val lut = CubeLut.parse(identity2).getOrThrow()
        assertEquals(2, lut.size)
        val mid = lut.sample(0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, mid[0], 0.001f)
        assertEquals(0.5f, mid[1], 0.001f)
        assertEquals(0.5f, mid[2], 0.001f)
        val corner = lut.sample(1f, 0f, 1f)
        assertEquals(1f, corner[0], 0.001f)
        assertEquals(0f, corner[1], 0.001f)
        assertEquals(1f, corner[2], 0.001f)
    }

    @Test
    fun applyToRespectsStrength() {
        val lut = CubeLut.parse(identity2).getOrThrow()
        val px = intArrayOf(0xFF804020.toInt())
        lut.applyTo(px, 0)
        assertEquals(0xFF804020.toInt(), px[0])
        lut.applyTo(px, 100)
        assertEquals(0x80, (px[0] shr 16) and 0xFF)
        assertEquals(0x40, (px[0] shr 8) and 0xFF)
        assertEquals(0x20, px[0] and 0xFF)
    }

    @Test
    fun rejectBadContent() {
        assertTrue(CubeLut.parse("LUT_1D_SIZE 4\n0 0 0\n").isFailure)
        assertTrue(CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n").isFailure)
        assertTrue(CubeLut.parse("nope").isFailure)
        assertTrue(CubeLut.parse("LUT_3D_SIZE 99\n").isFailure)
    }
}
