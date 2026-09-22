package com.aicodemax.tools.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOpsTest {
    /** 4x2 with distinct corners: TL=red TR=green BL=blue BR=white. */
    private fun sample(): PixelImage {
        val red = argb(255, 255, 0, 0)
        val green = argb(255, 0, 255, 0)
        val blue = argb(255, 0, 0, 255)
        val white = argb(255, 255, 255, 255)
        return PixelImage(4, 2, intArrayOf(red, red, green, green, blue, blue, white, white))
    }

    @Test
    fun resizeShrinksLongSide() {
        val out = ImageOps.resize(sample(), 2)
        assertEquals(2, out.width)
        assertEquals(1, out.height)
    }

    @Test
    fun resizeKeepsSmallImage() {
        assertEquals(sample(), ImageOps.resize(sample(), 99))
    }

    @Test
    fun cropCutsExactRect() {
        val out = ImageOps.crop(sample(), 0, 0, 2, 1)
        assertEquals(2, out.width)
        assertEquals(1, out.height)
        assertEquals(argb(255, 255, 0, 0), out.pixel(0, 0))
        assertEquals(argb(255, 255, 0, 0), out.pixel(1, 0))
    }

    @Test
    fun rotate90SwapsDimsAndMovesPixels() {
        val out = ImageOps.rotate(sample(), 90)
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        // TL red (0,0) -> top-right (1,0) in clockwise rotation.
        assertEquals(argb(255, 255, 0, 0), out.pixel(1, 0))
        // BL blue (0,1) -> top-left (0,0).
        assertEquals(argb(255, 0, 0, 255), out.pixel(0, 0))
    }

    @Test
    fun rotate180And270() {
        val r180 = ImageOps.rotate(sample(), 180)
        assertEquals(4, r180.width)
        assertEquals(argb(255, 255, 255, 255), r180.pixel(0, 0))
        val r270 = ImageOps.rotate(sample(), 270)
        assertEquals(2, r270.width)
        assertEquals(4, r270.height)
        assertEquals(ImageOps.rotate(ImageOps.rotate(sample(), 90), 90), r180)
    }

    @Test
    fun rotateRejectsArbitraryAngles() {
        try {
            ImageOps.rotate(sample(), 45)
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("90/180/270"))
        }
    }

    @Test
    fun grayscaleUsesLuminance() {
        val out = ImageOps.grayscale(sample())
        // red -> 0.299*255 = 76
        assertEquals(argb(255, 76, 76, 76), out.pixel(0, 0))
        // white stays white, alpha preserved
        assertEquals(argb(255, 255, 255, 255), out.pixel(3, 1))
    }
}
