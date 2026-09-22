package com.aicodemax.tools.subtitle

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Subtitle engine: SRT make/parse/shift + burn-in transcode. */
fun subtitleDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "subtitle",
    displayName = "Subtitle",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (ทำซับ:/ฝังซับ:)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "SubtitlePort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "make/parse/shift/burn"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "pure SRT ops + MediaCodec burn-in (decode → Canvas overlay → AVC, ≤720p)",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "cue count + output duration"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no undo yet"),
    ),
)
