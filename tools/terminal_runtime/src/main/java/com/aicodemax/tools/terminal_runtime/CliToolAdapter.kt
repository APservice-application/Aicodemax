package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.terminal.CommandResult
import com.aicodemax.tools.terminal.CommandRisk
import com.aicodemax.tools.terminal.CommandRiskClassifier
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.TerminalPort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CP-32: CLI tool adapter (MASTER_ARCHITECTURE §33). Turns the callback-based
 * [TerminalPort.exec] into one blocking call for tool use. BANNED commands
 * never reach the runtime. The terminal stays a compatibility engine — tools
 * prefer native capability bindings (tools:capability).
 */
class CliToolAdapter(
    private val port: TerminalPort,
    private val defaultTimeoutMs: Long = 60_000,
    private val maxChars: Int = 32_000,
) {
    fun run(sessionId: String, command: String, timeoutMs: Long = defaultTimeoutMs): Outcome<CommandResult> {
        if (CommandRiskClassifier.classify(command) == CommandRisk.BANNED) {
            return Outcome.Failure(AppError("COMMAND_BANNED", "command is banned: $command"))
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val done = CountDownLatch(1)
        var exitCode = -1
        val outcome = port.exec(
            ExecRequest(sessionId = sessionId, command = command, timeoutMs = timeoutMs),
            { chunk: ExecChunk ->
                if (chunk.isStderr) stderr.append(chunk.text) else stdout.append(chunk.text)
                if (chunk.finished) {
                    exitCode = chunk.exitCode ?: 0
                    done.countDown()
                }
            },
        )
        val handle = outcome.fold(
            onSuccess = { it },
            onFailure = { return Outcome.Failure(it) },
        )
        val finished = try {
            done.await(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            runCatching { handle.cancel() }
            return Outcome.Failure(AppError("COMMAND_TIMEOUT", "command timed out after ${timeoutMs}ms"))
        }
        return Outcome.Success(clip(stdout.toString(), stderr.toString(), exitCode))
    }

    private fun clip(stdout: String, stderr: String, exitCode: Int): CommandResult {
        val limit = maxChars.coerceAtLeast(256)
        val truncated = stdout.length + stderr.length > limit
        var out = stdout
        var err = stderr
        if (truncated) {
            // Keep stderr whole when small; cut stdout first.
            val errKeep = minOf(err.length, limit / 4)
            err = err.takeLast(errKeep)
            out = out.take(limit - errKeep)
        }
        return CommandResult(exitCode, out, err, truncated)
    }

    /** No-op handle for fakes/tests. */
    class CompletedHandle : RunningHandle {
        override fun cancel() = Unit
        override fun isRunning(): Boolean = false
    }
}
