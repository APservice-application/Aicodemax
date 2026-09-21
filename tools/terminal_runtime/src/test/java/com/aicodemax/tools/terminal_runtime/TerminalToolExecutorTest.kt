package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.ExecListener
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.SessionState
import com.aicodemax.tools.terminal.TerminalPort
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.tools.terminal.terminalDescriptorToday
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTerminalPort(var exitCode: Int = 0, var output: String = "hi\n") : TerminalPort {
    val sessions = mutableListOf(TerminalSession("s1", "shell", SessionState.RUNNING, 1L))

    override fun descriptor(): ToolDescriptor = terminalDescriptorToday()
    override fun createSession(title: String): Outcome<TerminalSession> {
        val session = TerminalSession("s${sessions.size + 1}", title, SessionState.RUNNING, 1L)
        sessions.add(session)
        return Outcome.Success(session)
    }
    override fun closeSession(sessionId: String): Outcome<Unit> {
        sessions.removeIf { it.id == sessionId }
        return Outcome.Success(Unit)
    }
    override fun listSessions(): Outcome<List<TerminalSession>> = Outcome.Success(sessions.toList())
    override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> {
        listener.onChunk(ExecChunk(request.sessionId, output))
        listener.onChunk(ExecChunk(request.sessionId, "", finished = true, exitCode = exitCode))
        return Outcome.Success(object : RunningHandle {
            override fun cancel() = Unit
            override fun isRunning(): Boolean = false
        })
    }
}

class TerminalToolExecutorTest {
    private fun resultOf(call: ToolCall, port: FakeTerminalPort = FakeTerminalPort()): ToolResult {
        val executor = TerminalToolExecutor(port)
        return runBlocking { executor.execute(call) as Outcome.Success<ToolResult> }.value
    }

    @Test
    fun execCollectsOutputAndExitCode() {
        val ok = resultOf(ToolCall("c1", "terminal", "exec", mapOf("sessionId" to "s1", "command" to "echo hi")))
        assertTrue(ok.ok)
        assertEquals("hi\n", ok.output)

        val failed = resultOf(
            ToolCall("c2", "terminal", "exec", mapOf("sessionId" to "s1", "command" to "x")),
            FakeTerminalPort(exitCode = 3, output = "out"),
        )
        assertFalse(failed.ok)
        assertTrue(failed.error.contains("exit=3"))
    }

    @Test
    fun openCloseSessions() {
        val port = FakeTerminalPort()
        val opened = resultOf(ToolCall("c1", "terminal", "open", mapOf("title" to "work")), port)
        assertTrue(opened.ok)
        assertTrue(opened.output.contains("s2"))

        val listed = resultOf(ToolCall("c2", "terminal", "sessions"), port)
        assertTrue(listed.output.contains("s1") && listed.output.contains("s2"))

        val closed = resultOf(ToolCall("c3", "terminal", "close", mapOf("sessionId" to "s1")), port)
        assertTrue(closed.ok)
        assertEquals(1, port.sessions.size)
    }

    @Test
    fun missingArgsAndUnknownActionFailHonestly() {
        val missing = resultOf(ToolCall("c1", "terminal", "exec", mapOf("command" to "ls")))
        assertFalse(missing.ok)
        assertTrue(missing.error.contains("sessionId"))

        val unknown = resultOf(ToolCall("c2", "terminal", "fly"))
        assertFalse(unknown.ok)
        assertTrue(unknown.error.contains("unknown action"))
    }
}
