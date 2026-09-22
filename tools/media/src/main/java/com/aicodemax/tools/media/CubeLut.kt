package com.aicodemax.tools.media

/**
 * CP-81 §42 Pro: minimal Adobe .cube LUT parser + trilinear sampler.
 * Pure Kotlin (JVM-tested); the Android renderer applies it per clip.
 * Supports: TITLE, LUT_3D_SIZE N, DOMAIN_MIN/MAX (default 0..1), data lines R G B.
 * 1D/SHALLOW variants are rejected honestly.
 */
data class ColorLut(val size: Int, val data: FloatArray) {
    init {
        require(size >= 2) { "LUT size ต้อง ≥ 2" }
        require(data.size == size * size * size * 3) { "ข้อมูล LUT ไม่ครบ (${data.size})" }
    }

    /** Trilinear sample at rgb 0..1 → rgb 0..1. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val x = (r.coerceIn(0f, 1f) * (size - 1)).coerceIn(0f, (size - 1).toFloat())
        val y = (g.coerceIn(0f, 1f) * (size - 1)).coerceIn(0f, (size - 1).toFloat())
        val z = (b.coerceIn(0f, 1f) * (size - 1)).coerceIn(0f, (size - 1).toFloat())
        val x0 = x.toInt().coerceAtMost(size - 2)
        val y0 = y.toInt().coerceAtMost(size - 2)
        val z0 = z.toInt().coerceAtMost(size - 2)
        val fx = x - x0
        val fy = y - y0
        val fz = z - z0
        fun at(ix: Int, iy: Int, iz: Int, c: Int): Float =
            data[((iz * size + iy) * size + ix) * 3 + c]
        return FloatArray(3) { c ->
            val c000 = at(x0, y0, z0, c)
            val c100 = at(x0 + 1, y0, z0, c)
            val c010 = at(x0, y0 + 1, z0, c)
            val c110 = at(x0 + 1, y0 + 1, z0, c)
            val c001 = at(x0, y0, z0 + 1, c)
            val c101 = at(x0 + 1, y0, z0 + 1, c)
            val c011 = at(x0, y0 + 1, z0 + 1, c)
            val c111 = at(x0 + 1, y0 + 1, z0 + 1, c)
            val x00 = c000 + (c100 - c000) * fx
            val x10 = c010 + (c110 - c010) * fx
            val x01 = c001 + (c101 - c001) * fx
            val x11 = c011 + (c111 - c011) * fx
            val y0v = x00 + (x10 - x00) * fy
            val y1v = x01 + (x11 - x01) * fy
            y0v + (y1v - y0v) * fz
        }
    }

    /** In-place ARGB pixel pass; strength 0..100 mixes original↔lut. */
    fun applyTo(px: IntArray, strength: Int = 100): IntArray {
        if (strength <= 0) return px
        val mix = strength.coerceIn(0, 100) / 100f
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val o = sample(r, g, b)
            val nr = (r + (o[0].coerceIn(0f, 1f) - r) * mix) * 255f
            val ng = (g + (o[1].coerceIn(0f, 1f) - g) * mix) * 255f
            val nb = (b + (o[2].coerceIn(0f, 1f) - b) * mix) * 255f
            px[i] = (c and 0xFF000000.toInt()) or
                (nr.toInt().coerceIn(0, 255) shl 16) or
                (ng.toInt().coerceIn(0, 255) shl 8) or
                nb.toInt().coerceIn(0, 255)
        }
        return px
    }
}

object CubeLut {
    const val MAX_SIZE = 64

    /** Parses .cube text; returns null with reason on unsupported content. */
    fun parse(text: String): Result<ColorLut> {
        var size = 0
        val triples = mutableListOf<FloatArray>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val head = line.substringBefore(' ').uppercase()
            when (head) {
                "TITLE", "DOMAIN_MIN", "DOMAIN_MAX" -> Unit
                "LUT_1D_SIZE" -> return Result.failure(IllegalArgumentException("รองรับเฉพาะ 3D LUT (.cube LUT_3D_SIZE)"))
                "LUT_3D_SIZE" -> {
                    size = line.substringAfter(' ').trim().toIntOrNull()
                        ?: return Result.failure(IllegalArgumentException("LUT_3D_SIZE ไม่ใช่ตัวเลข"))
                    if (size !in 2..MAX_SIZE) {
                        return Result.failure(IllegalArgumentException("LUT_3D_SIZE ต้องอยู่ 2..$MAX_SIZE (ได้ $size)"))
                    }
                }
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 3) return Result.failure(IllegalArgumentException("บรรทัดข้อมูลผิดรูป: $line"))
                    val rgb = parts.take(3).map { it.toFloatOrNull() }
                    if (rgb.any { it == null }) return Result.failure(IllegalArgumentException("ข้อมูล LUT ไม่ใช่ตัวเลข: $line"))
                    triples.add(floatArrayOf(rgb[0]!!, rgb[1]!!, rgb[2]!!))
                }
            }
        }
        if (size < 2) return Result.failure(IllegalArgumentException("ไม่พบ LUT_3D_SIZE"))
        if (triples.size != size * size * size) {
            return Result.failure(IllegalArgumentException("ข้อมูล LUT ไม่ครบ (ได้ ${triples.size} ต้องการ ${size * size * size})"))
        }
        val data = FloatArray(triples.size * 3)
        triples.forEachIndexed { i, t ->
            data[i * 3] = t[0]
            data[i * 3 + 1] = t[1]
            data[i * 3 + 2] = t[2]
        }
        return Result.success(ColorLut(size, data))
    }
}
