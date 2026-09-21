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
    version = "0.2.0",
    layers = listOf(
        LayerCapability(
            CapabilityLayer.UI, CapabilityStatus.PARTIAL,
            "Browser Center: tabs + WebView (history resets on tab switch)",
        ),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "BrowserPort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "BrowserPort"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE, "WebView + InMemoryBrowserPort"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "page title read-back only"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no session restore yet"),
    ),
    permissions = listOf("android.permission.INTERNET"),
)
