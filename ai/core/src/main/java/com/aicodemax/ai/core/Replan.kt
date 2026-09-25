package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/**
 * CP-148 (spec §31–§34): self-correction primitives.
 *
 * Execute → Observe → Verify → (fail) → Diagnose → Correct → Execute Again.
 * [PlanDiagnoser] is the rule-based fast path (spec §32 error recovery);
 * [RePlanner] is the LLM slow path that rewrites the remaining plan.
 * Both are pure/seam-based so the loop stays unit-testable.
 */
enum class FailureAction {
    RETRY,
    FIX_AND_RETRY,
    REPLAN,
    HANDOFF_AUTH,
    ABORT,
}

data class Diagnosis(
    val action: FailureAction,
    val hint: String,
    val fixedArgs: Map<String, String> = emptyMap(),
)

data class ExecutedStep(
    val step: PlanStep,
    val result: StepResult,
)

data class ReplanRequest(
    val goal: String,
    val executed: List<ExecutedStep>,
    val failedStep: PlanStep?,
    val diagnosis: Diagnosis,
    /** 1-based replan attempt (loop bounds it with maxReplans). */
    val attempt: Int,
)

interface RePlanner {
    suspend fun replan(req: ReplanRequest): Outcome<Plan>
}

/** Spec §32: what each failure code means and what to do about it. */
object PlanDiagnoser {
    fun diagnose(
        toolId: String,
        action: String,
        code: String,
        message: String,
        attempt: Int,
        args: Map<String, String> = emptyMap(),
    ): Diagnosis {
        val where = "$toolId.$action"
        val text = ("$code $message").uppercase()
        val lower = ("$code $message").lowercase()

        // §31 login handoff: never ask for passwords — park and wait for the user.
        if ("AUTH_REQUIRED" in text || "LOGIN_REQUIRED" in text ||
            "LOGIN WALL" in text || " 401" in " $text" ||
            ("login" in lower && ("required" in lower || "needed" in lower || "wall" in lower))
        ) {
            return Diagnosis(
                FailureAction.HANDOFF_AUTH,
                "เว็บ/เครื่องมือต้องล็อกอินก่อน ($where) — หยุดรอผู้ใช้ล็อกอิน แล้วทำต่อ",
            )
        }
        // §32 INVALID_URL: fix the URL, don't fail the task.
        if ("INVALID_URL" in text || "MALFORMED_URL" in text) {
            val fixed = args.toMutableMap()
            args["url"]?.let { fixed["url"] = fixUrl(it) }
            return Diagnosis(
                FailureAction.FIX_AND_RETRY,
                "URL ใช้ไม่ได้ ($where) — แก้ URL แล้วลองใหม่",
                fixed,
            )
        }
        if ("PERMISSION" in text && ("DENIED" in text || "REQUIRED" in text)) {
            return Diagnosis(FailureAction.ABORT, "ต้องขออนุญาตผู้ใช้ก่อน ($where) — หยุดงาน")
        }
        if ("NETWORK_ERROR" in text || "TIMEOUT" in text || "TIMED OUT" in lower ||
            "CONNECT" in text && "FAIL" in text || "UNKNOWNHOST" in text
        ) {
            return if (attempt < 2) {
                Diagnosis(FailureAction.RETRY, "เน็ตขัดข้องชั่วคราว ($where) — ลองใหม่ครั้งที่ ${attempt + 1}")
            } else {
                Diagnosis(FailureAction.ABORT, "เน็ตล้มเหลว 3 ครั้ง ($where) — แจ้งผู้ใช้แล้วหยุด")
            }
        }
        if ("PAGE_LOAD_FAILED" in text || "PAGE LOAD" in text && "FAIL" in text) {
            return if (attempt < 1) {
                Diagnosis(FailureAction.RETRY, "โหลดหน้าไม่สำเร็จ ($where) — ลองโหลดใหม่อีกครั้ง")
            } else {
                Diagnosis(FailureAction.REPLAN, "โหลดหน้าไม่สำเร็จซ้ำ ($where) — ตรวจ page state แล้ววางแผนใหม่")
            }
        }
        if ("ELEMENT_NOT_FOUND" in text || "NOT FOUND" in text && "SELECTOR" in text) {
            return Diagnosis(
                FailureAction.REPLAN,
                "หาองค์ประกอบในหน้าไม่เจอ ($where) — ตรวจ page state แล้ววางแผนใหม่",
            )
        }
        // Generic tool failure: one blind retry, then let the LLM replan.
        return if (attempt < 1) {
            Diagnosis(FailureAction.RETRY, "เครื่องมือล้มเหลว ($where) — ลองใหม่อีกครั้ง")
        } else {
            Diagnosis(FailureAction.REPLAN, "เครื่องมือล้มเหลวซ้ำ ($where): ${message.take(200)} — วางแผนใหม่")
        }
    }

    /** Minimal URL repair for FIX_AND_RETRY (full normalization lives in UrlResolver). */
    fun fixUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
        return "https://$t"
    }
}

/** Try [primary]; only when it honestly fails, try [fallback] (LLM). */
class CascadePlanner(
    private val primary: Planner,
    private val fallback: Planner,
) : Planner {
    override suspend fun plan(intent: UserIntent): Outcome<Plan> {
        return when (val first = primary.plan(intent)) {
            is Outcome.Success -> first
            is Outcome.Failure -> when (val second = fallback.plan(intent)) {
                is Outcome.Success -> second
                is Outcome.Failure -> Outcome.Failure(
                    AppError(
                        second.error.code,
                        "วางแผนไม่ได้ครับ (${first.error.message} / ${second.error.message})",
                    ),
                )
            }
        }
    }
}
