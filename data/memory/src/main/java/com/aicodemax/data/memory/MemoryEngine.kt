package com.aicodemax.data.memory

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold

/** Scoped memory policy (CP-09 Context+Memory backfill; MASTER §25). Scopes: GLOBAL | PROJECT:<id> | TASK:<id>. */
class MemoryEngine(private val store: MemoryStore) {
    fun remember(scope: String, key: String, value: String): Outcome<MemoryRecord> =
        store.save(scope, key, value)

    fun recall(scope: String, key: String): Outcome<MemoryRecord> =
        store.recall(scope, key)

    fun rememberGlobal(key: String, value: String): Outcome<MemoryRecord> =
        store.save(GLOBAL, key, value)

    fun rememberProject(projectId: String, key: String, value: String): Outcome<MemoryRecord> =
        store.save("PROJECT:$projectId", key, value)

    fun rememberTask(taskId: String, key: String, value: String): Outcome<MemoryRecord> =
        store.save("TASK:$taskId", key, value)

    fun recallProject(projectId: String, key: String): Outcome<MemoryRecord> =
        store.recall("PROJECT:$projectId", key)

    fun recallTask(taskId: String, key: String): Outcome<MemoryRecord> =
        store.recall("TASK:$taskId", key)

    fun forget(scope: String, key: String): Boolean = store.delete(scope, key)

    /** Newest-first records in a scope (empty query matches everything). */
    fun recent(scopePrefix: String, limit: Int = 20): Outcome<List<MemoryRecord>> =
        store.search("", scopePrefix, limit)

    /**
     * Compaction: keep only the [keepLatest] newest records in [scopePrefix],
     * delete the rest. Returns the number of deleted records.
     */
    fun compact(scopePrefix: String, keepLatest: Int = 100): Outcome<Int> {
        return store.search("", scopePrefix, Int.MAX_VALUE).fold(
            onSuccess = { records ->
                val stale = records.sortedByDescending { it.updatedAt }.drop(keepLatest.coerceAtLeast(0))
                var deleted = 0
                for (record in stale) {
                    if (store.delete(record.scope, record.key)) deleted += 1
                }
                Outcome.Success(deleted)
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    /** Renders scope memories as prompt-ready lines, truncated to [maxChars]. */
    fun summarizeForPrompt(scopePrefix: String, maxChars: Int = 2_000): Outcome<String> {
        return recent(scopePrefix, limit = 200).fold(
            onSuccess = { records ->
                val lines = records.sortedBy { it.updatedAt }
                    .joinToString("\n") { "[${it.scope}] ${it.key}: ${it.value}" }
                val clipped = if (lines.length > maxChars) lines.take(maxChars) + "…[truncated]" else lines
                Outcome.Success(clipped)
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    companion object {
        const val GLOBAL = "GLOBAL"
    }
}
