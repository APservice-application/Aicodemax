package com.aicodemax.tools.media

/**
 * CP-97: script → video beats. Splits narration into captioned scenes;
 * the executor renders each beat as TTS + background + text overlay.
 */
object ScriptVideo {
    const val MAX_BEATS = 20

    /** Blank-line separated beats; "# ..." comments and empties dropped. */
    fun parse(script: String): List<String> {
        val beats = script.split(Regex("\\n\\s*\\n"))
            .map { it.lines().filterNot { l -> l.trimStart().startsWith("#") }.joinToString(" ").trim() }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (beats.isEmpty()) throw IllegalArgumentException("บทยังว่างอยู่")
        if (beats.size > MAX_BEATS) throw IllegalArgumentException("บทยาวไป (${beats.size} ช่วง มากสุด $MAX_BEATS)")
        return beats
    }

    /** Fallback narration length when the TTS file can't be probed (ms). */
    fun estimateMs(beat: String): Long = (beat.length * 120L + 800L).coerceIn(2000L, 20000L)
}
