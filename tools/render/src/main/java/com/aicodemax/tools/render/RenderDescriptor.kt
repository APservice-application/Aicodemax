package com.aicodemax.tools.render

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** CP-67 render engine: queue + transcode + QC + approval + export. */
fun renderDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "render",
    displayName = "เรนเดอร์",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.AVAILABLE, "Render screen (queue/previews/approve/export)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "RenderPort"),
        LayerCapability(
            CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE,
            "enqueue/runNow/run/status/list/retry/approve/export",
        ),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "fast stream-copy path + MediaCodec concat transcode (720p cap, stills, audio mix)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE,
            "QC gate: file/duration/height/audio + 3 preview frames",
        ),
        LayerCapability(
            CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL,
            "retry failed jobs + auto-checkpoint before render/export; no mid-render cancel yet",
        ),
    ),
)
