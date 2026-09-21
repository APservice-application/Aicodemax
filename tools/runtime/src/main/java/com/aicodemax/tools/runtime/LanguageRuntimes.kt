package com.aicodemax.tools.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import java.io.File

/**
 * CP-15: language runtime adapters (MASTER_ARCHITECTURE §17).
 * Each language resolves an interpreter and executes scripts through
 * [ProcessRunner] — the AI calls `LanguageRuntime.execute()`, never a
 * shell command. Real device interpreters (bundled/downloaded binaries)
 * are provisioned later; resolution failure is honest NOT_FOUND today.
 */
interface LanguageRuntime {
    val id: String
    fun resolve(): Outcome<RuntimeInfo>
    fun execute(script: File, args: List<String> = emptyList(), timeoutMs: Long = 30_000): Outcome<ProcessResult>
}

class ScriptLanguageRuntime(
    override val id: String,
    private val displayName: String,
    private val candidates: List<String>,
    private val versionArgs: List<String> = listOf("--version"),
    private val scriptArgs: (File) -> List<String> = { script -> listOf(script.absolutePath) },
) : LanguageRuntime {
    private val manager = StaticRuntimeManager(
        listOf(RuntimeSpec(id, displayName, candidates, versionArgs)),
    )

    override fun resolve(): Outcome<RuntimeInfo> = manager.detect(id)

    override fun execute(script: File, args: List<String>, timeoutMs: Long): Outcome<ProcessResult> {
        if (!script.isFile) {
            return Outcome.Failure(AppError("SCRIPT_MISSING", "script not found: ${script.path}"))
        }
        return resolve().fold(
            onSuccess = { info ->
                if (info.status != RuntimeStatus.AVAILABLE) {
                    Outcome.Failure(
                        AppError(
                            "RUNTIME_NOT_INSTALLED",
                            "$displayName is not installed (${info.detail}); provision it first",
                        ),
                    )
                } else {
                    ProcessRunner.run(
                        info.path.ifBlank { candidates.first() },
                        scriptArgs(script) + args,
                        workDir = script.parentFile,
                        timeoutMs = timeoutMs,
                    )
                }
            },
            onFailure = { Outcome.Failure(it) },
        )
    }
}

object LanguageRuntimes {
    /** App-private binaries live under [appHome]/bin; system PATH is fallback. */
    fun python(appHome: File): LanguageRuntime = ScriptLanguageRuntime(
        id = "python",
        displayName = "Python",
        candidates = listOf(
            File(appHome, "bin/python3").absolutePath,
            File(appHome, "bin/python").absolutePath,
            "python3",
            "python",
        ),
    )

    fun java(appHome: File): LanguageRuntime = ScriptLanguageRuntime(
        id = "java",
        displayName = "Java",
        candidates = listOf(File(appHome, "bin/java").absolutePath, "java"),
        versionArgs = listOf("-version"),
        scriptArgs = { script -> listOf(script.absolutePath) },
    )

    fun node(appHome: File): LanguageRuntime = ScriptLanguageRuntime(
        id = "node",
        displayName = "Node.js",
        candidates = listOf(File(appHome, "bin/node").absolutePath, "node", "nodejs"),
    )
}
