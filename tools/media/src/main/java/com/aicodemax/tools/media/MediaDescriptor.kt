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
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.AVAILABLE, "Timeline screen + chat"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "MediaProjectPort"),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "project.*/asset.*/timeline.get/addClip/split/trim/move/delete/duplicate/transform/freeze/markers/trackFlags + version.* + edit.undo/redo + checkpoint.*",
        ),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "file stores (project/assets/versions) + engine probes",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "timeline validation only"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.AVAILABLE, "undo/redo + transactions + checkpoints + trash + versions"),
    ),
)
