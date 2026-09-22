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
        // Debug engine (native).
        CapabilityBinding("debug.analyze", "debug", "analyze", AdapterKind.NATIVE,
            metadata = meta("debug", "วิเคราะห์ stack trace หาสาเหตุ", listOf("error"), listOf("finding"), emptyList(), false, "parse confidence", "ask for full trace")),
        // Memory engine (native).
        CapabilityBinding("memory.save", "memory", "save", AdapterKind.NATIVE,
            metadata = meta("memory", "จำ key=value ระดับ global", listOf("key,value"), listOf("ok"), emptyList(), false, "read-back", "retry")),
        CapabilityBinding("memory.recall", "memory", "recall", AdapterKind.NATIVE,
            metadata = meta("memory", "ทวนความจำ global", listOf("key"), listOf("value"), emptyList(), false, "record found", "ask user")),
        // Skill engine (native).
        CapabilityBinding("skill.list", "skill", "list", AdapterKind.NATIVE,
            metadata = meta("skill", "list สกิลทั้งหมด", emptyList(), listOf("skills"), emptyList(), false, "index read", "re-list")),
        CapabilityBinding("skill.get", "skill", "get", AdapterKind.NATIVE,
            metadata = meta("skill", "อ่านเนื้อหาสกิล", listOf("id"), listOf("content"), emptyList(), false, "file read", "re-get")),
        CapabilityBinding("skill.install", "skill", "install", AdapterKind.NATIVE,
            metadata = meta("skill", "ติดตั้งสกิลจากไฟล์ workspace", listOf("path"), listOf("id"), listOf("fs.write"), false, "id listed", "fix file")),
        CapabilityBinding("skill.inject", "skill", "inject", AdapterKind.NATIVE,
            metadata = meta("skill", "ฉีดสกิลเข้า context", listOf("ids"), listOf("context"), emptyList(), false, "ids found", "list first")),
        CapabilityBinding("skill.remove", "skill", "remove", AdapterKind.NATIVE,
            metadata = meta("skill", "ลบสกิลที่ติดตั้ง", listOf("id"), listOf("ok"), listOf("fs.delete"), false, "id gone", "re-remove")),
        // Voice engine (native, Android speech APIs).
        CapabilityBinding("voice.listen", "voice", "listen", AdapterKind.NATIVE,
            metadata = meta("voice", "ฟังเสียงแล้วถอดเป็นข้อความ", listOf("lang?,timeoutMs?"), listOf("transcript"), listOf("mic"), false, "text heard", "re-listen")),
        CapabilityBinding("voice.speak", "voice", "speak", AdapterKind.NATIVE,
            metadata = meta("voice", "พูดข้อความออกเสียง", listOf("text,lang?"), listOf("ok"), listOf("audio"), false, "utterance done", "retry")),
        CapabilityBinding("voice.stop", "voice", "stop", AdapterKind.NATIVE,
            metadata = meta("voice", "หยุดเสียงที่กำลังพูด", emptyList(), listOf("ok"), emptyList(), false, "silent", "re-stop")),
        CapabilityBinding("voice.status", "voice", "status", AdapterKind.NATIVE,
            metadata = meta("voice", "เช็คความพร้อม STT/TTS", emptyList(), listOf("status"), emptyList(), false, "engine probe", "re-check")),
        // Image engine (native, header probe + pixel ops).
        CapabilityBinding("image.info", "image", "info", AdapterKind.NATIVE,
            metadata = meta("image", "ดูฟอร์แมต+ขนาดรูป", listOf("path"), listOf("info"), emptyList(), false, "header parsed", "re-probe")),
        CapabilityBinding("image.resize", "image", "resize", AdapterKind.NATIVE,
            metadata = meta("image", "ย่อรูปด้านยาวสุด", listOf("src,maxDim?"), listOf("dst"), listOf("fs.write"), false, "dims fit", "retry")),
        CapabilityBinding("image.crop", "image", "crop", AdapterKind.NATIVE,
            metadata = meta("image", "ครอปรูปตามกรอบ", listOf("src,x,y,w,h"), listOf("dst"), listOf("fs.write"), false, "dims match", "fix rect")),
        CapabilityBinding("image.rotate", "image", "rotate", AdapterKind.NATIVE,
            metadata = meta("image", "หมุนรูป 90/180/270", listOf("src,degrees?"), listOf("dst"), listOf("fs.write"), false, "dims swapped", "retry")),
        CapabilityBinding("image.grayscale", "image", "grayscale", AdapterKind.NATIVE,
            metadata = meta("image", "ทำรูปขาวดำ", listOf("src"), listOf("dst"), listOf("fs.write"), false, "luma only", "retry")),
        // Audio engine (native, WAV pipeline + MediaCodec decode).
        CapabilityBinding("audio.info", "audio", "info", AdapterKind.NATIVE,
            metadata = meta("audio", "ดูฟอร์แมต+ความยาวเสียง", listOf("path"), listOf("info"), emptyList(), false, "header parsed", "re-probe")),
        CapabilityBinding("audio.trim", "audio", "trim", AdapterKind.NATIVE,
            metadata = meta("audio", "ตัดเสียงตามช่วงเวลา", listOf("src,startMs,endMs"), listOf("dst"), listOf("fs.write"), false, "duration match", "fix range")),
        CapabilityBinding("audio.concat", "audio", "concat", AdapterKind.NATIVE,
            metadata = meta("audio", "ต่อไฟล์เสียง", listOf("srcs"), listOf("dst"), listOf("fs.write"), false, "duration sum", "fix files")),
        CapabilityBinding("audio.gain", "audio", "gain", AdapterKind.NATIVE,
            metadata = meta("audio", "เร่ง/เบาเสียง", listOf("src,db"), listOf("dst"), listOf("fs.write"), false, "peak changed", "retry")),
        CapabilityBinding("audio.fade", "audio", "fade", AdapterKind.NATIVE,
            metadata = meta("audio", "เฟดหัว/ท้ายเสียง", listOf("src,inMs?,outMs?"), listOf("dst"), listOf("fs.write"), false, "ramps ok", "retry")),
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
        for (toolId in listOf("files", "editor", "git", "browser", "debug", "memory", "skill", "voice", "image", "audio")) {
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
        "debug" to ("Debug Engine" to "StackTraceParser + DebugSession"),
        "memory" to ("Memory Engine" to "FileMemoryStore"),
        "skill" to ("Skill Engine" to "FileSkillStore"),
        "voice" to ("Voice Engine" to "SpeechRecognizer + TTS"),
        "image" to ("Image Engine" to "header probe + pixel ops"),
        "audio" to ("Audio Engine" to "WAV pipeline + MediaCodec"),
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
