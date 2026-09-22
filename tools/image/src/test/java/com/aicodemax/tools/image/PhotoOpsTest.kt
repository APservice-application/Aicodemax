package com.aicodemax.tools.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-89: photo DSP. */
class PhotoOpsTest {
    private fun gray(w: Int, h: Int, v: Int): PixelImage =
        PixelImage(w, h, IntArray(w * h) { argb(255, v, v, v) })

    @Test
    fun adjustBrightens() {
        val out = PhotoOps.adjust(gray(4, 4, 100), brightness = 20)
        assertTrue(redOf(out.pixels[0]) > 100)
        val dark = PhotoOps.adjust(gray(4, 4, 100), brightness = -20)
        assertTrue(redOf(dark.pixels[0]) < 100)
    }

    @Test
    fun upscaleDoubles() {
        val src = PixelImage(2, 2, intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255), argb(255, 255, 0, 0), argb(255, 0, 0, 255)))
        val out = PhotoOps.upscale(src, 2)
        assertEquals(4, out.width)
        assertEquals(4, out.height)
        assertTrue(out.pixels.any { redOf(it) in 1..254 })
    }

    @Test
    fun restoreDenoises() {
        val px = IntArray(25) { argb(255, 100, 100, 100) }
        px[12] = argb(255, 255, 255, 255)
        val out = PhotoOps.restore(PixelImage(5, 5, px), denoise = true, deFade = false, whiteBalance = false)
        assertTrue(redOf(out.pixels[12]) < 200)
    }
}
