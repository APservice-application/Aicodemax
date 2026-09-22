package com.aicodemax.tools.video

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Video engine: MP4 probe + thumbnail + stream-copy trim + audio extraction. */
fun videoDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "video",
    displayName = "Video",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (ตัดวิดีโอ:/ดึงเสียง:)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "VideoPort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "info/thumbnail/trim/extractAudio"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "pure MP4 probe + MediaMetadataRetriever/Extractor/Muxer (HW paths, no re-encode)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "output duration only"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no undo yet"),
    ),
)
