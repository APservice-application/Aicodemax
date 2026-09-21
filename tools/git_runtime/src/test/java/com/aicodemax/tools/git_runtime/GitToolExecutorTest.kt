package com.aicodemax.tools.git_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.git.GitBranch
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
    var branches = mutableListOf(GitBranch("main", true))
    override fun branches(repoDir: String): Outcome<List<GitBranch>> = Outcome.Success(branches.toList())
    override fun createBranch(repoDir: String, name: String, checkout: Boolean): Outcome<GitBranch> {
        branches.replaceAll { it.copy(current = false) }
        val branch = GitBranch(name, checkout)
        branches.add(branch)
        return Outcome.Success(branch)
    }
    override fun checkout(repoDir: String, name: String): Outcome<GitBranch> {
        branches.replaceAll { it.copy(current = it.name == name) }
        return Outcome.Success(GitBranch(name, true))
    }
    override fun diff(repoDir: String, maxChars: Int): Outcome<String> = Outcome.Success("@@ fake diff")
    override fun stash(repoDir: String, message: String): Outcome<String> = Outcome.Success("stash@{0}")
    override fun stashPop(repoDir: String): Outcome<Unit> = Outcome.Success(Unit)
}

class GitToolExecutorTest {
    private fun run(call: ToolCall, port: FakeGitPort = FakeGitPort()): ToolResult {
        val executor = GitToolExecutor(port)
        return runBlocking { executor.execute(call) as Outcome.Success<ToolResult> }.value
    }

    @Test
    fun branchDiffStashActions() {
        val port = FakeGitPort()
        val listed = run(ToolCall("c1", "git", "branch", mapOf("repo" to "/r")), port)
        assertTrue(listed.ok)
        assertTrue(listed.output.contains("* main"))
        val created = run(ToolCall("c2", "git", "branch", mapOf("repo" to "/r", "name" to "feat")), port)
        assertTrue(created.ok)
        val diff = run(ToolCall("c3", "git", "diff", mapOf("repo" to "/r")), port)
        assertTrue(diff.output.contains("@@"))
        val stashed = run(ToolCall("c4", "git", "stash", mapOf("repo" to "/r")), port)
        assertTrue(stashed.ok)
        val popped = run(ToolCall("c5", "git", "stash-pop", mapOf("repo" to "/r")), port)
        assertTrue(popped.ok)
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
    fun blankRepoFallsBackToDefault() {
        val port = FakeGitPort()
        val executor = GitToolExecutor(port, "/default")
        val result = runBlocking { executor.execute(ToolCall("c1", "git", "ensure")) as Outcome.Success<ToolResult> }.value
        assertTrue(result.ok)
        assertEquals("/default", port.ensured)
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
