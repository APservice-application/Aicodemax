package com.aicodemax.tools.debug_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.debug.DebugSession
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for debug (actions: analyze). */
class DebugToolExecutor(private val debug: DebugSession = DebugSession()) : ToolExecutor {
    override val toolId: String = "debug"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "analyze" -> {
                    val error = call.args["error"]
                        ?: return@withContext done(false, error = "missing arg: error")
                    debug.analyze(error).fold(
                        onSuccess = { finding ->
                            val suspect = finding.suspect?.display ?: "unknown frame"
                            done(
                                true,
                                "สาเหตุ: ${finding.cause}\n" +
                                    "จุดน่าสงสัย: $suspect\n" +
                                    "${finding.summary}",
                            )
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "bench" -> {
                    val quick = call.args["quick"]?.lowercase() != "false"
                    val results = com.aicodemax.tools.debug.PerfBench.suite(quick)
                    done(true, com.aicodemax.tools.debug.PerfBench.format(results))
                }
                else -> done(false, error = "unknown action '${call.action}' (have: analyze/bench)")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
