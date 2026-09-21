package com.aicodemax.tools.git

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.registry.ToolDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

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

    override fun merge(repoDir: String, branch: String): Outcome<GitMergeResult> =
        withGit(repoDir, "GIT_MERGE") { git ->
            check(branch.isNotBlank()) { "branch name is blank" }
            val ref = git.repository.resolve(branch.trim())
                ?: throw IllegalArgumentException("branch not found: '$branch'")
            val result = git.merge().include(ref).call()
            val status = result.mergeStatus.name
            val conflicts = result.conflicts?.keys?.sorted().orEmpty()
            GitMergeResult(
                merged = result.mergeStatus.isSuccessful,
                status = status,
                conflicts = conflicts,
            )
        }

    override fun conflicts(repoDir: String): Outcome<List<String>> =
        withGit(repoDir, "GIT_STATUS") { git ->
            git.status().call().conflicting.sorted()
        }

    override fun push(repoDir: String, remote: String, credentials: GitCredentials?): Outcome<PushSummary> =
        withGit(repoDir, "GIT_PUSH") { git ->
            val command = git.push().setRemote(remote)
            if (credentials != null) {
                command.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(credentials.username, credentials.secret),
                )
            }
            val results = command.call()
            val pushed = mutableListOf<String>()
            val messages = StringBuilder()
            for (result in results) {
                messages.append(result.messages)
                for (update in result.remoteUpdates) {
                    if (update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK) {
                        pushed.add(update.remoteName)
                    } else {
                        messages.append(update.remoteName).append(": ").append(update.status.name).append("; ")
                    }
                }
            }
            if (pushed.isEmpty()) throw IllegalStateException(messages.toString().ifBlank { "push rejected" })
            PushSummary(remote, pushed.sorted(), messages.toString().trim())
        }

    override fun pull(repoDir: String, remote: String, credentials: GitCredentials?): Outcome<PullSummary> =
        withGit(repoDir, "GIT_PULL") { git ->
            val command = git.pull().setRemote(remote)
            if (credentials != null) {
                command.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(credentials.username, credentials.secret),
                )
            }
            val result = command.call()
            val mergeResult = result.mergeResult
            PullSummary(
                remote = remote,
                successful = result.isSuccessful,
                merged = mergeResult?.mergeStatus?.isSuccessful == true,
                conflicts = mergeResult?.conflicts?.keys?.sorted().orEmpty(),
            )
        }

    override fun clone(url: String, destDir: String, credentials: GitCredentials?): Outcome<Unit> =
        runOutcome("GIT_CLONE") {
            check(url.isNotBlank()) { "clone url is blank" }
            val dest = File(destDir)
            check(!dest.exists() || dest.list()?.isEmpty() == true) { "destination not empty: '$destDir'" }
            val command = Git.cloneRepository().setURI(url).setDirectory(dest)
            if (credentials != null) {
                command.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(credentials.username, credentials.secret),
                )
            }
            command.call().use { }
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
