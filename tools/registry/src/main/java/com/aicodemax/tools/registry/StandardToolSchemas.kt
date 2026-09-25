package com.aicodemax.tools.registry

/**
 * CP-147 (spec แก้ai §3–§4 + §29): the AI's application knowledge as data.
 * Schemas for every browser/file/code/git tool the AI may call. `boundTo`
 * names the REAL execution route; tools without one are declared (so the AI
 * knows they exist) but NOT runnable — never a fake success.
 */
object StandardToolSchemas {
    private fun p(name: String, desc: String, required: Boolean = false, type: String = "string"): ToolParam =
        ToolParam(name, type, desc, required)

    private fun boundLayers(): List<LayerCapability> = listOf(
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "schema registered"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE, "executor bound"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "executor bound"),
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.PARTIAL, "via gateway/tool loop"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.PARTIAL, "via gateway/tool loop"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "caller verifies result"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.PARTIAL, "caller retries"),
    )

    private fun unboundLayers(reason: String): List<LayerCapability> = listOf(
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "schema registered"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.MISSING, reason),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.MISSING, reason),
    )

    private fun tool(
        id: String,
        display: String,
        desc: String,
        params: List<ToolParam>,
        returns: String,
        errors: List<String>,
        boundTo: String?,
        unboundReason: String = "executor not yet bound",
    ): ToolDescriptor {
        val schema = ToolSchema(id, desc, params, returns, errors, boundTo = boundTo)
        return ToolDescriptor(
            toolId = id,
            displayName = display,
            version = "1.0",
            layers = if (boundTo != null) boundLayers() else unboundLayers(unboundReason),
            schema = schema,
        )
    }

    // ------------------------------------------------------------- browser
    fun browserTools(): List<ToolDescriptor> = listOf(
        tool("browser.new_tab", "New tab", "Create a browser tab and open a URL in it.",
            listOf(p("url", "URL to open (validated + normalized)", true), p("name", "Tab name shown in UI"), p("note", "Note attached to the tab")),
            "{ok, tabId, url}", listOf("INVALID_URL", "TAB_LIMIT"), "browser:open"),
        tool("browser.close_tab", "Close tab", "Close a tab by id.",
            listOf(p("tabId", "Tab id", true)), "{ok}", listOf("TAB_UNKNOWN"), "browser:close"),
        tool("browser.switch_tab", "Switch tab", "Make a tab the active one.",
            listOf(p("tabId", "Tab id", true)), "{ok, tabId}", listOf("TAB_UNKNOWN"), null,
            "active-tab sync with the UI WebView pending"),
        tool("browser.get_tabs", "List tabs", "List all open tabs (id, url, title, name).",
            emptyList(), "{tabs[]}", emptyList(), "browser:list"),
        tool("browser.get_current_tab", "Current tab", "Most-recently-opened tab (id, url, title, name, note).",
            emptyList(), "{ok, tabId, url, title}", listOf("NO_TABS"), "browser:current"),
        tool("browser.open_url", "Open URL", "Navigate a tab to a URL (validated + normalized). Uses the given tab, else the current tab, else opens a new tab.",
            listOf(p("url", "URL to open", true), p("tabId", "Tab id (optional)")),
            "{ok, tabId, url}", listOf("INVALID_URL", "TAB_UNKNOWN"), "browser:navigate"),
        tool("browser.search", "Web search", "Search the web with the configured engine and open results.",
            listOf(p("query", "Search query", true), p("engine", "google|bing|duckduckgo|brave")),
            "{ok, tabId, url}", listOf("EMPTY_QUERY"), "browser:search"),
        tool("browser.back", "Back", "Navigate back in tab history.",
            listOf(p("tabId", "Tab id")), "{ok, url}", listOf("NO_HISTORY"), null, "WebView navigation bridge pending"),
        tool("browser.forward", "Forward", "Navigate forward in tab history.",
            listOf(p("tabId", "Tab id")), "{ok, url}", listOf("NO_HISTORY"), null, "WebView navigation bridge pending"),
        tool("browser.reload", "Reload", "Reload the current page.",
            listOf(p("tabId", "Tab id")), "{ok, url}", emptyList(), null, "WebView navigation bridge pending"),
        tool("browser.click", "Click element", "Click a page element by CSS selector. Page must be visible.",
            listOf(p("selector", "CSS selector", true), p("tabId", "Tab id")),
            "{ok, detail}", listOf("ELEMENT_NOT_FOUND", "BROWSER_NEEDS_WEBVIEW"), "browser:click"),
        tool("browser.type", "Type into element", "Type text into a page element by CSS selector. Page must be visible.",
            listOf(p("selector", "CSS selector", true), p("text", "Text to type", true), p("tabId", "Tab id")),
            "{ok, detail}", listOf("ELEMENT_NOT_FOUND", "BROWSER_NEEDS_WEBVIEW"), "browser:type"),
        tool("browser.scroll", "Scroll page", "Scroll the page (direction + amount).",
            listOf(p("direction", "up|down|top|bottom", true), p("amount", "CSS px"), p("tabId", "Tab id")),
            "{ok}", listOf("BROWSER_NEEDS_WEBVIEW"), null, "scroll bridge pending"),
        tool("browser.select", "Select option", "Choose an option in a select element.",
            listOf(p("selector", "CSS selector", true), p("value", "Option value", true), p("tabId", "Tab id")),
            "{ok}", listOf("ELEMENT_NOT_FOUND"), null, "select bridge pending"),
        tool("browser.press_key", "Press key", "Press a key (Enter, Tab, Escape, ...) on the page.",
            listOf(p("key", "Key name", true), p("tabId", "Tab id")),
            "{ok}", listOf("BAD_KEY"), null, "key-event bridge pending"),
        tool("browser.find", "Find in page", "Find text matches on the page.",
            listOf(p("query", "Text to find", true), p("tabId", "Tab id")),
            "{matches, index}", emptyList(), null, "find-in-page bridge pending"),
        tool("browser.get_text", "Page text", "Readable text of the current page. Page must be visible.",
            listOf(p("tabId", "Tab id")), "{text}", listOf("BROWSER_NEEDS_WEBVIEW"), "browser:read"),
        tool("browser.get_links", "Page links", "Links on the current page (text + href).",
            listOf(p("tabId", "Tab id")), "{links[]}", listOf("BROWSER_NEEDS_WEBVIEW"), null, "link extraction pending"),
        tool("browser.get_page_info", "Page info", "URL, title, loading state and login state of a tab.",
            listOf(p("tabId", "Tab id")), "{id, url, title, loading, login}", listOf("NO_TABS"), "browser:info"),
        tool("browser.screenshot", "Screenshot", "Capture the visible page as an image file.",
            listOf(p("tabId", "Tab id")), "{path, bytes}", listOf("BROWSER_NEEDS_WEBVIEW"), null, "capture bridge pending"),
        tool("browser.download", "Download file", "Download a file URL to workspace downloads.",
            listOf(p("url", "File URL", true)), "{path, bytes}", listOf("INVALID_URL", "NETWORK_ERROR"), null, "download bridge pending"),
        tool("browser.upload", "Upload file", "Attach a local file to a page file input.",
            listOf(p("selector", "File input selector", true), p("path", "Local file path", true), p("tabId", "Tab id")),
            "{ok}", listOf("ELEMENT_NOT_FOUND", "FILE_MISSING"), null, "upload bridge pending"),
    )

    // ---------------------------------------------------------------- file
    fun fileTools(): List<ToolDescriptor> = listOf(
        tool("file.read", "Read file", "Read a workspace file.",
            listOf(p("path", "Workspace-relative path", true)), "{content}", listOf("FILE_MISSING", "PATH_ESCAPE"), "capability:files.read"),
        tool("file.write", "Write file", "Write a workspace file (creates parents).",
            listOf(p("path", "Workspace-relative path", true), p("content", "Content", true)), "{bytes}", listOf("PATH_ESCAPE"), "capability:files.write"),
        tool("file.edit", "Edit file", "Patch part of a file.",
            listOf(p("path", "Workspace-relative path", true), p("patch", "Patch spec", true)), "{ok}", listOf("FILE_MISSING", "PATCH_FAILED"), "capability:editor.patch"),
        tool("file.search", "Search files", "Search text across workspace files.",
            listOf(p("query", "Text query", true), p("path", "Folder scope")), "{matches[]}", emptyList(), "capability:files.search"),
        tool("file.list", "List files", "List a workspace folder.",
            listOf(p("path", "Folder path")), "{entries[]}", listOf("PATH_ESCAPE"), "capability:files.list"),
    )

    // ---------------------------------------------------------------- code
    fun codeTools(): List<ToolDescriptor> = listOf(
        tool("code.read", "Read code", "Read a source file.",
            listOf(p("path", "Workspace-relative path", true)), "{content}", listOf("FILE_MISSING"), "capability:files.read"),
        tool("code.write", "Write code", "Write a source file.",
            listOf(p("path", "Workspace-relative path", true), p("content", "Content", true)), "{bytes}", listOf("PATH_ESCAPE"), "capability:files.write"),
        tool("code.edit", "Edit code", "Patch a source file.",
            listOf(p("path", "Workspace-relative path", true), p("patch", "Patch spec", true)), "{ok}", listOf("PATCH_FAILED"), "capability:editor.patch"),
        tool("code.search", "Search code", "Search text across source files.",
            listOf(p("query", "Text query", true), p("path", "Folder scope")), "{matches[]}", emptyList(), "capability:files.search"),
        tool("code.build", "Build", "Build the project.",
            listOf(p("target", "Build target")), "{ok, log}", listOf("BUILD_FAILED"), null, "no build capability binding"),
        tool("code.test", "Test", "Run project tests.",
            listOf(p("target", "Test target")), "{passed, failed}", listOf("TESTS_FAILED"), null, "no test capability binding"),
    )

    // ----------------------------------------------------------------- git
    fun gitTools(): List<ToolDescriptor> = listOf(
        tool("git.status", "Git status", "Working-tree status of a repo.",
            listOf(p("path", "Repo path")), "{branch, staged, dirty[]}", listOf("NOT_A_REPO"), "capability:git.status"),
        tool("git.diff", "Git diff", "Diff of working tree / staged / commit range.",
            listOf(p("path", "Repo path"), p("ref", "Base ref or range")), "{diff}", listOf("NOT_A_REPO"), "capability:git.diff"),
        tool("git.commit", "Git commit", "Commit staged changes (AI must state what it commits).",
            listOf(p("path", "Repo path"), p("message", "Commit message", true)), "{sha}", listOf("NOTHING_STAGED", "NOT_A_REPO"), "capability:git.commit"),
    )

    fun allTools(): List<ToolDescriptor> = browserTools() + fileTools() + codeTools() + gitTools()
}
