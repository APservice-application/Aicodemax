package com.aicodemax.tools.debug_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.debug.DebugSession
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for debug (actions: analyze/bench/native). */
class DebugToolExecutor(
    private val debug: DebugSession = DebugSession(),
    private val nativeLibDir: String? = null,
    private val nativeRunner: ((executable: String, args: List<String>) -> String)? = null,
) : ToolExecutor {
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
                "native" -> {
                    val runner = nativeRunner ?: { _: String, _: List<String> -> "" }
                    val report = com.aicodemax.tools.runtime.NativeToolchain.detect(nativeLibDir, runner)
                    done(true, com.aicodemax.tools.runtime.NativeToolchain.format(report))
                }
                "tools" -> {
                    val query = call.args["query"] ?: call.args["q"]
                        ?: return@withContext done(false, error = "missing arg: query")
                    val history = call.args["history"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    val limit = call.args["limit"]?.toIntOrNull() ?: 5
                    val found = com.aicodemax.tools.capability.ToolRetriever.retrieve(
                        query, com.aicodemax.tools.capability.StandardCapabilities.bindings(), history, limit,
                    )
                    if (found.isEmpty()) done(true, "ไม่เจอ tool ที่ตรงกับ “$query”")
                    else done(true, found.joinToString("\n") { "• ${it.binding.capabilityId} — ${it.binding.metadata.purpose} (score ${it.score})" })
                }
                else -> done(false, error = "unknown action '${call.action}' (have: analyze/bench/native/tools)")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
