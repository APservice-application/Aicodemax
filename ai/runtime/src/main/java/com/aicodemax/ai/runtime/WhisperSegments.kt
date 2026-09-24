package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome

/** One transcribed cue: millisecond timestamps + text. */
data class WhisperSegment(val t0Ms: Long, val t1Ms: Long, val text: String)

/**
 * CP-140: parse the whisper JNI JSON output + format SRT.
 * Pure Kotlin (hand-rolled parser — no JSON dependency on plain JVM).
 */
object WhisperSegments {
    fun parse(json: String): Outcome<List<WhisperSegment>> = runOutcome("STT_PARSE") {
        val parser = Parser(json)
        parser.parseSegments()
    }

    fun toText(segments: List<WhisperSegment>): String =
        segments.joinToString("\n") { it.text.trim() }.trim()

    fun toSrt(segments: List<WhisperSegment>): String {
        val out = StringBuilder()
        segments.forEachIndexed { index, seg ->
            out.append(index + 1).append('\n')
            out.append(fmtSrtTime(seg.t0Ms)).append(" --> ").append(fmtSrtTime(seg.t1Ms)).append('\n')
            out.append(seg.text.trim()).append("\n\n")
        }
        return out.toString()
    }

    fun fmtSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0)
        val h = clamped / 3_600_000
        val m = (clamped % 3_600_000) / 60_000
        val s = (clamped % 60_000) / 1000
        val rest = clamped % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, rest)
    }

    /**
     * Minimal parser for our own machine-generated JSON:
     * `{"segments":[{"t0":N,"t1":N,"text":"..."}, …]}`.
     * Tolerates whitespace + any key order; rejects anything else honestly.
     */
    private class Parser(val src: String) {
        var pos = 0

        fun parseSegments(): List<WhisperSegment> {
            skipWs()
            expect('{')
            var segments: List<WhisperSegment>? = null
            skipWs()
            if (peek() != '}') {
                while (true) {
                    skipWs()
                    val key = parseString()
                    skipWs()
                    expect(':')
                    skipWs()
                    if (key == "segments") segments = parseArray()
                    else skipValue()
                    skipWs()
                    if (peek() == ',') {
                        pos++
                    } else {
                        break
                    }
                }
            }
            expect('}')
            skipWs()
            if (pos != src.length) throw IllegalArgumentException("ตัวอักษรเกินท้าย JSON (pos=$pos)")
            return segments ?: throw IllegalArgumentException("JSON ไม่มีฟิลด์ segments")
        }

        private fun parseArray(): List<WhisperSegment> {
            expect('[')
            val out = mutableListOf<WhisperSegment>()
            skipWs()
            if (peek() != ']') {
                while (true) {
                    out.add(parseSegment())
                    skipWs()
                    if (peek() == ',') {
                        pos++
                        skipWs()
                    } else {
                        break
                    }
                }
            }
            expect(']')
            return out
        }

        private fun parseSegment(): WhisperSegment {
            expect('{')
            var t0: Long? = null
            var t1: Long? = null
            var text: String? = null
            skipWs()
            if (peek() != '}') {
                while (true) {
                    skipWs()
                    val key = parseString()
                    skipWs()
                    expect(':')
                    skipWs()
                    when (key) {
                        "t0" -> t0 = parseLong()
                        "t1" -> t1 = parseLong()
                        "text" -> text = parseString()
                        else -> skipValue()
                    }
                    skipWs()
                    if (peek() == ',') {
                        pos++
                    } else {
                        break
                    }
                }
            }
            expect('}')
            return WhisperSegment(
                t0 ?: throw IllegalArgumentException("cue ไม่มี t0"),
                t1 ?: throw IllegalArgumentException("cue ไม่มี t1"),
                text ?: throw IllegalArgumentException("cue ไม่มี text"),
            )
        }

        private fun parseString(): String {
            if (peek() != '"') throw IllegalArgumentException("ต้องขึ้นต้นสตริงด้วย \" (pos=$pos)")
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= src.length) throw IllegalArgumentException("สตริงไม่ปิด")
                val c = src[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("escape ไม่สมบูรณ์")
                        when (val e = src[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("\\u ไม่สมบูรณ์")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("escape ไม่รู้จัก: \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseLong(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos == start || (pos == start + 1 && src[start] == '-')) {
                throw IllegalArgumentException("ตัวเลขไม่ถูกต้อง (pos=$start)")
            }
            return src.substring(start, pos).toLong()
        }

        private fun skipValue() {
            skipWs()
            when (peek()) {
                '"' -> parseString()
                '{' -> {
                    pos++
                    skipWs()
                    if (peek() != '}') {
                        while (true) {
                            skipWs()
                            parseString()
                            skipWs()
                            expect(':')
                            skipValue()
                            skipWs()
                            if (peek() == ',') {
                                pos++
                            } else {
                                break
                            }
                        }
                    }
                    expect('}')
                }
                '[' -> {
                    pos++
                    skipWs()
                    if (peek() != ']') {
                        while (true) {
                            skipValue()
                            skipWs()
                            if (peek() == ',') {
                                pos++
                                skipWs()
                            } else {
                                break
                            }
                        }
                    }
                    expect(']')
                }
                else -> {
                    // number / true / false / null
                    while (pos < src.length && src[pos] != ',' && src[pos] != '}' && src[pos] != ']' &&
                        !src[pos].isWhitespace()
                    ) {
                        pos++
                    }
                }
            }
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char =
            if (pos < src.length) src[pos] else throw IllegalArgumentException("JSON จบกลางคัน")

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("ต้องเป็น '$c' (pos=$pos)")
            pos++
        }
    }
}
