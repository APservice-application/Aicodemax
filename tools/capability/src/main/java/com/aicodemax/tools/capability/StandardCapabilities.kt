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
        CapabilityBinding("files.read", "files", "read", AdapterKind.NATIVE,
            metadata = meta("files", "อ่านไฟล์ใน workspace", listOf("path"), listOf("content"), emptyList(), false, "canonical-path containment", "re-read")),
        CapabilityBinding("files.list", "files", "list", AdapterKind.NATIVE,
            metadata = meta("files", "list ไฟล์ในโฟลเดอร์", listOf("path?"), listOf("entries"), emptyList(), false, "canonical-path containment", "re-list")),
        CapabilityBinding("files.mkdir", "files", "mkdir", AdapterKind.NATIVE,
            metadata = meta("files", "สร้างโฟลเดอร์", listOf("path"), listOf("ok"), listOf("fs.write"), false, "list shows dir", "retry")),
        CapabilityBinding("files.delete", "files", "delete", AdapterKind.NATIVE,
            metadata = meta("files", "ลบไฟล์/โฟลเดอร์", listOf("path"), listOf("ok"), listOf("fs.delete"), false, "path gone", "checkpoint restore")),
        CapabilityBinding("files.copy", "files", "copy", AdapterKind.NATIVE,
            metadata = meta("files", "คัดลอกไฟล์", listOf("from,to"), listOf("ok"), listOf("fs.write"), false, "target readable", "retry")),
        CapabilityBinding("files.move", "files", "move", AdapterKind.NATIVE,
            metadata = meta("files", "ย้ายไฟล์", listOf("from,to"), listOf("ok"), listOf("fs.write"), false, "target readable + source gone", "retry")),
        CapabilityBinding("files.search", "files", "search", AdapterKind.NATIVE,
            metadata = meta("files", "ค้นหาข้อความในไฟล์", listOf("query,path?"), listOf("matches"), emptyList(), false, "bounded result set", "narrow query")),
        CapabilityBinding("files.archive", "files", "archive", AdapterKind.NATIVE,
            metadata = meta("files", "บีบอัดเป็น zip", listOf("paths,out"), listOf("archive"), listOf("fs.write"), false, "zip-slip guarded write", "retry")),
        CapabilityBinding("files.unarchive", "files", "unarchive", AdapterKind.NATIVE,
            metadata = meta("files", "แตกไฟล์ zip", listOf("archive,out?"), listOf("files"), listOf("fs.write"), false, "zip-slip guarded extract", "retry")),
        CapabilityBinding("files.metadata", "files", "metadata", AdapterKind.NATIVE,
            metadata = meta("files", "ข้อมูลไฟล์", listOf("path"), listOf("size,modified"), emptyList(), false, "stat", "re-stat")),
        // Code engine (native).
        CapabilityBinding("editor.set", "editor", "set", AdapterKind.NATIVE,
            metadata = meta("editor", "เปิด/ตั้งค่าบัฟเฟอร์แก้ไข", listOf("path"), listOf("buffer"), emptyList(), false, "file exists", "re-open")),
        CapabilityBinding("editor.save", "editor", "save", AdapterKind.NATIVE,
            metadata = meta("editor", "บันทึกบัฟเฟอร์ลงไฟล์", listOf("path,content"), listOf("ok"), listOf("fs.write"), false, "read-back bytes", "re-save")),
        CapabilityBinding("editor.preview", "editor", "preview", AdapterKind.NATIVE,
            metadata = meta("editor", "ดู diff ก่อนแก้", listOf("path,startLine,endLine,replacement"), listOf("diff"), emptyList(), false, "pure (no write)", "adjust range")),
        CapabilityBinding("editor.patch", "editor", "patch", AdapterKind.NATIVE,
            metadata = meta("editor", "แก้ไฟล์แบบ patch", listOf("path,patch"), listOf("ok"), listOf("fs.write"), false, "read-back bytes", "inverse patch")),
        // Git engine (native, JGit).
        CapabilityBinding("git.ensure", "git", "ensure", AdapterKind.NATIVE,
            metadata = meta("git", "init/open repo ถ้าจำเป็น", listOf("repo"), listOf("ok"), listOf("fs.write"), false, ".git present", "retry")),
        CapabilityBinding("git.status", "git", "status", AdapterKind.NATIVE,
            metadata = meta("git", "สถานะ working tree", listOf("repo"), listOf("status"), emptyList(), false, "JGit status", "re-status")),
        CapabilityBinding("git.log", "git", "log", AdapterKind.NATIVE,
            metadata = meta("git", "ประวัติ commit", listOf("repo,limit?"), listOf("commits"), emptyList(), false, "log parse", "re-log")),
        CapabilityBinding("git.stage", "git", "stage", AdapterKind.NATIVE,
            metadata = meta("git", "stage ไฟล์", listOf("repo,paths?"), listOf("ok"), listOf("fs.write"), false, "status shows staged", "retry")),
        CapabilityBinding("git.commit", "git", "commit", AdapterKind.NATIVE,
            metadata = meta("git", "commit การเปลี่ยน", listOf("repo,message"), listOf("sha"), listOf("fs.write"), false, "log shows commit", "amend/retry")),
        CapabilityBinding("git.branch", "git", "branch", AdapterKind.NATIVE,
            metadata = meta("git", "list/สร้าง branch", listOf("repo,name?"), listOf("branches"), listOf("fs.write"), false, "branch list", "retry")),
        CapabilityBinding("git.checkout", "git", "checkout", AdapterKind.NATIVE,
            metadata = meta("git", "สลับ branch", listOf("repo,name"), listOf("ok"), listOf("fs.write"), false, "HEAD moved", "checkout back")),
        CapabilityBinding("git.diff", "git", "diff", AdapterKind.NATIVE,
            metadata = meta("git", "ดู diff", listOf("repo"), listOf("diff"), emptyList(), false, "JGit diff", "re-diff")),
        CapabilityBinding("git.stash", "git", "stash", AdapterKind.NATIVE,
            metadata = meta("git", "เก็บงานชั่วคราว", listOf("repo"), listOf("ok"), listOf("fs.write"), false, "stash list", "stash-pop")),
        CapabilityBinding("git.stash-pop", "git", "stash-pop", AdapterKind.NATIVE,
            metadata = meta("git", "คืน stash", listOf("repo"), listOf("ok"), listOf("fs.write"), false, "tree matches", "re-stash")),
        CapabilityBinding("git.merge", "git", "merge", AdapterKind.NATIVE,
            metadata = meta("git", "merge branch", listOf("repo,name"), listOf("ok|conflicts"), listOf("fs.write"), false, "conflict report", "abort + resolve")),
        CapabilityBinding("git.conflicts", "git", "conflicts", AdapterKind.NATIVE,
            metadata = meta("git", "list ไฟล์ชนกัน", listOf("repo"), listOf("paths"), emptyList(), false, "status scan", "re-scan")),
        CapabilityBinding("git.push", "git", "push", AdapterKind.NATIVE,
            metadata = meta("git", "push ขึ้น remote", listOf("repo,remote?,branch?"), listOf("ok"), listOf("fs.write","network"), true, "remote refs", "pull then push")),
        CapabilityBinding("git.pull", "git", "pull", AdapterKind.NATIVE,
            metadata = meta("git", "pull จาก remote", listOf("repo,remote?,branch?"), listOf("ok"), listOf("fs.write","network"), true, "log advanced", "resolve conflicts")),
        CapabilityBinding("git.clone", "git", "clone", AdapterKind.NATIVE,
            metadata = meta("git", "clone repo", listOf("url,dest"), listOf("ok"), listOf("fs.write","network"), true, "dir is repo", "re-clone")),
        // Browser engine (native, WebView).
        CapabilityBinding("browser.open", "browser", "open", AdapterKind.NATIVE,
            metadata = meta("browser", "เปิดแท็บใหม่", listOf("url"), listOf("tabId"), listOf("network"), true, "tab listed", "re-open")),
        CapabilityBinding("browser.close", "browser", "close", AdapterKind.NATIVE,
            metadata = meta("browser", "ปิดแท็บ", listOf("tabId"), listOf("ok"), emptyList(), false, "tab gone", "re-list")),
        CapabilityBinding("browser.list", "browser", "list", AdapterKind.NATIVE,
            metadata = meta("browser", "list แท็บ", emptyList(), listOf("tabs"), emptyList(), false, "tab count", "re-list")),
        CapabilityBinding("browser.navigate", "browser", "navigate", AdapterKind.NATIVE,
            metadata = meta("browser", "เปลี่ยน URL ในแท็บ", listOf("tabId,url"), listOf("ok"), listOf("network"), true, "tab url", "go back")),
        // Compatibility engine — CLI adapter, LAST resort (§29, CP-32).
        CapabilityBinding("terminal.open", "terminal", "open", AdapterKind.CLI_ADAPTER,
            metadata = meta("terminal", "เปิด terminal session", emptyList(), listOf("sessionId"), listOf("terminal"), false, "session listed", "re-open")),
        CapabilityBinding("terminal.exec", "terminal", "exec", AdapterKind.CLI_ADAPTER,
            metadata = meta("terminal", "รันคำสั่งใน session", listOf("sessionId,command"), listOf("output"), listOf("terminal"), false, "exit code", "repair ladder")),
        CapabilityBinding("terminal.close", "terminal", "close", AdapterKind.CLI_ADAPTER,
            metadata = meta("terminal", "ปิด session", listOf("sessionId"), listOf("ok"), emptyList(), false, "session gone", "force close")),
        CapabilityBinding("terminal.sessions", "terminal", "sessions", AdapterKind.CLI_ADAPTER,
            metadata = meta("terminal", "list sessions", emptyList(), listOf("sessions"), emptyList(), false, "session count", "re-list")),
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

private fun meta(
    tool: String, purpose: String, inputs: List<String>, outputs: List<String>,
    permissions: List<String>, network: Boolean, verification: String, recovery: String,
): CapabilityMetadata {
    val engines = mapOf(
        "files" to ("File Engine" to "SandboxFileStore"),
        "editor" to ("Code Engine" to "FileBackedEditor"),
        "git" to ("Git Engine" to "JGit"),
        "browser" to ("Browser Engine" to "WebView + tab store"),
        "terminal" to ("Compatibility Engine" to "Termux bridge (pending device work)"),
    )
    val (engine, runtime) = engines.getValue(tool)
    return CapabilityMetadata(
        name = purpose, category = tool, purpose = purpose, engine = engine, runtime = runtime,
        operations = listOf(tool), inputs = inputs, outputs = outputs,
        dependencies = if (tool == "editor") listOf("files") else emptyList(),
        permissions = permissions, needsNetwork = network, needsStorage = tool != "browser",
        compatibility = if (tool == "terminal") "requires Termux bridge" else "native",
        fallback = "", verification = verification, recovery = recovery,
    )
}
