package com.aicodemax.tools.git

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.registry.ToolDescriptor
import java.io.ByteArrayOutputStream
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

    override fun branches(repoDir: String): Outcome<List<GitBranch>> =
        withGit(repoDir, "GIT_BRANCH") { git ->
            val current = git.repository.branch
            git.branchList().call().map { ref ->
                val name = ref.name.removePrefix("refs/heads/")
                GitBranch(name, name == current)
            }.sortedBy { it.name }
        }

    override fun createBranch(repoDir: String, name: String, checkout: Boolean): Outcome<GitBranch> =
        withGit(repoDir, "GIT_BRANCH") { git ->
            check(name.isNotBlank()) { "branch name is blank" }
            git.branchCreate().setName(name.trim()).call()
            if (checkout) git.checkout().setName(name.trim()).call()
            GitBranch(name.trim(), checkout || git.repository.branch == name.trim())
        }

    override fun checkout(repoDir: String, name: String): Outcome<GitBranch> =
        withGit(repoDir, "GIT_CHECKOUT") { git ->
            check(name.isNotBlank()) { "branch name is blank" }
            git.checkout().setName(name.trim()).call()
            GitBranch(name.trim(), true)
        }

    override fun diff(repoDir: String, maxChars: Int): Outcome<String> =
        withGit(repoDir, "GIT_DIFF") { git ->
            // NOTE: DiffCommand must format via setOutputStream — a manual
            // DiffFormatter cannot resolve workdir blobs ("Missing blob").
            val out = ByteArrayOutputStream()
            val entries = git.diff().setOutputStream(out).call()
            if (entries.isEmpty()) return@withGit "(clean)"
            val text = out.toString(Charsets.UTF_8.name())
            if (text.length > maxChars.coerceAtLeast(256)) text.take(maxChars) + "\n…[truncated]" else text
        }

    override fun stash(repoDir: String, message: String): Outcome<String> =
        withGit(repoDir, "GIT_STASH") { git ->
            val commit = git.stashCreate().setWorkingDirectoryMessage(message.ifBlank { "aicodemax stash" }).call()
            commit?.name ?: "(nothing to stash)"
        }

    override fun stashPop(repoDir: String): Outcome<Unit> =
        withGit(repoDir, "GIT_STASH") { git ->
            git.stashApply().call()
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
