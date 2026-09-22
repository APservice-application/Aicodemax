package com.aicodemax.tools.subtitle

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/** One subtitle cue. Times in ms. */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    val lines: List<String>,
)

/** CP-66: pure SRT parse/format/shift/distribute (TH/EN text is opaque UTF-8). */
object Srt {
    private val stampPattern = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

    fun format(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, cue ->
            appendLine("${i + 1}")
            appendLine("${stamp(cue.startMs)} --> ${stamp(cue.endMs)}")
            cue.lines.forEach { appendLine(it) }
            appendLine()
        }
    }

    fun stamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0)
        val h = clamped / 3_600_000
        val m = (clamped % 3_600_000) / 60_000
        val s = (clamped % 60_000) / 1000
        val milli = clamped % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    fun parse(text: String): Outcome<List<Cue>> {
        val blocks = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) {
            return Outcome.Failure(AppError("SUB_EMPTY", "ไฟล์ซับว่างเปล่า"))
        }
        val cues = mutableListOf<Cue>()
        for ((i, block) in blocks.withIndex()) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
            if (lines.size < 2) {
                return Outcome.Failure(AppError("SUB_PARSE", "บล็อกที่ ${i + 1} ไม่ครบ (ต้องมีเลข+เวลา+ข้อความ)"))
            }
            val stampLine = lines.firstOrNull { it.contains("-->") }
                ?: return Outcome.Failure(AppError("SUB_PARSE", "บล็อกที่ ${i + 1} ไม่มีบรรทัดเวลา"))
            val match = stampPattern.find(stampLine)
                ?: return Outcome.Failure(AppError("SUB_PARSE", "เวลาบล็อกที่ ${i + 1} ผิดรูปแบบ: $stampLine"))
            val start = toMs(match, 1)
            val end = toMs(match, 5)
            if (end <= start) {
                return Outcome.Failure(AppError("SUB_PARSE", "บล็อกที่ ${i + 1} เวลาจบต้องมากกว่าเวลาเริ่ม"))
            }
            val content = lines.filter { it != stampLine }.let {
                if (lines[0] != stampLine) it.drop(1) else it
            }
            if (content.isEmpty()) {
                return Outcome.Failure(AppError("SUB_PARSE", "บล็อกที่ ${i + 1} ไม่มีข้อความ"))
            }
            cues.add(Cue(start, end, content))
        }
        return Outcome.Success(cues)
    }

    /** Shifts all cues by [offsetMs] (negative = earlier, clamped at 0). */
    fun shift(cues: List<Cue>, offsetMs: Long): List<Cue> =
        cues.map {
            val start = (it.startMs + offsetMs).coerceAtLeast(0)
            val end = (it.endMs + offsetMs).coerceAtLeast(start + 100)
            it.copy(startMs = start, endMs = end)
        }

    /**
     * Distributes [transcript] over [durationMs] into cues of ~[maxChars] chars
     * (2 lines). Durations are proportional to text length (min 800ms/cue).
     * Thai without spaces is hard-cut — honest v0 until word segmentation lands.
     */
    fun distribute(transcript: String, durationMs: Long, maxChars: Int = 84): List<Cue> {
        val clean = transcript.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty() || durationMs <= 0) return emptyList()
        val chunks = mutableListOf<String>()
        var rest = clean
        while (rest.isNotEmpty()) {
            if (rest.length <= maxChars) {
                chunks.add(rest)
                break
            }
            var cut = rest.lastIndexOf(' ', maxChars)
            if (cut < maxChars / 3) cut = maxChars // Thai/no-space: hard cut.
            chunks.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        val totalChars = chunks.sumOf { it.length }.coerceAtLeast(1)
        var cursor = 0L
        return chunks.mapIndexed { i, chunk ->
            val share = if (i == chunks.lastIndex) {
                durationMs - cursor
            } else {
                maxOf(800L, durationMs * chunk.length / totalChars)
            }
            val cue = Cue(cursor, (cursor + share).coerceAtMost(durationMs), wrap(chunk))
            cursor += share
            cue
        }.filter { it.endMs > it.startMs }
    }

    /** Wraps a cue chunk into ≤2 balanced lines. */
    fun wrap(chunk: String, lineMax: Int = 42): List<String> {
        if (chunk.length <= lineMax) return listOf(chunk)
        var cut = chunk.lastIndexOf(' ', lineMax)
        if (cut < lineMax / 3) cut = lineMax
        val first = chunk.substring(0, cut).trim()
        val second = chunk.substring(cut).trim()
        if (second.isEmpty()) return listOf(first)
        if (second.length <= lineMax) return listOf(first, second)
        return listOf(first, second.take(lineMax))
    }

    private fun toMs(match: MatchResult, base: Int): Long {
        val h = match.groupValues[base].toLong()
        val m = match.groupValues[base + 1].toLong()
        val s = match.groupValues[base + 2].toLong()
        val milli = match.groupValues[base + 3].toLong()
        return h * 3_600_000 + m * 60_000 + s * 1000 + milli
    }
}
