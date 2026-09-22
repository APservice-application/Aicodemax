package com.aicodemax.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaKind
import com.aicodemax.tools.media.GenCapability
import com.aicodemax.tools.media.GenKinds
import com.aicodemax.tools.media.GenPort
import com.aicodemax.tools.media.GenRequest
import com.aicodemax.tools.media.GenResult
import com.aicodemax.tools.voice.VoicePort
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-83 §46: real offline generation providers (Canvas PNG + local TTS WAV).
 * Cloud slots (text2video/music/sfx) fail honestly until §29 Cloud AI lands.
 */
class AndroidGenPort(
    private val filesDir: File,
    private val voice: VoicePort,
) : GenPort {
    private val mem = com.aicodemax.tools.media.InMemoryGenPort()

    override fun list(): List<GenCapability> = mem.list()

    override suspend fun generate(request: GenRequest): Outcome<GenResult> =
        withContext(Dispatchers.IO) {
            val cap = list().find { it.kind == request.kind }
                ?: return@withContext Outcome.Failure(AppError("GEN_KIND", "kind ไม่รู้จัก (${request.kind})"))
            if (!cap.available) {
                return@withContext Outcome.Failure(AppError("GEN_UNAVAILABLE", "${cap.title}: ${cap.note}"))
            }
            try {
                when (request.kind) {
                    GenKinds.POSTER -> poster(request)
                    GenKinds.BACKGROUND -> background(request)
                    GenKinds.STYLIZE -> stylize(request)
                    GenKinds.TTS -> tts(request)
                    else -> Outcome.Failure(AppError("GEN_KIND", "kind ไม่รู้จัก (${request.kind})"))
                }
            } catch (e: Exception) {
                Outcome.Failure(AppError("GEN_FAILED", "สร้างไม่ได้: ${e.message}"))
            }
        }

    private fun outFile(kind: String, ext: String): File {
        val dir = File(filesDir, "gen").apply { mkdirs() }
        return File(dir, "gen-$kind-${System.currentTimeMillis()}.$ext")
    }

    private fun savePng(bmp: Bitmap): File {
        val f = outFile("img", "png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun poster(request: GenRequest): Outcome<GenResult> {
        if (request.prompt.isBlank()) {
            return Outcome.Failure(AppError("GEN_PROMPT", "โปสเตอร์ต้องมีข้อความ (prompt)"))
        }
        val w = request.width.coerceIn(64, 1920)
        val h = request.height.coerceIn(64, 1920)
        val (top, bottom) = palette(request.style.ifBlank { "indigo" })
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) },
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(h / 90f, 0f, h / 180f, 0xAA000000.toInt())
        }
        val lines = wrap(request.prompt.trim().take(200), paint, w * 0.86f, h * 0.16f)
        paint.textSize = h * 0.16f
        // Shrink until the widest line fits.
        while (paint.textSize > h * 0.04f && lines.any { paint.measureText(it) > w * 0.86f }) {
            paint.textSize *= 0.92f
        }
        val lineH = paint.textSize * 1.3f
        var y = h / 2f - lineH * (lines.size - 1) / 2f
        for (line in lines) {
            cv.drawText(line, w / 2f, y, paint)
            y += lineH
        }
        val f = savePng(bmp)
        bmp.recycle()
        return Outcome.Success(GenResult(f.path, MediaKind.IMAGE, "โปสเตอร์ ${w}x$h (${lines.size} บรรทัด)"))
    }

    /** Char-level wrap (works for Thai, which has no spaces). */
    private fun wrap(text: String, paint: Paint, maxW: Float, startSize: Float): List<String> {
        paint.textSize = startSize
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var cur = ""
        fun flush() {
            if (cur.isNotEmpty()) lines.add(cur)
            cur = ""
        }
        for (word in words) {
            var rest = if (cur.isEmpty()) word else "$cur $word"
            while (paint.measureText(rest) > maxW && rest.length > 1) {
                // Binary-search the longest fitting prefix.
                var lo = 1
                var hi = rest.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (paint.measureText(rest.substring(0, mid)) <= maxW) lo = mid else hi = mid - 1
                }
                lines.add(rest.substring(0, lo))
                rest = rest.substring(lo).trimStart()
            }
            cur = rest
            if (lines.size + 1 >= 8) break
        }
        flush()
        return lines.take(8)
    }

    private fun background(request: GenRequest): Outcome<GenResult> {
        val w = request.width.coerceIn(64, 1920)
        val h = request.height.coerceIn(64, 1920)
        val style = request.style.ifBlank { request.prompt.ifBlank { "dusk" } }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val solid = Regex("^solid:?([0-9A-Fa-f]{6})$").find(style)?.groupValues?.get(1)
        if (solid != null) {
            cv.drawColor(android.graphics.Color.parseColor("#$solid"))
        } else {
            val (top, bottom) = palette(style)
            cv.drawRect(
                0f, 0f, w.toFloat(), h.toFloat(),
                Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), top, bottom, Shader.TileMode.CLAMP) },
            )
        }
        val f = savePng(bmp)
        bmp.recycle()
        return Outcome.Success(GenResult(f.path, MediaKind.IMAGE, "พื้นหลัง ${w}x$h ($style)"))
    }

    private fun palette(style: String): Pair<Int, Int> {
        fun c(hex: String): Int = android.graphics.Color.parseColor(hex)
        return when (style.lowercase()) {
            "warm", "dusk" -> c("#4A148C") to c("#FF6F00")
            "sea", "cool" -> c("#01579B") to c("#004D40")
            "bw", "mono" -> c("#424242") to c("#000000")
            "rose" -> c("#880E4F") to c("#1A237E")
            "forest" -> c("#1B5E20") to c("#000000")
            else -> c("#1A237E") to c("#000000")
        }
    }

    private fun stylize(request: GenRequest): Outcome<GenResult> {
        if (request.inputPath.isBlank()) {
            return Outcome.Failure(AppError("GEN_INPUT", "stylize ต้องมี path รูปต้นฉบับ"))
        }
        val src = BitmapFactory.decodeFile(request.inputPath)
            ?: return Outcome.Failure(AppError("GEN_INPUT", "อ่านรูปต้นฉบับไม่ได้"))
        val scale = 1280f / maxOf(src.width, src.height).coerceAtLeast(1)
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else {
            src
        }
        val out = Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888)
        val matrix = styleMatrix(request.style.ifBlank { "vivid" })
        Canvas(out).drawBitmap(
            work, 0f, 0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
        )
        if (work !== src) work.recycle()
        src.recycle()
        val f = savePng(out)
        out.recycle()
        return Outcome.Success(GenResult(f.path, MediaKind.IMAGE, "แต่งรูป (${request.style.ifBlank { "vivid" }})"))
    }

    private fun styleMatrix(style: String): ColorMatrix {
        val m = ColorMatrix()
        when (style.lowercase()) {
            "bw" -> m.setSaturation(0f)
            "warm" -> {
                m.setSaturation(1.1f)
                m.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.12f, 0f, 0f, 0f, 8f,
                            0f, 1.02f, 0f, 0f, 0f,
                            0f, 0f, 0.88f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            "cool" -> {
                m.setSaturation(1.05f)
                m.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            0.9f, 0f, 0f, 0f, 0f,
                            0f, 1.0f, 0f, 0f, 0f,
                            0f, 0f, 1.12f, 0f, 8f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            "cinema" -> {
                m.setSaturation(0.85f)
                m.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.1f, 0f, 0f, 0f, -12f,
                            0f, 1.05f, 0f, 0f, -12f,
                            0f, 0f, 0.95f, 0f, -6f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            else -> {
                // vivid: saturation + gentle S-contrast
                m.setSaturation(1.35f)
                m.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.12f, 0f, 0f, 0f, -14f,
                            0f, 1.12f, 0f, 0f, -14f,
                            0f, 0f, 1.12f, 0f, -14f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
        }
        return m
    }

    private suspend fun tts(request: GenRequest): Outcome<GenResult> {
        if (request.prompt.isBlank()) {
            return Outcome.Failure(AppError("GEN_PROMPT", "เสียงพูดต้องมีข้อความ (prompt)"))
        }
        val out = outFile("tts", "wav")
        return when (val r = voice.speakToFile(request.prompt.take(1000), request.lang, out.path)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                if (!out.isFile || out.length() == 0L) {
                    Outcome.Failure(AppError("GEN_FAILED", "TTS ไม่สร้างไฟล์ (engine อาจไม่รองรับภาษานี้)"))
                } else {
                    Outcome.Success(GenResult(out.path, MediaKind.AUDIO, "เสียงพูด ${out.length() / 1024} KB"))
                }
            }
        }
    }
}
