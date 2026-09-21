package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.TerminalPort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gateway executor for the terminal tool (actions: open/exec/close/sessions).
 * Works with any [TerminalPort]; ready the moment the Termux runtime is wired.
 * See docs/TERMINAL_WIRING.md.
 */
class TerminalToolExecutor(private val terminal: TerminalPort) : ToolExecutor {
    override val toolId: String = "terminal"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "open" -> {
                    val title = call.args["title"] ?: "shell"
                    terminal.createSession(title).fold(
                        onSuccess = { done(true, "session ${it.id} (${it.state.name})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "exec" -> execBlocking(call)
                "close" -> {
                    val sessionId = call.args["sessionId"]
                        ?: return@withContext done(false, error = "missing arg: sessionId")
                    terminal.closeSession(sessionId).fold(
                        onSuccess = { done(true, "closed $sessionId") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "sessions" -> terminal.listSessions().fold(
                    onSuccess = { sessions ->
                        done(true, sessions.joinToString("\n") { "${it.id} ${it.state.name} ${it.title}" }
                            .ifBlank { "(no sessions)" })
                    },
                    onFailure = { done(false, error = it.message) },
                )
                else -> done(false, error = "unknown action '${call.action}'")
            }
        }

    private fun execBlocking(call: ToolCall): Outcome<ToolResult> {
        val sessionId = call.args["sessionId"] ?: return done(false, error = "missing arg: sessionId")
        val command = call.args["command"] ?: return done(false, error = "missing arg: command")
        val timeoutMs = call.args["timeoutMs"]?.toLongOrNull() ?: 60_000L
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        val finished = CountDownLatch(1)

        val started = terminal.exec(ExecRequest(sessionId, command, timeoutMs = timeoutMs)) { chunk ->
            if (chunk.isStderr) stderr.append(chunk.text) else stdout.append(chunk.text)
            if (chunk.finished) {
                exitCode = chunk.exitCode
                finished.countDown()
            }
        }
        if (started is Outcome.Failure) return done(false, error = started.error.message)
        val completed = try {
            finished.await(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            (started as Outcome.Success).value.cancel()
            return done(false, error = "terminal exec timed out")
        }
        val code = exitCode ?: -1
        return if (code == 0) {
            done(true, output = stdout.toString())
        } else {
            done(false, output = stdout.toString(), error = "exit=$code\n$stderr")
        }
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
