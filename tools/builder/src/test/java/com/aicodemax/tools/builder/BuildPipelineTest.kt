package com.aicodemax.tools.builder

import com.aicodemax.core.common.Outcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun adapter(
        stage: BuildStage,
        ok: Boolean = true,
        artifact: String = "",
        output: String = "",
    ) = object : BuildStageAdapter {
        override val stage: BuildStage = stage
        override suspend fun run(request: BuildRequest): Outcome<BuildStageResult> =
            Outcome.Success(BuildStageResult(stage, ok, output, "", artifact))
    }

    @Test
    fun pipelineRunsStagesAndVerifiesArtifact() = runBlocking {
        val project = tmp.newFolder("proj")
        val apk = File(project, "app.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val engine = PipelineBuildEngine(
            listOf(
                adapter(BuildStage.CONFIGURE, output = "configured"),
                adapter(BuildStage.COMPILE, output = "compiled"),
                adapter(BuildStage.PACKAGE, artifact = apk.absolutePath),
            ),
        )
        val result = (engine.build(BuildRequest(project.absolutePath)) as Outcome.Success<BuildResult>).value
        assertTrue(result.success)
        assertTrue(result.output.contains("[run] COMPILE"))
        assertTrue(result.output.contains("[skip] PRECHECK: no adapter"))
        assertTrue(result.output.contains("[verify] artifact ok"))
    }

    @Test
    fun missingArtifactFailsDespiteOkStages() = runBlocking {
        val project = tmp.newFolder("proj")
        val engine = PipelineBuildEngine(
            listOf(adapter(BuildStage.PACKAGE, artifact = File(project, "ghost.apk").absolutePath)),
        )
        val result = engine.build(BuildRequest(project.absolutePath))
        assertTrue(result is Outcome.Failure)
        assertEquals("BUILD_ARTIFACT_MISSING", (result as Outcome.Failure).error.code)
    }

    @Test
    fun stageFailureStopsPipeline() = runBlocking {
        val project = tmp.newFolder("proj")
        var compileRan = false
        val failing = object : BuildStageAdapter {
            override val stage = BuildStage.PRECHECK
            override suspend fun run(request: BuildRequest): Outcome<BuildStageResult> =
                Outcome.Success(BuildStageResult(stage, ok = false, error = "no SDK"))
        }
        val compile = object : BuildStageAdapter {
            override val stage = BuildStage.COMPILE
            override suspend fun run(request: BuildRequest): Outcome<BuildStageResult> {
                compileRan = true
                return Outcome.Success(BuildStageResult(stage, ok = true))
            }
        }
        val result = PipelineBuildEngine(listOf(failing, compile)).build(BuildRequest(project.absolutePath))
        assertTrue(result is Outcome.Failure)
        assertEquals("BUILD_STAGE_FAILED", (result as Outcome.Failure).error.code)
        assertTrue(!compileRan)
    }
}
