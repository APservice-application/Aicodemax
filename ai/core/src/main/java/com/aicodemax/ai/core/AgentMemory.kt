package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.fold
import com.aicodemax.data.memory.MemoryRecord
import com.aicodemax.data.memory.MemoryStore

/**
 * CP-148 (spec §35): the agent's memory scopes over [MemoryStore].
 *
 * SHORT:<task> task working notes (capped) · TASK:<task> task facts ·
 * PROJECT:<id> project facts · BROWSER tab context · PREFS user preferences ·
 * LONG long-term lessons. Sensitive keys/values are REFUSED (never stored).
 */
class AgentMemory(
    private val store: MemoryStore,
    private val clock: Clock = SystemClock,
) {
    companion object {
        const val SHORT = "SHORT"
        const val TASK = "TASK"
        const val PROJECT = "PROJECT"
        const val BROWSER = "BROWSER"
        const val PREFS = "PREFS"
        const val LONG = "LONG"
        const val SHORT_CAP = 12

        private val SENSITIVE_KEY = Regex("(?i)passw|passwd|pwd|token|secret|api[_-]?key|auth|credential|private")
        private val SENSITIVE_VALUE = Regex("(?i)(password|passwd|api[_-]?key|token|secret)\\s*[:=]\\s*\\S+")
    }

    fun remember(scope: String, key: String, value: String): Outcome<MemoryRecord> {
        if (SENSITIVE_KEY.containsMatchIn(key) || SENSITIVE_VALUE.containsMatchIn(value)) {
            return Outcome.Failure(AppError("SENSITIVE_REFUSED", "ไม่เก็บข้อมูลลับ ($scope/$key) ครับ"))
        }
        return store.save(scope, key, value)
    }

    fun recallText(scope: String, key: String): Outcome<String> =
        store.recall(scope, key).fold(
            onSuccess = { Outcome.Success(it.value) },
            onFailure = { Outcome.Failure(it) },
        )

    /** Task working note (short-term); pruned to the newest [SHORT_CAP]. */
    fun shortNote(taskId: String, text: String): Outcome<MemoryRecord> {
        val scope = "$SHORT:$taskId"
        val saved = remember(scope, "n${clock.nowMillis()}", text.take(500))
        if (saved is Outcome.Failure) return saved
        store.search("", scope, 1000).fold(
            onSuccess = { records ->
                records.sortedBy { it.createdAt }.dropLast(SHORT_CAP).forEach { store.delete(it.scope, it.key) }
            },
            onFailure = { },
        )
        return saved
    }

    fun taskNote(taskId: String, text: String): Outcome<MemoryRecord> =
        remember("$TASK:$taskId", "n${clock.nowMillis()}", text.take(500))

    fun projectNote(projectId: String, key: String, text: String): Outcome<MemoryRecord> =
        remember("$PROJECT:$projectId", key, text.take(500))

    fun browserNote(text: String): Outcome<MemoryRecord> =
        remember(BROWSER, "n${clock.nowMillis()}", text.take(500))

    fun pref(key: String, value: String): Outcome<MemoryRecord> =
        remember(PREFS, key, value.take(300))

    fun lesson(text: String): Outcome<MemoryRecord> =
        remember(LONG, "n${clock.nowMillis()}", text.take(500))

    /** Compact non-sensitive context for planner prompts (capped lines). */
    fun plannerContext(): String {
        val lines = mutableListOf<String>()
        store.search("", PREFS, 5).fold(
            onSuccess = { records -> records.forEach { lines.add("ชอบ:${it.key}=${it.value.take(80)}") } },
            onFailure = { },
        )
        store.search("", LONG, 5).fold(
            onSuccess = { records -> records.forEach { lines.add("บทเรียน:${it.value.take(120)}") } },
            onFailure = { },
        )
        return lines.take(8).joinToString("\n")
    }
}
