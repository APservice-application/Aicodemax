package com.aicodemax.tools.editor

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult

/** Gateway executor: open/set/save/close. */
class EditorToolExecutor(private val editor: EditorPort) : ToolExecutor {
    override val toolId: String = "editor"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> {
        val path = call.args["path"] ?: return done(ok = false, error = "missing arg: path")
        return when (call.action) {
            "open" -> editor.open(path).fold(
                onSuccess = { done(true, "opened $path (${it.content.length} chars, dirty=${it.dirty})") },
                onFailure = { done(false, error = it.message) },
            )
            "set" -> {
                val content = call.args["content"] ?: ""
                editor.setContent(path, content).fold(
                    onSuccess = { done(true, "buffer updated $path (${it.content.length} chars)") },
                    onFailure = { done(false, error = it.message) },
                )
            }
            "save" -> editor.save(path).fold(
                onSuccess = { done(true, "saved $path ($it bytes)") },
                onFailure = { done(false, error = it.message) },
            )
            "close" -> {
                editor.close(path)
                done(true, "closed $path")
            }
            else -> done(false, error = "unknown action '${call.action}'")
        }
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
