package com.aicodemax.data.memory

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Memory engine: scoped key-value recall for the AI (global/project/task). */
fun memoryDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "memory",
    displayName = "Memory",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (บันทึก:/ความจำ)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "MemoryEngine"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "save/recall"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "FileMemoryStore (app-scoped JSON)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE, "read-back"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL, "forget only"),
    ),
)
