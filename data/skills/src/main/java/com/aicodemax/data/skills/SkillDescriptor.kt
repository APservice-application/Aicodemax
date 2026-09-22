package com.aicodemax.data.skills

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Skills: text knowledge files injected into AI context (AMENDMENT-002 revenue path). */
fun skillDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "skill",
    displayName = "Skills",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "Skills screen: list/import/view/delete"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "FileSkillStore"),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "install/list/get/inject/remove",
        ),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "FileSkillStore (app-scoped, 2MB/file, zip-slip guarded)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE, "read-back + text check"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL, "re-install only"),
    ),
)
