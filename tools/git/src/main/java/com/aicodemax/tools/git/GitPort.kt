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

/** Git runtime port. Real implementation: [JGitGitPort] (Eclipse JGit, pure Java). */
interface GitPort {
    fun descriptor(): ToolDescriptor
    /** Creates a repo at [repoDir] when missing; no-op when one already exists. */
    fun ensureRepo(repoDir: String): Outcome<Unit>
    fun status(repoDir: String): Outcome<GitStatus>
    fun log(repoDir: String, limit: Int = 20): Outcome<List<GitCommit>>
    fun stageAll(repoDir: String): Outcome<Unit>
    fun commit(repoDir: String, message: String): Outcome<GitCommit>
}

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
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL, "no stash/rollback UI yet"),
    ),
)
