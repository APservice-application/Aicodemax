package com.aicodemax.tools.debug

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Debug engine: stack-trace triage anywhere (live debugging still needs device work). */
fun debugDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "debug",
    displayName = "Debug",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (แก้บั๊ก:)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "DebugSession"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "analyze"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "StackTraceParser + DebugSession (pure Kotlin)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL,
            "parse confidence only — no re-run",
        ),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no fix-apply yet"),
    ),
)
