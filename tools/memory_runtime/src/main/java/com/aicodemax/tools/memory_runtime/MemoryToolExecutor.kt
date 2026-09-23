package com.aicodemax.tools.memory_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.memory.MemoryEngine
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for memory (actions: save/recall/lessons; lessons = CP-114 learned tool reliability). */
class MemoryToolExecutor(private val memory: MemoryEngine) : ToolExecutor {
    override val toolId: String = "memory"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "save" -> {
                    val key = call.args["key"]
                        ?: return@withContext done(false, error = "missing arg: key")
                    val value = call.args["value"]
                        ?: return@withContext done(false, error = "missing arg: value")
                    memory.rememberGlobal(key, value).fold(
                        onSuccess = { done(true, "จำแล้ว: $key") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "recall" -> {
                    val key = call.args["key"]
                        ?: return@withContext done(false, error = "missing arg: key")
                    memory.recall(MemoryEngine.GLOBAL, key).fold(
                        onSuccess = { done(true, "${it.key}: ${it.value}") },
                        onFailure = { done(false, error = "จำไม่ได้ว่า '$key' คืออะไร") },
                    )
                }
                "lessons" -> {
                    memory.recent("LESSONS", 20).fold(
                        onSuccess = { records ->
                            if (records.isEmpty()) {
                                done(true, "ยังไม่มีบทเรียน — AI จะเรียนรู้จากงานที่ทำจริง (สำเร็จ/ล้มเหลว) อัตโนมัติ")
                            } else {
                                done(true, records.joinToString("\n") { "• ${it.key}: ${it.value}" })
                            }
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: save/recall/lessons)")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
