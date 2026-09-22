package com.aicodemax.tools.audio

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Audio engine: probe (WAV/FLAC/M4A/MP3/OGG) + edit (trim/concat/gain/fade, WAV out). */
fun audioDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "audio",
    displayName = "Audio",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (ตัดเสียง:/เร่งเสียง:)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "AudioPort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "info/trim/concat/gain/fade"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "pure WAV pipeline + MediaCodec decode (MP3/M4A/OGG/FLAC) on Android",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "output duration only"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no undo yet"),
    ),
)
