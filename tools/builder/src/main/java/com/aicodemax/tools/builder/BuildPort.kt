package com.aicodemax.tools.builder

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

data class BuildRequest(
    val projectDir: String,
    val variant: String = "debug",
    val tasks: List<String> = listOf("assembleDebug"),
)

data class BuildResult(
    val success: Boolean,
    val output: String,
    val errors: List<String> = emptyList(),
    val durationMs: Long = 0,
)

data class TestRunResult(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val output: String = "",
)

/** On-device build/test port. Runtime wires in Phase 17. */
interface BuildPort {
    fun descriptor(): ToolDescriptor
    suspend fun build(request: BuildRequest): Outcome<BuildResult>
    suspend fun runTests(projectDir: String): Outcome<TestRunResult>
}

fun buildDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "build",
    displayName = "Build & Test",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.MISSING, "Build Center UI in Phase 24"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.MISSING, "wires in Phase 17"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "BuildPort"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.MISSING, "on-device build in Phase 17"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.MISSING, "no runtime yet"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.MISSING, "with runtime"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "with runtime"),
    ),
)
