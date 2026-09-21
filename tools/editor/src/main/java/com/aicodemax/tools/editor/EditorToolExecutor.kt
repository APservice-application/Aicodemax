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
            "preview", "patch" -> {
                val start = call.args["startLine"]?.toIntOrNull()
                val end = call.args["endLine"]?.toIntOrNull()
                if (start == null || end == null) {
                    return done(false, error = "missing args: startLine/endLine (1-based, end exclusive)")
                }
                val replacement = call.args["replacement"] ?: ""
                editor.open(path).fold(
                    onSuccess = { buffer ->
                        PatchEngine.apply(buffer.content, listOf(EditOp(start, end, replacement))).fold(
                            onSuccess = { updated ->
                                if (call.action == "preview") {
                                    done(true, PatchEngine.previewDiff(buffer.content, updated))
                                } else {
                                    editor.setContent(path, updated).fold(
                                        onSuccess = { done(true, "patched $path (unsaved — call save)") },
                                        onFailure = { done(false, error = it.message) },
                                    )
                                }
                            },
                            onFailure = { done(false, error = it.message) },
                        )
                    },
                    onFailure = { done(false, error = it.message) },
                )
            }
            else -> done(false, error = "unknown action '${call.action}'")
        }
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
