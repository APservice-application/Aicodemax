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

/** Port to Git runtime (JGit/CLI wires in Phase 18). */
interface GitPort {
    fun descriptor(): ToolDescriptor
    fun status(repoDir: String): Outcome<GitStatus>
    fun log(repoDir: String, limit: Int = 20): Outcome<List<GitCommit>>
    fun stageAll(repoDir: String): Outcome<Unit>
    fun commit(repoDir: String, message: String): Outcome<GitCommit>
}

/** Honest capability snapshot of the git tool *today* (100% contract). */
fun gitDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "git",
    displayName = "Git",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.MISSING, "Git Center UI wires in Phase 24"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.MISSING, "wires in Phase 18"),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "GitPort contract",
        ),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.MISSING, "JGit/CLI wires in Phase 18"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.MISSING, "no runtime yet"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.MISSING, "diff/status checks with runtime"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "stash/rollback with runtime"),
    ),
)
