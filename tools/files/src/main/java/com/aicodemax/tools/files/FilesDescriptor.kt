package com.aicodemax.tools.files

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** First genuinely runnable tool: real runtime on app-scoped storage. */
fun filesDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "files",
    displayName = "Files",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(
            CapabilityLayer.UI, CapabilityStatus.PARTIAL,
            "basic browser in Projects tab; full File Center in Phase 24",
        ),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "FilePort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "FilePort"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "SandboxFileStore (app-scoped, traversal-guarded)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE,
            "write read-back verify + size caps",
        ),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL, "no trash/restore yet"),
    ),
)
