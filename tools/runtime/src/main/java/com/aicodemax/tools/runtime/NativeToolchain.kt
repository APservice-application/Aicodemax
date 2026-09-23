package com.aicodemax.tools.runtime

import java.io.File

/**
 * CP-118: prebuilt native tools embedded in the APK (ported from the owner's
 * previous app — arm64: ffmpeg + ffprobe + llama-server).
 *
 * Build-time: CI downloads the .so files from release `archive/oldai-workspace`
 * into app/src/main/jniLibs/arm64-v8a/ (repo stays lean, no binaries in git).
 * Runtime: Android extracts them to nativeLibraryDir (exec bit set via
 * useLegacyPackaging) — this object resolves paths + detects versions.
 *
 * Pure JVM (testable): pass [nativeLibDir] + a [runner]; Android wires the real
 * nativeLibraryDir + ProcessRunner. Missing dir/files → honest "missing" lines.
 */
object NativeToolchain {
    data class NativeTool(val name: String, val fileName: String, val versionArgs: List<String>)

    val TOOLS: List<NativeTool> = listOf(
        NativeTool("ffmpeg", "libffmpeg.so", listOf("-version")),
        NativeTool("ffprobe", "libffprobe.so", listOf("-version")),
        NativeTool("llama-server", "libllama-server.so", listOf("--version")),
    )

    /** Resolve expected executable paths (files may legitimately not exist). */
    fun resolve(nativeLibDir: String?): Map<String, File> {
        val dir = nativeLibDir?.trim().orEmpty()
        return TOOLS.associate { it.name to File(dir, it.fileName) }
    }

    /**
     * Detect each tool: missing file → "missing: <path>"; runnable → first line
     * of version output; runner failure → "error: ...". Never throws.
     */
    fun detect(
        nativeLibDir: String?,
        runner: (executable: String, args: List<String>) -> String,
    ): Map<String, String> {
        if (nativeLibDir.isNullOrBlank()) {
            return TOOLS.associate { it.name to "missing: no native library dir on this runtime" }
        }
        return TOOLS.associate { tool ->
            val exe = File(nativeLibDir, tool.fileName)
            val line = when {
                !exe.isFile -> "missing: ${exe.path} (CI embeds it at build time)"
                !exe.canExecute() -> "present but not executable: ${exe.path}"
                else -> runCatching {
                    runner(exe.path, tool.versionArgs).lineSequence().firstOrNull()?.trim().orEmpty()
                        .ifBlank { "ran but no version output" }.take(160)
                }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
            }
            tool.name to line
        }
    }

    fun format(report: Map<String, String>): String =
        report.entries.joinToString("\n") { (name, line) -> "• $name: $line" }
}
