package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.ExecListener
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.SessionState
import com.aicodemax.tools.terminal.TerminalPort
import com.aicodemax.tools.terminal.TerminalSession
import com.aicodemax.tools.terminal.terminalDescriptorToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatEngineTest {
    private class FakePort : TerminalPort {
        override fun descriptor(): ToolDescriptor = terminalDescriptorToday()
        override fun createSession(title: String): Outcome<TerminalSession> =
            Outcome.Failure(AppError("X", "unused"))
        override fun closeSession(sessionId: String): Outcome<Unit> = Outcome.Success(Unit)
        override fun listSessions(): Outcome<List<TerminalSession>> = Outcome.Success(emptyList())
        override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> {
            Thread {
                listener.onChunk(ExecChunk(request.sessionId, "ok\n", finished = true, exitCode = 0))
            }.start()
            return Outcome.Success(CliToolAdapter.CompletedHandle())
        }
    }

    @Test
    fun execTracksSession() {
        val engine = CompatEngine(CliToolAdapter(FakePort()))
        val opened = (engine.openSession("dev") as Outcome.Success<TerminalSession>).value
        val result = (engine.exec("echo hi", opened.id) as Outcome.Success<CompatResult>).value
        assertEquals("ok\n", result.stdout)
        assertEquals(opened.id, result.sessionId)
        assertEquals(SessionState.RUNNING, engine.sessions().single().state)
        assertTrue(engine.closeSession(opened.id) is Outcome.Success)
        assertTrue(engine.sessions().isEmpty())
    }

    @Test
    fun execCreatesSessionOnDemand() {
        val engine = CompatEngine(CliToolAdapter(FakePort()))
        val result = (engine.exec("echo hi") as Outcome.Success<CompatResult>).value
        assertEquals(1, engine.sessions().size)
        assertEquals(engine.sessions().single().id, result.sessionId)
    }

    @Test
    fun unknownSessionAndBannedFailHonestly() {
        val engine = CompatEngine(CliToolAdapter(FakePort()))
        assertTrue(engine.exec("echo hi", "ghost") is Outcome.Failure)
        assertTrue(engine.exec("rm -rf /") is Outcome.Failure)
    }
}
