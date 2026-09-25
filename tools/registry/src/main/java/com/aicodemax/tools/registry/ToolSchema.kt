package com.aicodemax.tools.registry

/**
 * CP-147 (spec แก้ai §3–§4): every tool the AI may call has a real schema.
 * The AI must NEVER guess tools — it queries the registry and reads these.
 */
data class ToolParam(
    val name: String,
    /** `string` | `int` | `bool` | `json`. */
    val type: String,
    val description: String,
    val required: Boolean = false,
    val default: String? = null,
)

data class ToolSchema(
    /** Spec-style id, e.g. `browser.open_url`. */
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    /** JSON-ish shape of the success result, e.g. `{ok, tabId, url}`. */
    val returns: String = "{ok}",
    val errors: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val timeoutMs: Long = 30_000L,
    val maxRetries: Int = 1,
    /**
     * Where this tool actually executes (`browser:navigate` action,
     * `capability:files.read`, ...). Null = declared but NOT bound —
     * the descriptor layers stay MISSING and the tool is not runnable.
     */
    val boundTo: String? = null,
) {
    fun requiredParams(): List<ToolParam> = params.filter { it.required }

    fun isBound(): Boolean = boundTo != null

    /** Compact rendering for prompts (`ToolPromptBuilder`-style sections). */
    fun render(): String = buildString {
        append(name).append(" — ").append(description)
        val req = requiredParams().joinToString(", ") { it.name }
        append(" [args: ")
        append(params.joinToString(", ") { it.name + if (it.required) "!" else "?" })
        append("; required: ").append(req.ifBlank { "(none)" })
        append("; returns: ").append(returns)
        if (errors.isNotEmpty()) append("; errors: ").append(errors.joinToString(","))
        if (!isBound()) append("; UNBOUND")
        append("]")
    }
}
