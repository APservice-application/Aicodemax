package com.aicodemax.tools.terminal

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Honest capability snapshot of the terminal tool *today* (100% contract). */
fun terminalDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "terminal",
    displayName = "Terminal",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.MISSING, "Workspace UI wires in Phase 24"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.MISSING, "Session controller wires in Phase 16"),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "TerminalPort + AIControlAPI contracts",
        ),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.MISSING, "Termux submodule wires in Phase 16"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.MISSING, "no runtime yet"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL,
            "exit-code policy defined, no runtime to verify yet",
        ),
        LayerCapability(
            CapabilityLayer.RECOVERY, CapabilityStatus.MISSING,
            "retry policy lives in TaskEngine; runtime recovery in Phase 16",
        ),
    ),
    permissions = listOf("android.permission.INTERNET"),
)
