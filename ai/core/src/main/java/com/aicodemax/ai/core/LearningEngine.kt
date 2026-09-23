package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.memory.MemoryStore

/**
 * CP-114: LearningEngine (ระบบ ai §50–51).
 *
 * Experience → Extract Pattern → Compare → Deduplicate → Validate →
 * Confidence → Candidate → Promote. Learns ONLY from real outcomes:
 * Success / Failure / Recovery / Repeated Pattern / Verified Result.
 *
 * Anti-instant-truth (§52): nothing is promoted before [minObservations]
 * real observations, and confidence grows gradually with evidence.
 * Lessons persist in memory scope [LESSON_SCOPE] (readable via memory.lessons).
 */
class LearningEngine(
    private val store: MemoryStore,
    private val minObservations: Int = 3,
    private val promoteRate: Double = 0.75,
    private val demoteRate: Double = 0.25,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Lesson(
        val toolAction: String,
        val okCount: Int,
        val failCount: Int,
        val lastError: String,
        val updatedAt: Long,
    ) {
        val observations: Int get() = okCount + failCount
        val successRate: Double get() = if (observations == 0) 0.5 else okCount.toDouble() / observations
        /** Grows with evidence, never 1.0 instantly (§52). */
        val confidence: Double get() = observations.toDouble() / (observations + 5.0)

        fun summary(verdict: String = ""): String =
            "$toolAction: ok=$okCount fail=$failCount rate=${"%.0f".format(successRate * 100)}% " +
                "conf=${"%.2f".format(confidence)}" +
                (if (verdict.isNotBlank()) " $verdict" else "") +
                (if (lastError.isNotBlank()) " last=<$lastError>" else "")
    }

    /** Record one real step outcome. Returns the updated lesson. */
    fun observe(toolAction: String, ok: Boolean, errorCode: String = ""): Outcome<Lesson> {
        val action = toolAction.trim()
        if (action.isBlank()) {
            return Outcome.Failure(com.aicodemax.core.common.AppError("LEARN_NO_ACTION", "toolAction blank"))
        }
        val prev = load(action)
        val next = Lesson(
            toolAction = action,
            okCount = prev.okCount + (if (ok) 1 else 0),
            failCount = prev.failCount + (if (ok) 0 else 1),
            lastError = if (ok) prev.lastError else errorCode.ifBlank { "FAILED" }.take(120),
            updatedAt = clock(),
        )
        store.save(LESSON_SCOPE, action, encode(next)).fold(
            onSuccess = {},
            onFailure = { return Outcome.Failure(it) },
        )
        return Outcome.Success(next)
    }

    /** All lessons, flaky-first (most actionable on top). */
    fun lessons(limit: Int = 20): Outcome<List<Lesson>> =
        store.search("", LESSON_SCOPE, limit.coerceAtLeast(1).coerceAtMost(200)).fold(
            onSuccess = { records ->
                Outcome.Success(
                    records.mapNotNull { decode(it.key, it.value) }
                        .sortedWith(compareBy({ it.successRate }, { -it.observations })),
                )
            },
            onFailure = { Outcome.Failure(it) },
        )

    fun isPromoted(lesson: Lesson): Boolean =
        lesson.observations >= minObservations &&
            (lesson.successRate >= promoteRate || lesson.successRate <= demoteRate)

    fun verdict(lesson: Lesson): String = when {
        !isPromoted(lesson) -> "watching"
        lesson.successRate >= promoteRate -> "reliable"
        else -> "flaky"
    }

    /** Resolver preference: +1 reliable, -1 flaky, 0 neutral/unpromoted. */
    fun preference(toolAction: String): Int {
        val lesson = load(toolAction)
        if (!isPromoted(lesson)) return 0
        return if (lesson.successRate >= promoteRate) 1 else -1
    }

    /** Short warning when a step uses a tool the system learned is flaky. */
    fun flakyWarning(toolAction: String): String? {
        val lesson = load(toolAction)
        if (!isPromoted(lesson) || lesson.successRate > demoteRate) return null
        return "⚠️ เรียนรู้: $toolAction ล้มเหลว ${lesson.failCount}/${lesson.observations} ครั้งหลัง" +
            (if (lesson.lastError.isNotBlank()) " (ล่าสุด: ${lesson.lastError})" else "") +
            " — จะลองต่อแต่ระวังเป็นพิเศษ"
    }

    private fun load(toolAction: String): Lesson =
        store.recall(LESSON_SCOPE, toolAction).fold(
            onSuccess = { decode(toolAction, it.value) ?: Lesson(toolAction, 0, 0, "", 0) },
            onFailure = { Lesson(toolAction, 0, 0, "", 0) },
        )

    private fun encode(lesson: Lesson): String =
        "ok=${lesson.okCount}|fail=${lesson.failCount}|updated=${lesson.updatedAt}|err=${lesson.lastError.replace("|", "/")}"

    private fun decode(toolAction: String, value: String): Lesson? = runCatching {
        val map = value.split("|").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        Lesson(
            toolAction = toolAction,
            okCount = map["ok"]?.toIntOrNull() ?: 0,
            failCount = map["fail"]?.toIntOrNull() ?: 0,
            lastError = map["err"].orEmpty(),
            updatedAt = map["updated"]?.toLongOrNull() ?: 0,
        )
    }.getOrNull()

    companion object {
        const val LESSON_SCOPE = "LESSONS"
    }
}
