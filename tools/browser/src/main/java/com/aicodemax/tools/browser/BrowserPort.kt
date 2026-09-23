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

/** Browser runtime port. Page automation (CP-115) runs on the visible WebView (Android). */
interface BrowserPort {
    fun descriptor(): ToolDescriptor
    suspend fun openTab(url: String): Outcome<BrowserTab>
    suspend fun closeTab(tabId: String): Outcome<Unit>
    suspend fun listTabs(): Outcome<List<BrowserTab>>
    suspend fun navigate(tabId: String, url: String): Outcome<BrowserTab>
    /** Read visible page text (read-only, capped). Fails honestly without a WebView. */
    suspend fun pageText(tabId: String): Outcome<String>
    /** Click first element matching CSS [selector] on the visible page. */
    suspend fun click(tabId: String, selector: String): Outcome<String>
    /** Type [text] into first element matching CSS [selector] (password fields refused). */
    suspend fun typeText(tabId: String, selector: String, text: String): Outcome<String>
    /** Read-only login-state probe (boolean only — old-app WebAI pattern, §166). */
    suspend fun probeLogin(tabId: String): Outcome<Boolean>
}

fun browserDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "browser",
    displayName = "Browser",
    version = "0.3.0",
    layers = listOf(
        LayerCapability(
            CapabilityLayer.UI, CapabilityStatus.PARTIAL,
            "Browser Center: tabs + WebView (history resets on tab switch)",
        ),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "BrowserPort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "BrowserPort"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE, "WebView + InMemoryBrowserPort"),
        LayerCapability(
            CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE,
            "via ToolGateway incl. read/click/type/probe on the visible page (CP-115, password fields refused)",
        ),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.AVAILABLE,
            "page text read-back + CLICKED/TYPED receipts",
        ),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no session restore yet"),
    ),
    permissions = listOf("android.permission.INTERNET"),
)
