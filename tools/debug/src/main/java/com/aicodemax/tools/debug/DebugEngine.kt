package com.aicodemax.tools.debug

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.runOutcome

/** CP-23: debug engine — failure analysis (MASTER_ARCHITECTURE §23). Live debugging (breakpoints/variables) needs device work; this engine parses and triages stack traces anywhere. */
data class StackFrame(
    val className: String,
    val method: String,
    val file: String,
    val line: Int,
) {
    val display: String get() = "$className.$method($file:$line)"
}

data class DebugFinding(
    val summary: String,
    val cause: String,
    val suspect: StackFrame?,
    val appFrames: List<StackFrame>,
    val analyzedAt: Long,
)

object StackTraceParser {
    private val frameRegex = Regex("""^\s*at\s+([\w.$]+)\.(\w+)\(([^:()]+)(?::(\d+))?\)\s*$""")
    private val causeRegex = Regex("""^(?:Caused by:\s*)?([\w.$]+(?:Exception|Error|Throwable)[\w.$]*)\s*:?\s*(.*)$""")

    private val frameworkPrefixes = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.",
        "com.android.", "dalvik.", "jdk.", "sun.", "org.junit.", "org.gradle.",
    )

    fun parse(text: String): List<StackFrame> =
        text.lineSequence().mapNotNull { line ->
            val match = frameRegex.matchEntire(line.trim()) ?: return@mapNotNull null
            StackFrame(
                className = match.groupValues[1],
                method = match.groupValues[2],
                file = match.groupValues[3],
                line = match.groupValues[4].toIntOrNull() ?: -1,
            )
        }.toList()

    fun cause(text: String): String =
        text.lineSequence().mapNotNull { causeRegex.matchEntire(it.trim()) }.firstOrNull()
            ?.let { "${it.groupValues[1]}: ${it.groupValues[2]}".trim().trimEnd(':') }
            .orEmpty()

    fun isAppFrame(frame: StackFrame): Boolean =
        frameworkPrefixes.none { frame.className.startsWith(it) }
}

class DebugSession(private val clock: Clock = SystemClock) {
    /** Triage a failure: extract cause + first app frame as the suspect. */
    fun analyze(errorText: String): Outcome<DebugFinding> = runOutcome("DEBUG_ANALYZE") {
        check(errorText.isNotBlank()) { "error text is blank" }
        val frames = StackTraceParser.parse(errorText)
        check(frames.isNotEmpty()) { "no stack frames found" }
        val appFrames = frames.filter { StackTraceParser.isAppFrame(it) }
        val suspect = appFrames.firstOrNull()
        val cause = StackTraceParser.cause(errorText).ifBlank { "unknown cause" }
        val summary = if (suspect != null) {
            "$cause — suspect ${suspect.display}"
        } else {
            "$cause — no app frame (framework-only trace)"
        }
        DebugFinding(summary, cause, suspect, appFrames, clock.nowMillis())
    }
}
