package com.aicodemax.tools.registry

/** The 7 mandatory layers from TOOL 100% CAPABILITY CONTRACT (§4). */
enum class CapabilityLayer {
    UI,
    CONTROLLER,
    CAPABILITY_API,
    RUNTIME,
    EXECUTION,
    VERIFICATION,
    RECOVERY,
}

enum class CapabilityStatus { AVAILABLE, PARTIAL, MISSING }

data class LayerCapability(
    val layer: CapabilityLayer,
    val status: CapabilityStatus,
    val reason: String = "",
)

data class ToolDescriptor(
    val toolId: String,
    val displayName: String,
    val version: String,
    val layers: List<LayerCapability>,
    val permissions: List<String> = emptyList(),
    /** CP-147: real schema (§4) — null only for legacy descriptors. */
    val schema: ToolSchema? = null,
) {
    fun layerStatus(layer: CapabilityLayer): CapabilityStatus =
        layers.firstOrNull { it.layer == layer }?.status ?: CapabilityStatus.MISSING

    /** A tool may run only when its execution path is real (no fake tools). */
    fun isRunnable(): Boolean =
        layerStatus(CapabilityLayer.RUNTIME) == CapabilityStatus.AVAILABLE &&
            layerStatus(CapabilityLayer.EXECUTION) == CapabilityStatus.AVAILABLE

    fun missingReasons(): List<String> =
        layers.filter { it.status == CapabilityStatus.MISSING }
            .map { "${it.layer}: ${it.reason.ifBlank { "not implemented" }}" }
}
