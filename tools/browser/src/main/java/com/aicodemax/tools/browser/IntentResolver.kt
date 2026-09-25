package com.aicodemax.tools.browser

import com.aicodemax.core.common.fold

/**
 * CP-147 (spec แก้ai §7): split every address-bar / AI navigation request into
 * URL vs SEARCH vs COMMAND — never let the model guess.
 *
 * - `เปิด github.com` / `เปิด Google` → URL
 * - `ค้นหา Qwen3 4B` / `หาร้านซ่อมมอเตอร์ไซค์ศรีสะเกษ` → SEARCH
 * - bare `Qwen3 4B` (spaces, no URL shape) → SEARCH (never `http://Qwen3 4B`)
 * - `/cmd ...` or known control verbs → COMMAND
 */
enum class NavIntent { URL, SEARCH, COMMAND }

data class ResolvedIntent(
    val intent: NavIntent,
    /** Normalized URL (URL), raw query (SEARCH), or command text (COMMAND). */
    val target: String,
)

object IntentResolver {
    private val openVerbs = listOf("เปิดเว็บ", "เปิดเว็ป", "เปิดไซต์", "เปิดเว็บไซด์", "เปิด")
    private val searchVerbs = listOf("ค้นหา", "ค้น", "เสิร์ช", "เซิร์ช", "search", "หา")
    private val commandVerbs = mapOf(
        "แท็บใหม่" to "new_tab",
        "ปิดแท็บ" to "close_tab",
        "ย้อนกลับ" to "back",
        "ไปข้างหน้า" to "forward",
        "โหลดใหม่" to "reload",
        "รีโหลด" to "reload",
        "หยุด" to "stop",
        "หน้าแรก" to "home",
        "บุ๊กมาร์ก" to "bookmark",
        "ที่คั่น" to "bookmark",
        "ประวัติ" to "history",
        "ดาวน์โหลด" to "downloads",
    )

    fun resolve(text: String): ResolvedIntent {
        val input = text.trim()
        if (input.startsWith("/")) {
            return ResolvedIntent(NavIntent.COMMAND, input.removePrefix("/").trim())
        }
        for (verb in openVerbs) {
            val glue = verbGlue(input, verb)
            if (glue == VerbGlue.NONE) continue
            val target = input.removePrefix(verb).trim()
            if (target.isEmpty()) continue
            val url = UrlResolver.normalize(target).fold(
                onSuccess = { it },
                onFailure = {
                    // "เปิดxxx" glued to a non-URL Thai phrase: search the phrase.
                    if (glue == VerbGlue.THAI_GLUED) return ResolvedIntent(NavIntent.SEARCH, input)
                    return ResolvedIntent(NavIntent.SEARCH, target)
                },
            )
            return ResolvedIntent(NavIntent.URL, url)
        }
        for (verb in searchVerbs) {
            val glue = verbGlue(input, verb)
            if (glue == VerbGlue.SPACED) {
                val query = input.removePrefix(verb).trim()
                if (query.isNotEmpty()) return ResolvedIntent(NavIntent.SEARCH, query)
            } else if (glue == VerbGlue.THAI_GLUED) {
                // Thai has no word spaces: "หาร้าน..." keeps the whole phrase.
                return ResolvedIntent(NavIntent.SEARCH, input)
            }
        }
        for ((thai, cmd) in commandVerbs) {
            if (input == thai) return ResolvedIntent(NavIntent.COMMAND, cmd)
        }
        // Bare input: URL shape wins, everything else is a search query.
        return UrlResolver.normalize(input).fold(
            onSuccess = { ResolvedIntent(NavIntent.URL, it) },
            onFailure = { ResolvedIntent(NavIntent.SEARCH, input) },
        )
    }

    private enum class VerbGlue { NONE, SPACED, THAI_GLUED }

    private fun verbGlue(input: String, verb: String): VerbGlue {
        if (!input.startsWith(verb) || input.length <= verb.length) return VerbGlue.NONE
        val next = input[verb.length]
        if (next.isWhitespace()) return VerbGlue.SPACED
        // Thai verb glued to a Thai word ("หาร้าน...") — still a search intent.
        if (next.isLetter() && verb.any { it in 'ก'..'ฮ' } && next in 'ก'..'ฮ') return VerbGlue.THAI_GLUED
        return VerbGlue.NONE
    }
}
