package com.aicodemax.tools.git_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.git.GitCommit
import com.aicodemax.tools.git.GitPort
import com.aicodemax.tools.git.GitStatus
import com.aicodemax.tools.git.gitDescriptorToday
import com.aicodemax.tools.registry.ToolDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeGitPort(var status: GitStatus = GitStatus("main", true)) : GitPort {
    var ensured: String? = null
    var commits = mutableListOf<String>()
    override fun descriptor(): ToolDescriptor = gitDescriptorToday()
    override fun ensureRepo(repoDir: String): Outcome<Unit> {
        ensured = repoDir
        return Outcome.Success(Unit)
    }
    override fun status(repoDir: String): Outcome<GitStatus> = Outcome.Success(status)
    override fun log(repoDir: String, limit: Int): Outcome<List<GitCommit>> =
        Outcome.Success(commits.map { GitCommit("id$it", it, "t", 1L) })
    override fun stageAll(repoDir: String): Outcome<Unit> = Outcome.Success(Unit)
    override fun commit(repoDir: String, message: String): Outcome<GitCommit> {
        return if (message.isBlank()) {
            Outcome.Failure(AppError("EMPTY", "empty message"))
        } else {
            commits.add(message)
            Outcome.Success(GitCommit("id$message", message, "t", 1L))
        }
    }
}

class GitToolExecutorTest {
    private fun run(call: ToolCall, port: FakeGitPort = FakeGitPort()): ToolResult {
        val executor = GitToolExecutor(port)
        return runBlocking { executor.execute(call) as Outcome.Success<ToolResult> }.value
    }

    @Test
    fun statusShowsBranchAndClean() {
        val result = run(ToolCall("c1", "git", "status", mapOf("repo" to "/r")))
        assertTrue(result.ok)
        assertTrue(result.output.contains("main"))
        assertTrue(result.output.contains("clean=true"))
    }

    @Test
    fun ensureCommitLogRoundtrip() {
        val port = FakeGitPort()
        val ensured = run(ToolCall("c0", "git", "ensure", mapOf("repo" to "/r")), port)
        assertTrue(ensured.ok)
        assertEquals("/r", port.ensured)
        val committed = run(ToolCall("c1", "git", "commit", mapOf("repo" to "/r", "message" to "hi")), port)
        assertTrue(committed.ok)
        val log = run(ToolCall("c2", "git", "log", mapOf("repo" to "/r")), port)
        assertTrue(log.output.contains("hi"))
    }

    @Test
    fun missingArgsFailHonestly() {
        val noRepo = run(ToolCall("c1", "git", "status"))
        assertFalse(noRepo.ok)
        assertTrue(noRepo.error.contains("repo"))

        val unknown = run(ToolCall("c2", "git", "push", mapOf("repo" to "/r")))
        assertFalse(unknown.ok)
        assertTrue(unknown.error.contains("unknown action"))
    }
}
