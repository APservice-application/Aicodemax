package com.aicodemax.tools.git

import com.aicodemax.core.common.Outcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JGitGitPortTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun initStageCommitLogFlow() {
        val dir = tmp.newFolder("repo")
        val git: GitPort = JGitGitPort()

        assertTrue(git.ensureRepo(dir.absolutePath) is Outcome.Success)
        // Idempotent: second call is a no-op success.
        assertTrue(git.ensureRepo(dir.absolutePath) is Outcome.Success)

        File(dir, "a.txt").writeText("hello")
        val dirty = (git.status(dir.absolutePath) as Outcome.Success<GitStatus>).value
        assertFalse(dirty.clean)
        assertTrue(dirty.changedFiles.contains("a.txt"))

        assertTrue(git.stageAll(dir.absolutePath) is Outcome.Success)
        val commit = (git.commit(dir.absolutePath, "first") as Outcome.Success<GitCommit>).value
        assertEquals("first", commit.message)
        assertEquals("Aicodemax", commit.author)

        val clean = (git.status(dir.absolutePath) as Outcome.Success<GitStatus>).value
        assertTrue(clean.clean)

        val log = (git.log(dir.absolutePath) as Outcome.Success<List<GitCommit>>).value
        assertEquals(1, log.size)
        assertEquals(commit.id, log[0].id)
    }

    @Test
    fun branchDiffStashFlow() {
        val dir = tmp.newFolder("repo")
        val git: GitPort = JGitGitPort()
        git.ensureRepo(dir.absolutePath)
        File(dir, "a.txt").writeText("v1\n")
        git.stageAll(dir.absolutePath)
        git.commit(dir.absolutePath, "first")

        val created = (git.createBranch(dir.absolutePath, "feat") as Outcome.Success<GitBranch>).value
        assertEquals("feat", created.name)
        assertTrue(created.current)
        val branches = (git.branches(dir.absolutePath) as Outcome.Success<List<GitBranch>>).value
        assertEquals(2, branches.size)

        val back = (git.checkout(dir.absolutePath, branches.first { !it.current }.name)
            as Outcome.Success<GitBranch>).value
        assertTrue(back.current)

        File(dir, "a.txt").writeText("v2\n")
        val diff = (git.diff(dir.absolutePath) as Outcome.Success<String>).value
        assertTrue(diff.contains("@@"))
        assertTrue(diff.contains("v2"))

        val stashId = (git.stash(dir.absolutePath, "wip") as Outcome.Success<String>).value
        assertTrue(stashId.isNotBlank())
        assertTrue((git.status(dir.absolutePath) as Outcome.Success<GitStatus>).value.clean)
        assertEquals("(clean)", (git.diff(dir.absolutePath) as Outcome.Success<String>).value)

        assertTrue(git.stashPop(dir.absolutePath) is Outcome.Success)
        assertEquals("v2\n", File(dir, "a.txt").readText())
    }

    @Test
    fun nonRepoFailsHonestly() {
        val dir = tmp.newFolder("plain")
        val git: GitPort = JGitGitPort()
        val result = git.status(dir.absolutePath)
        assertTrue(result is Outcome.Failure)
    }
}
