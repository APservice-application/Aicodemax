package com.aicodemax.tools.image

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Image engine: probe (headers) + edit (resize/crop/rotate/grayscale/adjust/upscale/restore). */
fun imageDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "image",
    displayName = "Image",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via chat (ย่อรูป:/ครอปรูป:)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "ImagePort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "info/resize/crop/rotate/grayscale/scopes/adjust/upscale/restore"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "header probe (PNG/JPEG/GIF/BMP/WebP) + pixel ops; Bitmap adapter on Android",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "output dims only"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no undo yet"),
    ),
)
