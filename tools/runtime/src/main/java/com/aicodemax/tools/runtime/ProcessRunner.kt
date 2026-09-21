package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
)

/**
 * CP-14: process foundation (pure JVM ProcessBuilder — no NDK, runs on
 * Android and CI alike). Powers runtime detection, language execution,
 * and future build/test adapters. Output is clipped, never unbounded.
 */
object ProcessRunner {
    const val MAX_OUTPUT_CHARS = 64_000

    fun run(
        executable: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
        timeoutMs: Long = 30_000,
        extraEnv: Map<String, String> = emptyMap(),
    ): Outcome<ProcessResult> = runOutcome("PROCESS_RUN") {
        val process = ProcessBuilder(listOf(executable) + args).apply {
            workDir?.let { directory(it) }
            environment().putAll(extraEnv)
        }.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = gobble(process.inputStream, stdout)
        val errThread = gobble(process.errorStream, stderr)
        val finished = process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(2_000)
            errThread.join(2_000)
            return@runOutcome ProcessResult(
                exitCode = -1,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = true,
            )
        }
        outThread.join(5_000)
        errThread.join(5_000)
        ProcessResult(process.exitValue(), stdout.toString(), stderr.toString(), false)
    }

    private fun gobble(stream: InputStream, sink: StringBuilder): Thread =
        Thread {
            try {
                stream.bufferedReader().forEachLine { line ->
                    if (sink.length < MAX_OUTPUT_CHARS) sink.appendLine(line)
                }
            } catch (_: Exception) {
                // Stream closed (timeout/destroy) — partial output is fine.
            }
        }.also { it.isDaemon = true; it.start() }
}
