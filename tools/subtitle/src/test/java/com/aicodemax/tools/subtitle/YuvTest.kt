package com.aicodemax.tools.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class YuvTest {
    @Test
    fun whiteAndBlack() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val out = Yuv.toI420(intArrayOf(white, white, black, black), 2, 2)
        assertEquals(6, out.size) // 4 Y + 1 U + 1 V
        assertEquals(235, out[0].toInt() and 0xFF) // white Y
        assertEquals(16, out[2].toInt() and 0xFF) // black Y
        // Mid-gray chroma for the mixed 2x2 block.
        assertEquals(128, out[4].toInt() and 0xFF)
        assertEquals(128, out[5].toInt() and 0xFF)
    }

    @Test
    fun nv12InterleavesChroma() {
        val red = 0xFFFF0000.toInt()
        val out = Yuv.toNV12(IntArray(4) { red }, 2, 2)
        assertEquals(6, out.size)
        assertEquals(82, out[0].toInt() and 0xFF)
        assertEquals(90, out[4].toInt() and 0xFF) // U first
        assertEquals(240, out[5].toInt() and 0xFF) // then V
    }

    @Test
    fun pureRedChroma() {
        val red = 0xFFFF0000.toInt()
        val out = Yuv.toI420(IntArray(4) { red }, 2, 2)
        assertEquals(82, out[0].toInt() and 0xFF) // Y of red
        assertEquals(90, out[4].toInt() and 0xFF) // U of red
        assertEquals(240, out[5].toInt() and 0xFF) // V of red
    }
}
