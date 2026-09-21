package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.ExecListener
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.TerminalPort
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.tools.terminal.terminalDescriptorToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliToolAdapterTest {
    private class FakePort(
        private val chunks: List<ExecChunk> = listOf(ExecChunk("s", "hi\n", finished = true, exitCode = 0)),
        private val hang: Boolean = false,
    ) : TerminalPort {
        val commands = mutableListOf<String>()
        var cancelled = false
        override fun descriptor(): ToolDescriptor = terminalDescriptorToday()
        override fun createSession(title: String): Outcome<TerminalSession> =
            Outcome.Failure(AppError("X", "unused"))
        override fun closeSession(sessionId: String): Outcome<Unit> = Outcome.Success(Unit)
        override fun listSessions(): Outcome<List<TerminalSession>> = Outcome.Success(emptyList())
        override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> {
            commands.add(request.command)
            val handle = object : RunningHandle {
                override fun cancel() {
                    cancelled = true
                }
                override fun isRunning(): Boolean = hang && !cancelled
            }
            if (!hang) {
                Thread { chunks.forEach { listener.onChunk(it) } }.start()
            }
            return Outcome.Success(handle)
        }
    }

    @Test
    fun collectsChunksIntoResult() {
        val port = FakePort(
            listOf(
                ExecChunk("s", "out", finished = false),
                ExecChunk("s", "err", isStderr = true, finished = false),
                ExecChunk("s", "", finished = true, exitCode = 3),
            ),
        )
        val result = (CliToolAdapter(port).run("s", "echo hi") as Outcome.Success<com.aicodemax.tools.terminal.CommandResult>).value
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertEquals(3, result.exitCode)
        assertEquals(false, result.truncated)
    }

    @Test
    fun bannedCommandNeverReachesPort() {
        val port = FakePort()
        // rm -rf / style commands are classified BANNED (see CommandRiskTest).
        val result = CliToolAdapter(port).run("s", "rm -rf /")
        assertTrue(result is Outcome.Failure)
        assertEquals("COMMAND_BANNED", (result as Outcome.Failure).error.code)
        assertTrue(port.commands.isEmpty())
    }

    @Test
    fun timeoutCancelsHandle() {
        val port = FakePort(hang = true)
        val result = CliToolAdapter(port).run("s", "sleep 99", timeoutMs = 50)
        assertTrue(result is Outcome.Failure)
        assertEquals("COMMAND_TIMEOUT", (result as Outcome.Failure).error.code)
        assertTrue(port.cancelled)
    }

    @Test
    fun largeOutputTruncates() {
        val port = FakePort(listOf(ExecChunk("s", "x".repeat(10_000), finished = true, exitCode = 0)))
        val result = (CliToolAdapter(port, maxChars = 256).run("s", "yes")
            as Outcome.Success<com.aicodemax.tools.terminal.CommandResult>).value
        assertEquals(true, result.truncated)
        assertTrue(result.stdout.length + result.stderr.length <= 256)
    }
}
