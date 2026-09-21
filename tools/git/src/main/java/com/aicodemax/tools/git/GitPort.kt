package com.aicodemax.tools.git

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

data class GitStatus(
    val branch: String,
    val clean: Boolean,
    val ahead: Int = 0,
    val behind: Int = 0,
    val changedFiles: List<String> = emptyList(),
)

data class GitCommit(
    val id: String,
    val message: String,
    val author: String,
    val timestamp: Long,
)

data class GitBranch(
    val name: String,
    val current: Boolean,
)

/** Git runtime port. Real implementation: [JGitGitPort] (Eclipse JGit, pure Java). */
interface GitPort {
    fun descriptor(): ToolDescriptor
    /** Creates a repo at [repoDir] when missing; no-op when one already exists. */
    fun ensureRepo(repoDir: String): Outcome<Unit>
    fun status(repoDir: String): Outcome<GitStatus>
    fun log(repoDir: String, limit: Int = 20): Outcome<List<GitCommit>>
    fun stageAll(repoDir: String): Outcome<Unit>
    fun commit(repoDir: String, message: String): Outcome<GitCommit>
    fun branches(repoDir: String): Outcome<List<GitBranch>>
    fun createBranch(repoDir: String, name: String, checkout: Boolean = true): Outcome<GitBranch>
    fun checkout(repoDir: String, name: String): Outcome<GitBranch>
    /** Unified diff of workdir vs HEAD, clipped to [maxChars]. */
    fun diff(repoDir: String, maxChars: Int = 8000): Outcome<String>
    fun stash(repoDir: String, message: String = ""): Outcome<String>
    fun stashPop(repoDir: String): Outcome<Unit>
    fun merge(repoDir: String, branch: String): Outcome<GitMergeResult>
    fun conflicts(repoDir: String): Outcome<List<String>>
    fun push(repoDir: String, remote: String = "origin", credentials: GitCredentials? = null): Outcome<PushSummary>
    fun pull(repoDir: String, remote: String = "origin", credentials: GitCredentials? = null): Outcome<PullSummary>
    fun clone(url: String, destDir: String, credentials: GitCredentials? = null): Outcome<Unit>
}

/** Never logged, never persisted by the git engine — passed per call. */
data class GitCredentials(
    val username: String,
    val secret: String,
) {
    /** Redacted: a credential must never leak through logs or crash reports. */
    override fun toString(): String = "GitCredentials(username=$username, secret=***)"
}

data class GitMergeResult(
    val merged: Boolean,
    val status: String,
    val conflicts: List<String> = emptyList(),
)

data class PushSummary(
    val remote: String,
    val pushed: List<String>,
    val messages: String = "",
)

data class PullSummary(
    val remote: String,
    val successful: Boolean,
    val merged: Boolean,
    val conflicts: List<String> = emptyList(),
)

/** Honest capability snapshot of the git tool *today* (100% contract). */
fun gitDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "git",
    displayName = "Git",
    version = "0.2.0",
    layers = listOf(
        LayerCapability(
            CapabilityLayer.UI, CapabilityStatus.PARTIAL,
            "status line in Projects; full Git Center in Phase 24",
        ),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "GitPort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "GitPort"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE, "JGitGitPort (JGit)"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE,
            "status/log read-back checks",
        ),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL, "stash engine ready; no rollback UI yet"),
    ),
)
