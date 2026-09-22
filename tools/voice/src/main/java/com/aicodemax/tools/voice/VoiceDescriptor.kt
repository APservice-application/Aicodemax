package com.aicodemax.tools.voice

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Voice engine: STT in + TTS out (TH/EN) via Android speech APIs. */
fun voiceDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "voice",
    displayName = "Voice",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.AVAILABLE, "mic button + speak buttons in chat"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "VoicePort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "listen/speak/stop/status"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.PARTIAL,
            "SpeechRecognizer + TextToSpeech on-device; needs mic permission + speech engine",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL,
            "recognizer confidence when reported; no transcript re-check",
        ),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no re-listen ladder yet"),
    ),
)
