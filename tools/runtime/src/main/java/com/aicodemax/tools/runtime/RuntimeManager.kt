package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import java.io.File

enum class RuntimeStatus { UNKNOWN, NOT_FOUND, AVAILABLE, BROKEN }

data class RuntimeInfo(
    val id: String,
    val displayName: String,
    val version: String = "",
    val path: String = "",
    val status: RuntimeStatus = RuntimeStatus.UNKNOWN,
    val detail: String = "",
)

data class RuntimeSpec(
    val id: String,
    val displayName: String,
    val candidates: List<String>,
    val versionArgs: List<String> = listOf("--version"),
)

/**
 * CP-14: runtime foundation (MASTER_ARCHITECTURE §16).
 * Detects executable runtimes from candidate paths + PATH lookup and probes
 * `--version` for health. Install/update/remove arrive with the provisioners
 * (device work); detection and health are real today.
 */
interface RuntimeManager {
    fun specs(): List<RuntimeSpec>
    fun detect(id: String): Outcome<RuntimeInfo>
    fun list(): List<RuntimeInfo>
}

class StaticRuntimeManager(private val runtimeSpecs: List<RuntimeSpec>) : RuntimeManager {
    override fun specs(): List<RuntimeSpec> = runtimeSpecs.toList()

    override fun list(): List<RuntimeInfo> = runtimeSpecs.map { spec ->
        detect(spec.id).fold(
            onSuccess = { it },
            onFailure = { RuntimeInfo(spec.id, spec.displayName, status = RuntimeStatus.UNKNOWN) },
        )
    }

    override fun detect(id: String): Outcome<RuntimeInfo> {
        val spec = runtimeSpecs.firstOrNull { it.id == id }
            ?: return Outcome.Failure(
                com.aicodemax.core.common.AppError("RUNTIME_UNKNOWN", "unknown runtime '$id'"),
            )
        val exe = spec.candidates.firstOrNull { findExecutable(it) != null }
            ?: return Outcome.Success(
                RuntimeInfo(
                    id = spec.id,
                    displayName = spec.displayName,
                    status = RuntimeStatus.NOT_FOUND,
                    detail = "none of: ${spec.candidates.joinToString(", ")}",
                ),
            )
        return ProcessRunner.run(exe, spec.versionArgs, timeoutMs = 10_000).fold(
            onSuccess = { result ->
                if (result.exitCode == 0) {
                    Outcome.Success(
                        RuntimeInfo(
                            id = spec.id,
                            displayName = spec.displayName,
                            version = (result.stdout + result.stderr).lineSequence().firstOrNull().orEmpty().take(120),
                            path = exe,
                            status = RuntimeStatus.AVAILABLE,
                            detail = "ok",
                        ),
                    )
                } else {
                    Outcome.Success(
                        RuntimeInfo(
                            id = spec.id,
                            displayName = spec.displayName,
                            path = exe,
                            status = RuntimeStatus.BROKEN,
                            detail = "exit=${result.exitCode} ${(result.stderr.ifBlank { result.stdout }).take(120)}",
                        ),
                    )
                }
            },
            onFailure = {
                Outcome.Success(
                    RuntimeInfo(
                        id = spec.id,
                        displayName = spec.displayName,
                        path = exe,
                        status = RuntimeStatus.BROKEN,
                        detail = it.message,
                    ),
                )
            },
        )
    }

    companion object {
        /** Resolves an executable: absolute/relative path or PATH lookup. */
        fun findExecutable(candidate: String): String? {
            val direct = File(candidate)
            if (direct.isAbsolute || candidate.contains('/')) {
                return if (direct.isFile && direct.canExecute()) direct.absolutePath else null
            }
            val pathEnv = System.getenv("PATH").orEmpty()
            for (dir in pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) continue
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
            return null
        }
    }
}
