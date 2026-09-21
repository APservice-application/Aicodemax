package com.aicodemax.tools.capability

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.InMemoryToolRegistry
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.registry.ToolRegistry

/**
 * The capability catalog the app actually ships today.
 * Native engines first; the terminal CLI adapter exists ONLY as fallback (CP-32).
 */
object StandardCapabilities {
    fun bindings(): List<CapabilityBinding> = listOf(
        // File engine (native).
        CapabilityBinding("files.read", "files", "read", AdapterKind.NATIVE),
        CapabilityBinding("files.list", "files", "list", AdapterKind.NATIVE),
        CapabilityBinding("files.mkdir", "files", "mkdir", AdapterKind.NATIVE),
        CapabilityBinding("files.delete", "files", "delete", AdapterKind.NATIVE),
        CapabilityBinding("files.copy", "files", "copy", AdapterKind.NATIVE),
        CapabilityBinding("files.move", "files", "move", AdapterKind.NATIVE),
        CapabilityBinding("files.search", "files", "search", AdapterKind.NATIVE),
        CapabilityBinding("files.archive", "files", "archive", AdapterKind.NATIVE),
        CapabilityBinding("files.unarchive", "files", "unarchive", AdapterKind.NATIVE),
        CapabilityBinding("files.metadata", "files", "metadata", AdapterKind.NATIVE),
        // Code engine (native).
        CapabilityBinding("editor.set", "editor", "set", AdapterKind.NATIVE),
        CapabilityBinding("editor.save", "editor", "save", AdapterKind.NATIVE),
        CapabilityBinding("editor.preview", "editor", "preview", AdapterKind.NATIVE),
        CapabilityBinding("editor.patch", "editor", "patch", AdapterKind.NATIVE),
        // Git engine (native, JGit).
        CapabilityBinding("git.ensure", "git", "ensure", AdapterKind.NATIVE),
        CapabilityBinding("git.status", "git", "status", AdapterKind.NATIVE),
        CapabilityBinding("git.log", "git", "log", AdapterKind.NATIVE),
        CapabilityBinding("git.stage", "git", "stage", AdapterKind.NATIVE),
        CapabilityBinding("git.commit", "git", "commit", AdapterKind.NATIVE),
        CapabilityBinding("git.branch", "git", "branch", AdapterKind.NATIVE),
        CapabilityBinding("git.checkout", "git", "checkout", AdapterKind.NATIVE),
        CapabilityBinding("git.diff", "git", "diff", AdapterKind.NATIVE),
        CapabilityBinding("git.stash", "git", "stash", AdapterKind.NATIVE),
        CapabilityBinding("git.stash-pop", "git", "stash-pop", AdapterKind.NATIVE),
        CapabilityBinding("git.merge", "git", "merge", AdapterKind.NATIVE),
        CapabilityBinding("git.conflicts", "git", "conflicts", AdapterKind.NATIVE),
        CapabilityBinding("git.push", "git", "push", AdapterKind.NATIVE),
        CapabilityBinding("git.pull", "git", "pull", AdapterKind.NATIVE),
        CapabilityBinding("git.clone", "git", "clone", AdapterKind.NATIVE),
        // Browser engine (native, WebView).
        CapabilityBinding("browser.open", "browser", "open", AdapterKind.NATIVE),
        CapabilityBinding("browser.close", "browser", "close", AdapterKind.NATIVE),
        CapabilityBinding("browser.list", "browser", "list", AdapterKind.NATIVE),
        CapabilityBinding("browser.navigate", "browser", "navigate", AdapterKind.NATIVE),
        // Compatibility engine — CLI adapter, LAST resort (§29, CP-32).
        CapabilityBinding("terminal.open", "terminal", "open", AdapterKind.CLI_ADAPTER),
        CapabilityBinding("terminal.exec", "terminal", "exec", AdapterKind.CLI_ADAPTER),
        CapabilityBinding("terminal.close", "terminal", "close", AdapterKind.CLI_ADAPTER),
        CapabilityBinding("terminal.sessions", "terminal", "sessions", AdapterKind.CLI_ADAPTER),
    )

    /** Resolver over a live registry (production: ServiceLocator's registry). */
    fun overRegistry(registry: ToolRegistry): CapabilityResolver =
        DefaultCapabilityResolver(registry).also { resolver ->
            bindings().forEach { resolver.register(it) }
        }

    /**
     * Standalone resolver for tests/embedders without the app registry.
     * Mirrors the tools this app build actually ships runnable.
     */
    fun defaultResolver(): CapabilityResolver {
        val registry = InMemoryToolRegistry()
        for (toolId in listOf("files", "editor", "git", "browser")) {
            registry.register(runnableDescriptor(toolId))
        }
        return overRegistry(registry)
    }

    private fun runnableDescriptor(toolId: String): ToolDescriptor = ToolDescriptor(
        toolId = toolId,
        displayName = toolId,
        version = "0.0.0-standalone",
        layers = listOf(
            LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE, "shipped in this build"),
            LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        ),
    )
}
