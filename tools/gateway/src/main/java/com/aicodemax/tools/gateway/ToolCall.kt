package com.aicodemax.tools.gateway

data class ToolCall(
    val id: String,
    val toolId: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    /** AI | USER | SYSTEM */
    val actor: String = "AI",
    val needsPermission: Boolean = false,
)

data class ToolResult(
    val ok: Boolean,
    val output: String = "",
    val error: String = "",
    val auditId: String = "",
)
