package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun processRunnerCapturesOutput() {
        // `sh` exists on CI Linux and on Android (/system/bin/sh).
        val result = (ProcessRunner.run("sh", listOf("-c", "echo out; echo err 1>&2")) as Outcome.Success<ProcessResult>).value
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("out"))
        assertTrue(result.stderr.contains("err"))
    }

    @Test
    fun processRunnerHonestOnMissingAndTimeout() {
        val missing = ProcessRunner.run("definitely-missing-exe-xyz")
        assertTrue(missing is Outcome.Failure)

        val timed = (ProcessRunner.run("sh", listOf("-c", "sleep 30"), timeoutMs = 500) as Outcome.Success<ProcessResult>).value
        assertTrue(timed.timedOut)
    }

    @Test
    fun runtimeDetectionFindsSh() {
        val manager = StaticRuntimeManager(
            listOf(RuntimeSpec("shell", "Shell", listOf("sh"), listOf("-c", "echo vtest"))),
        )
        val info = (manager.detect("shell") as Outcome.Success<RuntimeInfo>).value
        assertEquals(RuntimeStatus.AVAILABLE, info.status)
        assertTrue(info.path.isNotBlank())

        val missing = StaticRuntimeManager(
            listOf(RuntimeSpec("nope", "Nope", listOf("definitely-missing-xyz"))),
        ).detect("nope") as Outcome.Success<RuntimeInfo>
        assertEquals(RuntimeStatus.NOT_FOUND, missing.value.status)

        val unknown = manager.detect("ghost")
        assertTrue(unknown is Outcome.Failure)
    }

    @Test
    fun scriptExecutionPlumbing() {
        val script = File(tmp.root, "hello.sh").apply { writeText("echo hello") }
        val lang = ScriptLanguageRuntime(
            id = "shtest",
            displayName = "sh-test",
            candidates = listOf("sh"),
            versionArgs = listOf("-c", "echo v"),
            scriptArgs = { file -> listOf(file.absolutePath) },
        )
        val result = (lang.execute(script) as Outcome.Success<ProcessResult>).value
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello"))
    }

    @Test
    fun missingInterpreterFailsHonestly() {
        val script = File(tmp.root, "x.py").apply { writeText("print(1)") }
        val lang = ScriptLanguageRuntime("py", "Python", listOf("definitely-missing-python-xyz"))
        val result = lang.execute(script)
        assertTrue(result is Outcome.Failure)
        assertEquals("RUNTIME_NOT_INSTALLED", (result as Outcome.Failure).error.code)
    }
}
