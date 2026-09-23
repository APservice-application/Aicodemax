package com.aicodemax.tools.capability

/**
 * CP-129 (spec Phase 13 + §13): candidate selection — never send all tools
 * to the model. Scores bindings by id/purpose keyword overlap (Thai + English),
 * with a boost for recently successful tools. Deterministic, dependency-free.
 */
object ToolRetriever {
    data class ScoredBinding(val binding: CapabilityBinding, val score: Int)

    private val stopwords = setOf(
        // Thai
        "ที่", "และ", "ใน", "ให้", "หน่อย", "ครับ", "ค่ะ", "นะ", "ด้วย", "จาก",
        "เป็น", "มี", "ไม่", "ได้", "จะ", "ก็", "แล้ว", "นี้", "นั้น", "อะไร",
        "ยังไง", "อย่างไร", "ทำ", "ช่วย", "ขอ", "หน่อยครับ", "หน่อยค่ะ",
        // English
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "with",
        "please", "me", "my", "is", "it", "this", "that",
    )

    /** Thai/English synonyms mapping user words → tool vocabulary. */
    private val synonyms = mapOf(
        "เว็บ" to listOf("browser", "web", "page"),
        "เวบ" to listOf("browser", "web", "page"),
        "ไฟล์" to listOf("file", "files"),
        "แฟ้ม" to listOf("file", "files"),
        "วิดีโอ" to listOf("video", "media"),
        "วีดีโอ" to listOf("video", "media"),
        "หนัง" to listOf("video", "media"),
        "รูป" to listOf("image"),
        "ภาพ" to listOf("image"),
        "เสียง" to listOf("audio", "voice"),
        "เพลง" to listOf("audio"),
        "คำบรรยาย" to listOf("subtitle"),
        "ซับ" to listOf("subtitle"),
        "โปรเจกต์" to listOf("project", "media"),
        "ตัดต่อ" to listOf("timeline", "edit", "media"),
        "export" to listOf("export", "render"),
        "เอ็กซ์พอร์ต" to listOf("export", "render"),
        "เรนเดอร์" to listOf("render"),
        "เทอร์มินัล" to listOf("terminal", "exec", "command"),
        "รัน" to listOf("terminal", "exec", "run"),
        "คำสั่ง" to listOf("terminal", "exec", "command"),
        "จำ" to listOf("memory", "save"),
        "ความจำ" to listOf("memory"),
        "บทเรียน" to listOf("memory", "lesson"),
        "โมเดล" to listOf("model"),
        "โหลดโมเดล" to listOf("model", "download"),
        "ดาวน์โหลด" to listOf("download"),
        "ค้นหา" to listOf("search"),
        "หา" to listOf("search", "find"),
    )

    fun tokens(text: String): List<String> =
        text.lowercase()
            // \p{M}: Thai vowel/tone marks (Mn) must NOT split words.
            .split(Regex("[^\\p{L}\\p{M}\\p{N}_.]+"))
            .map { it.trim('.', '_') }
            .filter { it.isNotEmpty() && it !in stopwords }

    /**
     * Thai has no word spaces ("จำบทเรียนนี้" is one token): also match
     * synonym keys CONTAINED in a query token.
     */
    private fun expand(token: String): List<String> = buildList {
        add(token)
        synonyms[token]?.let { addAll(it) }
        for ((key, values) in synonyms) {
            if (key != token && token.contains(key)) {
                add(key)
                addAll(values)
            }
        }
    }

    /**
     * Top-[limit] candidate bindings for [query]. [history] holds recently
     * successful capabilityIds ("tool.action" or tool prefix) for boosting.
     * Empty query → empty list (honest, never random tools).
     */
    fun retrieve(
        query: String,
        bindings: List<CapabilityBinding>,
        history: List<String> = emptyList(),
        limit: Int = 5,
    ): List<ScoredBinding> {
        val queryTokens = tokens(query).flatMap { expand(it) }.toSet()
        if (queryTokens.isEmpty()) return emptyList()
        val recent = history.flatMap { h ->
            h.split(".").let { parts ->
                listOf(h) + parts.take(1)
            }
        }.toSet()
        return bindings.mapNotNull { binding ->
            var score = 0
            val idTokens = (binding.toolId.split("_", ".") + binding.action.split("_", ".") + binding.capabilityId.split("."))
                .map { it.lowercase() }.toSet()
            val textTokens = tokens(binding.toolId + " " + binding.action + " " + binding.metadata.purpose + " " + binding.metadata.inputs.joinToString(" ")).toSet()
            for (qt in queryTokens) {
                if (qt in idTokens) score += 5
                else if (textTokens.any { it.contains(qt) || qt.contains(it) }) score += 2
            }
            if (binding.toolId in recent || binding.capabilityId in recent) score += 3
            if (score > 0) ScoredBinding(binding, score) else null
        }.sortedWith(compareByDescending<ScoredBinding> { it.score }.thenBy { it.binding.capabilityId })
            .take(limit.coerceIn(1, 10))
    }
}
