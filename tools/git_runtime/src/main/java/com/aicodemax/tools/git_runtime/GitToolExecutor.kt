package com.aicodemax.tools.git_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.git.GitPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for git (actions: ensure/status/log/stage/commit). */
class GitToolExecutor(
    private val git: GitPort,
    private val defaultRepo: String = "",
) : ToolExecutor {
    override val toolId: String = "git"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            val repoArg = call.args["repo"].orEmpty()
            val repo = repoArg.ifBlank { defaultRepo }
            if (repo.isBlank()) return@withContext done(false, error = "missing arg: repo")
            when (call.action) {
                "ensure" -> git.ensureRepo(repo).fold(
                    onSuccess = { done(true, "repo ready at $repo") },
                    onFailure = { done(false, error = it.message) },
                )
                "status" -> git.status(repo).fold(
                    onSuccess = { st ->
                        val files = if (st.changedFiles.isEmpty()) {
                            "(clean)"
                        } else {
                            st.changedFiles.joinToString("\n")
                        }
                        done(true, "branch ${st.branch} clean=${st.clean}\n$files")
                    },
                    onFailure = { done(false, error = it.message) },
                )
                "log" -> {
                    val limit = call.args["limit"]?.toIntOrNull() ?: 20
                    git.log(repo, limit).fold(
                        onSuccess = { commits ->
                            done(true, commits.joinToString("\n") { "${it.id.take(7)} ${it.message}" }
                                .ifBlank { "(no commits)" })
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "stage" -> git.stageAll(repo).fold(
                    onSuccess = { done(true, "staged all") },
                    onFailure = { done(false, error = it.message) },
                )
                "commit" -> {
                    val message = call.args["message"] ?: return@withContext done(false, error = "missing arg: message")
                    git.commit(repo, message).fold(
                        onSuccess = { done(true, "committed ${it.id.take(7)} ${it.message}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "branch" -> {
                    val name = call.args["name"]
                    if (name == null) {
                        git.branches(repo).fold(
                            onSuccess = { branches ->
                                done(true, branches.joinToString("\n") {
                                    (if (it.current) "* " else "  ") + it.name
                                }.ifBlank { "(no branches)" })
                            },
                            onFailure = { done(false, error = it.message) },
                        )
                    } else {
                        git.createBranch(repo, name).fold(
                            onSuccess = { done(true, "branch '${it.name}' created and checked out") },
                            onFailure = { done(false, error = it.message) },
                        )
                    }
                }
                "checkout" -> {
                    val name = call.args["name"] ?: return@withContext done(false, error = "missing arg: name")
                    git.checkout(repo, name).fold(
                        onSuccess = { done(true, "checked out '${it.name}'") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "diff" -> git.diff(repo).fold(
                    onSuccess = { done(true, it) },
                    onFailure = { done(false, error = it.message) },
                )
                "stash" -> {
                    git.stash(repo, call.args["message"].orEmpty()).fold(
                        onSuccess = { done(true, "stashed: $it") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "stash-pop" -> git.stashPop(repo).fold(
                    onSuccess = { done(true, "stash applied") },
                    onFailure = { done(false, error = it.message) },
                )
                else -> done(false, error = "unknown action '${call.action}'")
            }
        }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
