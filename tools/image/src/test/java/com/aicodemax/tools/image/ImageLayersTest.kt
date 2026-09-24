package com.aicodemax.tools.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageLayersTest {
    private fun layer(id: String, name: String = "L$id", path: String = "/tmp/$id.png") =
        ImageLayer(id = id, name = name, path = path)

    private fun stackOf(vararg ids: String): LayerStack =
        ids.fold(LayerStack()) { acc, id -> acc.add(layer(id)) }

    @Test
    fun addSelectsNewLayer() {
        val stack = stackOf("a", "b")
        assertEquals(2, stack.layers.size)
        assertEquals("b", stack.selected!!.id)
        assertEquals("a", stack.layers.first().id) // bottom -> top
    }

    @Test
    fun renameOkBlankFailsLockedFails() {
        val renamed = stackOf("a").rename("a", " พื้นหลัง ")
        assertEquals("พื้นหลัง", renamed.selected!!.name)
        try {
            renamed.rename("a", "  ")
            fail("blank name must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ห้ามว่าง"))
        }
        val locked = renamed.toggleLock("a")
        try {
            locked.rename("a", "x")
            fail("locked rename must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun duplicateInsertsAboveAndSelectsCopy() {
        val duped = stackOf("a", "b").select("a").duplicate("a", "a2", "/tmp/a2.png")
        assertEquals(listOf("a", "a2", "b"), duped.layers.map { it.id })
        assertEquals("a2", duped.selected!!.id)
        assertTrue(duped.selected!!.name.contains("สำเนา"))
        assertEquals("/tmp/a2.png", duped.selected!!.path)
        try {
            stackOf("a").toggleLock("a").duplicate("a", "a2", "/tmp/a2.png")
            fail("locked duplicate must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun removeFreesSelectionToTop() {
        val stack = stackOf("a", "b", "c").select("b").remove("b")
        assertEquals(listOf("a", "c"), stack.layers.map { it.id })
        assertEquals("c", stack.selected!!.id) // falls back to top
        try {
            stackOf("a").toggleLock("a").remove("a")
            fail("locked remove must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun toggleVisibleAndLock() {
        val stack = stackOf("a").toggleVisible("a").toggleLock("a")
        assertTrue(!stack.selected!!.visible)
        assertTrue(stack.selected!!.locked)
        assertTrue(stack.visibleLayers().isEmpty())
        val back = stack.toggleVisible("a")
        assertEquals(1, back.visibleLayers().size)
    }

    @Test
    fun mergeDownKeepsLowerName() {
        val merged = stackOf("a", "b", "c").select("b").mergeDown("b", "m", "/tmp/m.png")
        assertEquals(listOf("m", "c"), merged.layers.map { it.id })
        assertEquals("La", merged.layers[0].name) // lower name kept
        assertEquals("/tmp/m.png", merged.layers.first { it.id == "m" }.path)
        assertEquals("m", merged.selected!!.id)
        try {
            stackOf("a", "b").mergeDown("a", "m", "/tmp/m.png")
            fail("bottom merge must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล่างสุด"))
        }
        try {
            stackOf("a", "b").toggleLock("a").mergeDown("b", "m", "/tmp/m.png")
            fail("locked merge must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun updatePathLockedFails() {
        val ok = stackOf("a").updatePath("a", "/tmp/new.png")
        assertEquals("/tmp/new.png", ok.selected!!.path)
        try {
            stackOf("a").toggleLock("a").updatePath("a", "/tmp/new.png")
            fail("locked update must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }

    @Test
    fun flattenOpaqueTopReplaces() {
        val bottom = PixelImage(2, 1, IntArray(2) { argb(255, 255, 0, 0) })
        val top = PixelImage(2, 1, IntArray(2) { argb(255, 0, 0, 255) })
        val out = ImageOps.flatten(listOf(bottom, top))
        assertEquals(2, out.width)
        assertEquals(argb(255, 0, 0, 255), out.pixel(0, 0))
        assertEquals(argb(255, 0, 0, 255), out.pixel(1, 0))
    }

    @Test
    fun flattenTransparentTopKeepsBottom() {
        val bottom = PixelImage(1, 1, intArrayOf(argb(255, 10, 20, 30)))
        val top = PixelImage(1, 1, intArrayOf(argb(0, 200, 200, 200)))
        val out = ImageOps.flatten(listOf(bottom, top))
        assertEquals(argb(255, 10, 20, 30), out.pixel(0, 0))
    }

    @Test
    fun flattenBlendsHalfAlpha() {
        val bottom = PixelImage(1, 1, intArrayOf(argb(255, 255, 0, 0)))
        val top = PixelImage(1, 1, intArrayOf(argb(128, 0, 0, 255)))
        val p = ImageOps.flatten(listOf(bottom, top)).pixel(0, 0)
        assertEquals(255, alphaOf(p))
        assertTrue("red in blend range, got ${redOf(p)}", redOf(p) in 120..135)
        assertEquals(0, greenOf(p))
        assertTrue("blue in blend range, got ${blueOf(p)}", blueOf(p) in 120..135)
    }

    @Test
    fun flattenCanvasUsesMaxDims() {
        val big = PixelImage(4, 2, IntArray(8) { argb(255, 1, 2, 3) })
        val small = PixelImage(2, 1, IntArray(2) { argb(255, 9, 9, 9) })
        val out = ImageOps.flatten(listOf(big, small))
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        assertEquals(argb(255, 9, 9, 9), out.pixel(0, 0)) // top-left aligned
        assertEquals(argb(255, 1, 2, 3), out.pixel(3, 1)) // untouched corner
    }

    @Test
    fun flattenEmptyFails() {
        try {
            ImageOps.flatten(emptyList())
            fail("empty flatten must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1 เลเยอร์"))
        }
    }
}
