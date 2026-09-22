package com.aicodemax.tools.image_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.image.InMemoryImagePort
import com.aicodemax.tools.image.PixelImage
import com.aicodemax.tools.image.argb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToolExecutorTest {
    private fun port(): InMemoryImagePort = InMemoryImagePort().also {
        it.put("/tmp/a.png", PixelImage(8, 4, IntArray(8 * 4) { argb(255, 10, 20, 30) }))
    }

    private fun run(port: InMemoryImagePort, action: String, args: Map<String, String>): ToolResult =
        runBlocking {
            val call = ToolCall(id = "c1", toolId = "image", action = action, args = args)
            (ImageToolExecutor(port).execute(call) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun infoReportsDims() {
        val r = run(port(), "info", mapOf("path" to "/tmp/a.png"))
        assertTrue(r.ok)
        assertTrue(r.output.contains("8x4"))
    }

    @Test
    fun resizeCropRotateGray() {
        val p = port()
        val small = run(p, "resize", mapOf("src" to "/tmp/a.png", "maxDim" to "4"))
        assertTrue(small.ok)
        assertEquals(4, p.get("/tmp/a-small.png")!!.width)
        val crop = run(p, "crop", mapOf("src" to "/tmp/a.png", "x" to "0", "y" to "0", "w" to "2", "h" to "2"))
        assertTrue(crop.ok)
        assertEquals(2, p.get("/tmp/a-crop.png")!!.height)
        val rot = run(p, "rotate", mapOf("src" to "/tmp/a.png", "degrees" to "90"))
        assertTrue(rot.ok)
        assertEquals(8, p.get("/tmp/a-rot.png")!!.height)
        val gray = run(p, "grayscale", mapOf("src" to "/tmp/a.png"))
        assertTrue(gray.ok)
        val lum = (0.299 * 10 + 0.587 * 20 + 0.114 * 30).toInt()
        assertEquals(argb(255, lum, lum, lum), p.get("/tmp/a-gray.png")!!.pixel(0, 0))
    }

    @Test
    fun scopesVerdict() {
        val r = run(port(), "scopes", mapOf("path" to "/tmp/a.png"))
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("ปกติ"))
        val missing = run(port(), "scopes", emptyMap())
        assertTrue(!missing.ok)
    }

    @Test
    fun adjustUpscaleRestoreFlow() {
        val p = port()
        val adj = run(p, "adjust", mapOf("src" to "/tmp/a.png", "brightness" to "20"))
        assertTrue(adj.output.ifBlank { adj.error }, adj.ok)
        val up = run(p, "upscale", mapOf("src" to "/tmp/a.png", "scale" to "2"))
        assertTrue(up.output.ifBlank { up.error }, up.ok && up.output.contains("2x"))
        assertEquals(p.get("/tmp/a.png")!!.width * 2, p.get("/tmp/a-big.png")!!.width)
        val re = run(p, "restore", mapOf("src" to "/tmp/a.png"))
        assertTrue(re.output.ifBlank { re.error }, re.ok)
        val bad = run(p, "upscale", mapOf("src" to "/tmp/a.png", "scale" to "3"))
        assertTrue(!bad.ok)
    }

    @Test
    fun missingArgsAreHonest() {
        val p = port()
        assertTrue(!run(p, "info", emptyMap()).ok)
        assertTrue(!run(p, "resize", emptyMap()).ok)
        assertTrue(!run(p, "crop", mapOf("src" to "/tmp/a.png")).ok)
        val r = run(p, "paint", mapOf("src" to "/tmp/a.png"))
        assertTrue(!r.ok)
        assertTrue(r.error.contains("unknown action"))
    }
}
