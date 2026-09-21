package com.aicodemax.tools.git

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.registry.ToolDescriptor
import java.io.File
import org.eclipse.jgit.api.Git

/** Real Git runtime on Eclipse JGit (pure Java — runs on JVM and Android). */
class JGitGitPort : GitPort {
    override fun descriptor(): ToolDescriptor = gitDescriptorToday()

    private fun <T> withGit(repoDir: String, code: String, block: (Git) -> T): Outcome<T> =
        runOutcome(code) {
            Git.open(File(repoDir)).use(block)
        }

    override fun ensureRepo(repoDir: String): Outcome<Unit> = runOutcome("GIT_INIT") {
        val dir = File(repoDir).apply { mkdirs() }
        if (!File(dir, ".git").exists()) {
            Git.init().setDirectory(dir).call().use { }
        }
    }

    override fun status(repoDir: String): Outcome<GitStatus> =
        withGit(repoDir, "GIT_STATUS") { git ->
            val st = git.status().call()
            GitStatus(
                branch = git.repository.branch,
                // NOTE: JGit hasUncommittedChanges() ignores untracked files — check them too.
                clean = !st.hasUncommittedChanges() && st.untracked.isEmpty(),
                changedFiles = (st.added + st.changed + st.removed + st.missing + st.modified + st.untracked)
                    .sorted(),
            )
        }

    override fun log(repoDir: String, limit: Int): Outcome<List<GitCommit>> =
        withGit(repoDir, "GIT_LOG") { git ->
            git.log().setMaxCount(limit.coerceAtLeast(1)).call().map { rc ->
                GitCommit(
                    id = rc.name,
                    message = rc.shortMessage,
                    author = rc.authorIdent?.name ?: "",
                    timestamp = rc.commitTime.toLong() * 1000,
                )
            }
        }

    override fun stageAll(repoDir: String): Outcome<Unit> =
        withGit(repoDir, "GIT_STAGE") { git ->
            git.add().addFilepattern(".").call()
            Unit
        }

    override fun commit(repoDir: String, message: String): Outcome<GitCommit> =
        withGit(repoDir, "GIT_COMMIT") { git ->
            val rc = git.commit()
                .setMessage(message)
                .setAuthor("Aicodemax", "aicodemax@local")
                .setCommitter("Aicodemax", "aicodemax@local")
                .call()
            GitCommit(
                id = rc.name,
                message = rc.shortMessage,
                author = rc.authorIdent?.name ?: "",
                timestamp = rc.commitTime.toLong() * 1000,
            )
        }
}
