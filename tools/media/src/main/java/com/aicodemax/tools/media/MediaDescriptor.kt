package com.aicodemax.tools.media

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Media projects: assets + timeline (source of truth) + versions. */
fun mediaDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "media",
    displayName = "Media Projects",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (โปรเจกต์ใหม่:)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "MediaProjectPort"),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "project.create/list + asset.import/list + timeline.get/set + version.save/restore",
        ),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "file stores (project/assets/versions) + engine probes",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "timeline validation only"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.AVAILABLE, "version snapshots"),
    ),
)
