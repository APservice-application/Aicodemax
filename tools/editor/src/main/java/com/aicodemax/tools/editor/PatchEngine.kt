package com.aicodemax.tools.editor

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/**
 * CP-18: patch engine (MASTER_ARCHITECTURE §18 — Code Engine).
 * Line-based edits with preview-before-apply and inverse ops for rollback.
 * Lines are 1-based; [endLine] is exclusive (startLine..endLine replaced).
 */
data class EditOp(
    val startLine: Int,
    val endLine: Int,
    val replacement: String,
)

object PatchEngine {
    fun apply(content: String, ops: List<EditOp>): Outcome<String> {
        if (ops.isEmpty()) {
            return Outcome.Failure(AppError("PATCH_EMPTY", "no edit ops"))
        }
        val lines = content.split("\n").toMutableList()
        // Bottom-up so earlier line numbers stay valid.
        for (op in ops.sortedByDescending { it.startLine }) {
            if (op.startLine < 1 || op.endLine < op.startLine || op.startLine > lines.size + 1 ||
                op.endLine > lines.size + 1
            ) {
                return Outcome.Failure(
                    AppError(
                        "PATCH_RANGE",
                        "lines ${op.startLine}..${op.endLine} out of 1..${lines.size + 1}",
                    ),
                )
            }
            val replacement = if (op.replacement.isEmpty()) {
                emptyList()
            } else {
                op.replacement.split("\n")
            }
            val head = lines.subList(0, op.startLine - 1).toList()
            val tail = lines.subList((op.endLine - 1).coerceAtMost(lines.size), lines.size).toList()
            lines.clear()
            lines.addAll(head + replacement + tail)
        }
        return Outcome.Success(lines.joinToString("\n"))
    }

    /** Inverse ops that restore [content] after [ops] were applied. */
    fun invert(content: String, ops: List<EditOp>): Outcome<List<EditOp>> {
        val lines = content.split("\n")
        val inverse = mutableListOf<EditOp>()
        for (op in ops.sortedBy { it.startLine }) {
            if (op.startLine < 1 || op.endLine < op.startLine || op.endLine > lines.size + 1) {
                return Outcome.Failure(AppError("PATCH_RANGE", "lines ${op.startLine}..${op.endLine} invalid"))
            }
            val original = lines.subList(op.startLine - 1, (op.endLine - 1).coerceAtMost(lines.size))
                .joinToString("\n")
            val newLineCount = if (op.replacement.isEmpty()) 0 else op.replacement.split("\n").size
            inverse.add(EditOp(op.startLine, op.startLine + newLineCount, original))
        }
        return Outcome.Success(inverse)
    }

    /** Minimal readable diff: common prefix/suffix trimmed, middle shown old → new. */
    fun previewDiff(old: String, new: String, contextLines: Int = 2): String {
        val a = old.split("\n")
        val b = new.split("\n")
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++
        if (prefix + suffix >= a.size && a.size == b.size) return "(no changes)"
        return buildString {
            val ctxStart = maxOf(0, prefix - contextLines)
            for (i in ctxStart until prefix) appendLine("  ${a[i]}")
            for (i in prefix until a.size - suffix) appendLine("- ${a[i]}")
            for (i in prefix until b.size - suffix) appendLine("+ ${b[i]}")
            for (i in b.size - suffix until minOf(b.size, b.size - suffix + contextLines)) {
                appendLine("  ${b[i]}")
            }
        }.trimEnd()
    }
}
