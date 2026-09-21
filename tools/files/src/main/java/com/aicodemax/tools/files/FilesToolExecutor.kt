package com.aicodemax.tools.files

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult

/** Gateway executor: list/read/write/mkdir/delete/exists. */
class FilesToolExecutor(private val files: FilePort) : ToolExecutor {
    override val toolId: String = "files"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> {
        return when (call.action) {
            "list" -> files.list(call.args["path"] ?: "").fold(
                onSuccess = { entries ->
                    ok(entries.joinToString("\n") { (if (it.isDirectory) "DIR " else "FILE") + " ${it.path} (${it.sizeBytes}B)" }
                        .ifBlank { "(empty)" })
                },
                onFailure = { fail(it.message) },
            )
            "read" -> {
                val path = call.args["path"] ?: return okFail("missing arg: path")
                files.read(path).fold(
                    onSuccess = { ok(it) },
                    onFailure = { fail(it.message) },
                )
            }
            "write" -> {
                val path = call.args["path"] ?: return okFail("missing arg: path")
                val content = call.args["content"] ?: ""
                files.write(path, content).fold(
                    onSuccess = { ok("wrote $it bytes to $path") },
                    onFailure = { fail(it.message) },
                )
            }
            "mkdir" -> {
                val path = call.args["path"] ?: return okFail("missing arg: path")
                files.mkdir(path).fold(
                    onSuccess = { ok("created directory $path") },
                    onFailure = { fail(it.message) },
                )
            }
            "delete" -> {
                val path = call.args["path"] ?: return okFail("missing arg: path")
                files.delete(path).fold(
                    onSuccess = { ok("deleted $path") },
                    onFailure = { fail(it.message) },
                )
            }
            "exists" -> {
                val path = call.args["path"] ?: return okFail("missing arg: path")
                files.exists(path).fold(
                    onSuccess = { ok(it.toString()) },
                    onFailure = { fail(it.message) },
                )
            }
            else -> okFail("unknown action '${call.action}'")
        }
    }

    private fun ok(output: String): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = true, output = output))

    private fun fail(error: String): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = false, error = error))

    private fun okFail(error: String): Outcome<ToolResult> = fail(error)
}
