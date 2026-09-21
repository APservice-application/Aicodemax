package com.aicodemax.tools.builder

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.fold
import com.aicodemax.tools.registry.ToolDescriptor
import java.io.File

/** CP-21: build pipeline stages (MASTER_ARCHITECTURE §21). */
enum class BuildStage {
    CONFIGURE,
    PRECHECK,
    RESOLVE_DEPS,
    PREPARE_RUNTIME,
    COMPILE,
    PACKAGE,
    SIGN,
    VERIFY,
}

data class BuildStageResult(
    val stage: BuildStage,
    val ok: Boolean,
    val output: String = "",
    val error: String = "",
    val artifactPath: String = "",
)

/** Stage adapters do the real work (Gradle adapter = device work, later). */
interface BuildStageAdapter {
    val stage: BuildStage
    suspend fun run(request: BuildRequest): Outcome<BuildStageResult>
}

fun interface ArtifactVerifier {
    fun verify(artifactPath: String): Outcome<Unit>
}

/** Default verifier: artifact must exist and be non-empty. */
object FileArtifactVerifier : ArtifactVerifier {
    override fun verify(artifactPath: String): Outcome<Unit> {
        val file = File(artifactPath)
        return if (file.isFile && file.length() > 0) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError("BUILD_ARTIFACT_MISSING", "artifact missing or empty: $artifactPath"))
        }
    }
}

/**
 * Pipeline build engine: runs stages in §21 order, stops at first failure,
 * and NEVER trusts exit codes alone — a claimed artifact is always verified.
 * Stages without an adapter are skipped visibly (never silently).
 */
class PipelineBuildEngine(
    adapters: List<BuildStageAdapter>,
    private val verifier: ArtifactVerifier = FileArtifactVerifier,
    private val clock: Clock = SystemClock,
) : BuildPort {
    private val byStage: Map<BuildStage, BuildStageAdapter> = adapters.associateBy { it.stage }

    override fun descriptor(): ToolDescriptor = buildDescriptorToday()

    override suspend fun build(request: BuildRequest): Outcome<BuildResult> {
        if (request.projectDir.isBlank() || !File(request.projectDir).isDirectory) {
            return Outcome.Failure(AppError("BUILD_NO_PROJECT", "project dir missing: '${request.projectDir}'"))
        }
        val started = clock.nowMillis()
        val log = StringBuilder()
        val errors = mutableListOf<String>()
        var artifactPath = ""
        for (stage in BuildStage.values()) {
            val adapter = byStage[stage]
            if (adapter == null) {
                log.appendLine("[skip] $stage: no adapter")
                continue
            }
            log.appendLine("[run] $stage")
            val result = adapter.run(request).fold(
                onSuccess = { it },
                onFailure = { BuildStageResult(stage, ok = false, error = it.message) },
            )
            if (result.output.isNotBlank()) log.appendLine(result.output.trim())
            if (!result.ok) {
                val msg = "$stage failed: ${result.error.ifBlank { "unknown error" }}"
                errors.add(msg)
                log.appendLine("[fail] $msg")
                return Outcome.Failure(AppError("BUILD_STAGE_FAILED", errors.joinToString("; ")))
            }
            if (result.artifactPath.isNotBlank()) artifactPath = result.artifactPath
        }
        if (artifactPath.isNotBlank()) {
            when (val checked = verifier.verify(artifactPath)) {
                is Outcome.Failure -> return checked
                is Outcome.Success -> log.appendLine("[verify] artifact ok: $artifactPath")
            }
        }
        return Outcome.Success(
            BuildResult(
                success = true,
                output = log.toString().trim(),
                durationMs = clock.nowMillis() - started,
            ),
        )
    }

    override suspend fun runTests(projectDir: String): Outcome<TestRunResult> =
        Outcome.Failure(AppError("TEST_ENGINE_TODO", "TestEngine (CP-22) wires separately"))
}
