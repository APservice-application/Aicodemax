package com.aicodemax.ai.agents

/**
 * CP-132 (spec §32): retrieved-tools → decision → validation → result trace.
 * Sinks (audit log, UI) subscribe via [traceSink]; [snapshot] keeps the last
 * turn for tests and debugging.
 */
class ToolTracer(
    private val maxSteps: Int = 60,
    var traceSink: ((String) -> Unit)? = null,
) {
    private val steps = ArrayDeque<String>()

    @Synchronized
    fun step(line: String) {
        steps.addLast(line)
        while (steps.size > maxSteps) steps.removeFirst()
        traceSink?.invoke(line)
    }

    fun candidates(query: String, ids: List<String>) =
        step("retrieve “${query.take(80)}” → ${if (ids.isEmpty()) "(none)" else ids.joinToString(",")}")

    fun decision(actionId: String) = step("decide $actionId")

    fun validation(actionId: String, verdict: String) = step("validate $actionId → $verdict")

    fun result(actionId: String, ok: Boolean, detail: String) =
        step("result $actionId ${if (ok) "OK" else "FAIL"} ${detail.take(120)}")

    @Synchronized fun snapshot(): List<String> = steps.toList()

    @Synchronized fun clear() = steps.clear()
}
