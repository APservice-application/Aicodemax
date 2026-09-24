package com.aicodemax.tools.terminal_runtime

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatEngineTest {
    private class FakePort : TerminalPort {
        var nextId = 0
        val created = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val cwds = mutableListOf<String?>()

        override fun descriptor(): ToolDescriptor = terminalDescriptorToday()
        override fun createSession(title: String): Outcome<TerminalSession> {
            val session = TerminalSession("port_${++nextId}", title, SessionState.RUNNING, 0)
            created.add(session.id)
            return Outcome.Success(session)
        }
        override fun closeSession(sessionId: String): Outcome<Unit> {
            closed.add(sessionId)
            return Outcome.Success(Unit)
        }
        override fun listSessions(): Outcome<List<TerminalSession>> = Outcome.Success(emptyList())
        override fun exec(request: ExecRequest, listener: ExecListener): Outcome<RunningHandle> {
            cwds.add(request.cwd)
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

    @Test
    fun execBridgesToSinglePortSession() {
        val fake = FakePort()
        val engine = CompatEngine(CliToolAdapter(fake))
        val opened = (engine.openSession("dev") as Outcome.Success<TerminalSession>).value
        assertTrue(engine.exec("echo a", opened.id) is Outcome.Success)
        assertTrue(engine.exec("echo b", opened.id) is Outcome.Success)
        assertEquals(1, fake.created.size)
    }

    @Test
    fun closeSessionClosesPortSession() {
        val fake = FakePort()
        val engine = CompatEngine(CliToolAdapter(fake))
        val opened = (engine.openSession("dev") as Outcome.Success<TerminalSession>).value
        assertTrue(engine.exec("echo a", opened.id) is Outcome.Success)
        assertTrue(engine.closeSession(opened.id) is Outcome.Success)
        assertEquals(fake.created, fake.closed)
    }

    @Test
    fun execLiveStreamsAndReturnsHandle() {
        val engine = CompatEngine(CliToolAdapter(FakePort()))
        val opened = (engine.openSession("dev") as Outcome.Success<TerminalSession>).value
        val latch = CountDownLatch(1)
        val chunks = mutableListOf<String>()
        val live = (
            engine.execLive("echo hi", opened.id) { chunk ->
                chunks.add(chunk.text)
                if (chunk.finished) latch.countDown()
            } as Outcome.Success<CompatLive>
            ).value
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(opened.id, live.sessionId)
        assertEquals(listOf("ok\n"), chunks)
    }

    @Test
    fun pendingCwdConsumedOnce() {
        val fake = FakePort()
        val engine = CompatEngine(CliToolAdapter(fake), defaultCwd = "/dflt")
        val opened = (engine.openSession("a") as Outcome.Success<TerminalSession>).value
        assertTrue(engine.exec("pwd", opened.id) is Outcome.Success)
        assertTrue(engine.exec("pwd", opened.id) is Outcome.Success)
        assertEquals(listOf("/dflt", null), fake.cwds)
    }

    @Test
    fun explicitCwdWinsOverDefault() {
        val fake = FakePort()
        val engine = CompatEngine(CliToolAdapter(fake), defaultCwd = "/dflt")
        val opened = (engine.openSession("a", "/x") as Outcome.Success<TerminalSession>).value
        assertTrue(engine.exec("pwd", opened.id) is Outcome.Success)
        assertEquals(listOf("/x"), fake.cwds)
    }
}
