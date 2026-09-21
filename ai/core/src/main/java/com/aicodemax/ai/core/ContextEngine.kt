package com.aicodemax.ai.core

/**
 * CP-09: budget-aware context builder (MASTER_ARCHITECTURE §9/§35/§67).
 * Sections compete on priority; the fitter keeps high-priority sections,
 * truncates at most one to fill leftover budget, and drops the rest —
 * always reporting what was dropped so the AI never hallucinates context.
 */
data class ContextSection(
    val id: String,
    val text: String,
    val priority: Int = 0,
)

data class ContextFitResult(
    val kept: List<ContextSection>,
    val droppedIds: List<String>,
    val truncatedChars: Int,
    val estimatedTokens: Int,
)

object ContextEngine {
    /** Rough token estimate (~4 chars/token for mixed EN/TH/code). */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun fit(sections: List<ContextSection>, maxChars: Int): ContextFitResult {
        require(maxChars >= 0) { "maxChars must be >= 0" }
        val kept = mutableListOf<ContextSection>()
        val dropped = mutableListOf<String>()
        var used = 0
        var truncated = 0
        for (section in sections.sortedByDescending { it.priority }) {
            val room = maxChars - used
            when {
                section.text.length <= room -> {
                    kept.add(section)
                    used += section.text.length
                }
                room >= 64 -> {
                    kept.add(section.copy(text = section.text.take(room)))
                    truncated += section.text.length - room
                    used = maxChars
                }
                else -> dropped.add(section.id)
            }
        }
        val tokens = kept.sumOf { estimateTokens(it.text) }
        return ContextFitResult(kept, dropped, truncated, tokens)
    }
}
