package com.aicodemax.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.ClipColor
import com.aicodemax.tools.media.ColorAnalysis
import com.aicodemax.tools.media.ColorPort
import com.aicodemax.tools.media.ColorRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-81 §42 AI-lite: real auto color from a sampled frame (offline, no ML).
 * Gray-world white balance + histogram levels (p2/p50/p98) → suggested [ClipColor].
 */
class AndroidColorPort : ColorPort {
    override suspend fun analyze(request: ColorRequest): Outcome<ColorAnalysis> =
        withContext(Dispatchers.IO) {
            val px = grabPixels(request.assetPath, request.atMs)
                ?: return@withContext Outcome.Failure(
                    com.aicodemax.core.common.AppError("COLOR_NO_FRAME", "อ่านเฟรมตัวอย่างไม่ได้ (${request.assetPath})"),
                )
            Outcome.Success(suggest(px))
        }

    private fun grabPixels(path: String, atMs: Long): IntArray? {
        if (path.startsWith("mem://")) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bmp = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val scale = 160f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                bmp
            }
            val out = IntArray(small.width * small.height)
            small.getPixels(out, 0, small.width, 0, 0, small.width, small.height)
            if (small !== bmp) small.recycle()
            bmp.recycle()
            out
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Pure suggestion math — kept separate for clarity (and JVM-portable logic). */
    internal fun suggest(px: IntArray): ColorAnalysis {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        val hist = IntArray(256)
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            rSum += r
            gSum += g
            bSum += b
            hist[((0.299 * r + 0.587 * g + 0.114 * b).toInt()).coerceIn(0, 255)]++
        }
        val n = px.size.coerceAtLeast(1).toDouble()
        val rMean = rSum / n
        val gMean = gSum / n
        val bMean = bSum / n
        val avg = (rMean + gMean + bMean) / 3.0
        fun percentile(p: Double): Int {
            var acc = 0L
            val target = (px.size * p).toLong()
            for (i in hist.indices) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        val p2 = percentile(0.02)
        val p50 = percentile(0.50)
        val p98 = percentile(0.98)
        val notes = mutableListOf<String>()
        // Gray-world: renderer temp+ warms (adds R, removes B).
        val temperature = if (avg > 1) {
            (((bMean - rMean) / avg * 120).toInt()).coerceIn(-80, 80).also {
                if (it >= 15) notes.add("ภาพติดฟ้า: อุ่น+$it")
                if (it <= -15) notes.add("ภาพติดเหลือง: เย็น$it")
            }
        } else {
            0
        }
        // Renderer tint+ adds green.
        val tint = if (avg > 1) {
            (((avg - gMean) / avg * 150).toInt()).coerceIn(-60, 60).takeIf { kotlin.math.abs(it) >= 12 }?.also {
                notes.add("สมดุลเขียว: ทินต์${if (it > 0) "+" else ""}$it")
            } ?: 0
        } else {
            0
        }
        val blacks = if (p2 > 12) (-((p2 - 12) * 1.2).toInt().coerceAtMost(60)).also {
            notes.add("ดำลอย (p2=$p2): กดดำ$it")
        } else {
            0
        }
        val whites = if (p98 < 235) ((235 - p98).coerceAtMost(60)).also {
            notes.add("ขาวไม่สุด (p98=$p98): ดันขาว+$it")
        } else {
            0
        }
        val exposure = when {
            p50 < 80 -> ((80 - p50) / 4).coerceAtMost(40).also { notes.add("ภาพมืด (กลาง=$p50): เปิดรับแสง+$it") }
            p50 > 175 -> (-((p50 - 175) / 4).coerceAtMost(40)).also { notes.add("ภาพสว่างเกิน (กลาง=$p50): ลดรับแสง$it") }
            else -> 0
        }
        if (notes.isEmpty()) notes.add("ภาพสมดุลดีอยู่แล้ว")
        return ColorAnalysis(
            ClipColor(temperature = temperature, tint = tint, whites = whites, blacks = blacks, exposure = exposure),
            notes.joinToString("; "),
        )
    }
}
