package com.aicodemax.tools.skill_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.skills.SkillInjector
import com.aicodemax.data.skills.SkillStore
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for skills (actions: list/get/install/inject/remove). */
class SkillToolExecutor(
    private val skills: SkillStore,
    private val files: FilePort,
) : ToolExecutor {
    override val toolId: String = "skill"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "list" -> skills.list().fold(
                    onSuccess = { metas ->
                        val query = call.args["query"]?.trim().orEmpty()
                        val shown = if (query.isBlank()) metas
                        else metas.filter { "${it.id} ${it.category}".contains(query, ignoreCase = true) }
                        done(true, shown.joinToString("\n") { "${it.id} [${it.category}]" }
                            .ifBlank { "(no skills)" })
                    },
                    onFailure = { done(false, error = it.message) },
                )
                "get" -> {
                    val id = call.args["id"]
                        ?: return@withContext done(false, error = "missing arg: id")
                    skills.get(id).fold(
                        onSuccess = { done(true, it.content.take(4000)) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "install" -> {
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    installFromWorkspace(path)
                }
                "inject" -> {
                    val ids = call.args["ids"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: return@withContext done(false, error = "missing arg: ids")
                    SkillInjector.injectIds(ids, skills).fold(
                        onSuccess = { done(true, it.take(8000)) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "remove" -> {
                    val id = call.args["id"]
                        ?: return@withContext done(false, error = "missing arg: id")
                    skills.remove(id).fold(
                        onSuccess = { done(true, "removed $id") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: list/get/install/inject/remove)")
            }
        }

    private suspend fun installFromWorkspace(path: String): Outcome<ToolResult> {
        val ext = File(path).extension.lowercase()
        if (ext == "zip") {
            // Workspace reads are text-only; binary .zip import goes through the Skills UI.
            return Outcome.Success(
                ToolResult(ok = false, error = "import .zip ผ่านหน้า Skills ครับ (gateway รับไฟล์ข้อความเท่านั้น)"),
            )
        }
        return when (val read = files.read(path)) {
            is Outcome.Failure -> done(false, error = "cannot read '$path': ${read.error.message}")
            is Outcome.Success -> {
                val staging = createTempDir("skill-import-")
                try {
                    val named = File(staging, File(path).name)
                    named.writeText(read.value)
                    skills.install(named).fold(
                        onSuccess = { metas ->
                            done(true, "installed: ${metas.joinToString(",") { it.id }}")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                } finally {
                    staging.deleteRecursively()
                }
            }
        }
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
