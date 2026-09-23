package com.aicodemax.tools.capability

/**
 * CP-130 (spec Phase 16 + §16): dynamic few-shot — record successful
 * (query → tool call) examples, retrieve the most similar for prompting.
 * In-memory with line-format serialize/load for later persistence.
 */
class FewShotStore(private val maxExamples: Int = 200) {
    data class Example(
        val query: String,
        val capabilityId: String,
        val argsSkeleton: Map<String, String> = emptyMap(),
        val ok: Boolean = true,
    )

    private val examples = ArrayDeque<Example>()

    @Synchronized
    fun record(query: String, capabilityId: String, argsSkeleton: Map<String, String> = emptyMap(), ok: Boolean = true) {
        examples.addLast(Example(query, capabilityId, argsSkeleton, ok))
        while (examples.size > maxExamples) examples.removeFirst()
    }

    /** Most similar successful examples, ranked by token overlap. */
    @Synchronized
    fun relevant(query: String, limit: Int = 3): List<Example> {
        val queryTokens = ToolRetriever.tokens(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()
        return examples.filter { it.ok }
            .map { it to ToolRetriever.tokens(it.query).toSet().intersect(queryTokens).size }
            .filter { (_, overlap) -> overlap > 0 }
            .sortedWith(compareByDescending<Pair<Example, Int>> { it.second }.thenBy { it.first.query })
            .take(limit.coerceIn(1, 10))
            .map { it.first }
    }

    @Synchronized fun size(): Int = examples.size

    @Synchronized
    fun serialize(): String = examples.joinToString("\n") {
        listOf(if (it.ok) "1" else "0", it.capabilityId, sanitize(it.query),
            it.argsSkeleton.entries.joinToString(",") { (k, v) -> "${sanitize(k)}=${sanitize(v)}" })
            .joinToString("|")
    }

    @Synchronized
    fun load(text: String) {
        examples.clear()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@forEach
            val skeleton = parts.drop(3).joinToString("|").split(",")
                .mapNotNull { kv -> kv.split("=").takeIf { it.size == 2 }?.let { (a, b) -> a to b } }
                .toMap()
            record(parts[2], parts[1], skeleton, parts[0] == "1")
        }
    }

    private fun sanitize(s: String): String = s.replace("|", "/").replace("\n", " ").take(500)
}
