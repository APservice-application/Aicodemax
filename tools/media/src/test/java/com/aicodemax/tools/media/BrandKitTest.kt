package com.aicodemax.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-100: brand kit model. */
class BrandKitTest {
    @Test
    fun validateAndArgb() {
        val kit = BrandKit("b1", "กาแฟดริป", "#FF0000", "", "หอมทุกเช้า")
        assertTrue(kit.validate().isEmpty())
        assertEquals(0xFFFF0000L, kit.argb())
        assertTrue(BrandKit("b2", "", "#FFF").validate().isNotEmpty())
        assertTrue(BrandKit("b3", "x", "notacolor").validate().isNotEmpty())
        assertEquals(0xFFFFFFFFL, BrandKit("b4", "x", "").argb())
    }
}
