package com.aicodemax.tools.browser

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
    val loading: Boolean = false,
)

/** Browser runtime port. Real engine wires in Phase 19 (see ฝ1.3 §5). */
interface BrowserPort {
    fun descriptor(): ToolDescriptor
    suspend fun openTab(url: String): Outcome<BrowserTab>
    suspend fun closeTab(tabId: String): Outcome<Unit>
    suspend fun listTabs(): Outcome<List<BrowserTab>>
    suspend fun navigate(tabId: String, url: String): Outcome<BrowserTab>
}

fun browserDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "browser",
    displayName = "Browser",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.MISSING, "Browser Center UI in Phase 24"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.MISSING, "wires in Phase 19"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "BrowserPort"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.MISSING, "browser engine in Phase 19"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.MISSING, "no runtime yet"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.MISSING, "with runtime"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "with runtime"),
    ),
    permissions = listOf("android.permission.INTERNET"),
)
