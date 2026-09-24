package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.terminal.ExecRequest
import com.aicodemax.tools.terminal.RunningHandle
import com.aicodemax.tools.terminal.TerminalSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real `sh` protocol tests (JVM has a shell; Android has toybox sh). */
class PersistentShellPortTest {
    private lateinit var port: PersistentShellPort
    private val opened = mutableListOf<String>()

    @Before
    fun setup() {
        port = PersistentShellPort()
    }

    @After
    fun tearDown() {
        for (id in opened) runCatching { port.closeSession(id) }
        opened.clear()
    }

    private fun open(): String {
        val s = (port.createSession("t") as Outcome.Success<TerminalSession>).value
        opened.add(s.id)
        return s.id
    }

    private fun runSync(
        sessionId: String,
        command: String,
        timeoutMs: Long = 10_000,
        cwd: String? = null,
    ): Pair<Int, String> {
        val out = StringBuilder()
        val latch = CountDownLatch(1)
        var code = -999
        val started = port.exec(ExecRequest(sessionId, command, cwd = cwd, timeoutMs = timeoutMs)) { chunk ->
            synchronized(out) { out.append(chunk.text) }
            if (chunk.finished) {
                code = chunk.exitCode ?: -999
                latch.countDown()
            }
        }
        assertTrue(started is Outcome.Success)
        assertTrue("timed out waiting for '$command'", latch.await(timeoutMs + 15_000, TimeUnit.MILLISECONDS))
        return code to out.toString()
    }

    @Test
    fun echoReturnsOutputAndZero() {
        val (code, out) = runSync(open(), "echo hello")
        assertEquals(0, code)
        assertTrue(out.contains("hello"))
    }

    @Test
    fun cdPersistsAcrossExecs() {
        val id = open()
        val (code1, _) = runSync(id, "cd /tmp")
        assertEquals(0, code1)
        val (code2, out2) = runSync(id, "pwd")
        assertEquals(0, code2)
        assertTrue(out2.contains("/tmp"))
    }

    @Test
    fun exportPersistsAcrossExecs() {
        val id = open()
        runSync(id, "export AICODEMUX_PROBE=bar123")
        val (code, out) = runSync(id, "echo \$AICODEMUX_PROBE")
        assertEquals(0, code)
        assertTrue(out.contains("bar123"))
    }

    @Test
    fun exitCodePassthrough() {
        val id = open()
        assertEquals(1, runSync(id, "false").first)
        assertEquals(3, runSync(id, "sh -c 'exit 3'").first)
    }

    @Test
    fun unknownSessionAndBlankCommandFail() {
        assertTrue(port.exec(ExecRequest("ghost", "echo hi")) { } is Outcome.Failure)
        assertTrue(port.exec(ExecRequest(open(), "  ")) { } is Outcome.Failure)
    }

    @Test
    fun secondConcurrentExecIsBusy() {
        val id = open()
        val latch = CountDownLatch(1)
        val first = port.exec(ExecRequest(id, "sleep 3", timeoutMs = 10_000)) { chunk ->
            if (chunk.finished) latch.countDown()
        }
        assertTrue(first is Outcome.Success)
        val second = port.exec(ExecRequest(id, "echo x")) { }
        assertTrue(second is Outcome.Failure)
        assertEquals("TERMINAL_BUSY", (second as Outcome.Failure).error.code)
        (first as Outcome.Success<RunningHandle>).value.cancel()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun timeoutKillsAndNextExecRespawnsFresh() {
        val id = open()
        val (code, out) = runSync(id, "sleep 10", timeoutMs = 1_500)
        assertEquals(124, code)
        assertTrue(out.contains("timed out"))
        val (code2, out2) = runSync(id, "echo alive")
        assertEquals(0, code2)
        assertTrue(out2.contains("alive"))
    }

    @Test
    fun cancelStopsAndNextExecWorks() {
        val id = open()
        val latch = CountDownLatch(1)
        val out = StringBuilder()
        val started = port.exec(ExecRequest(id, "sleep 10", timeoutMs = 15_000)) { chunk ->
            synchronized(out) { out.append(chunk.text) }
            if (chunk.finished) latch.countDown()
        }
        assertTrue(started is Outcome.Success)
        Thread.sleep(400)
        (started as Outcome.Success<RunningHandle>).value.cancel()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(out.toString().contains("cancelled"))
        val (code2, out2) = runSync(id, "echo back")
        assertEquals(0, code2)
        assertTrue(out2.contains("back"))
    }

    @Test
    fun shellExitRespawnsEagerly() {
        val id = open()
        val (_, out) = runSync(id, "exit 7")
        assertTrue(out.contains("เชลล์ใหม่"))
        val (code2, out2) = runSync(id, "echo fresh")
        assertEquals(0, code2)
        assertTrue(out2.contains("fresh"))
    }

    @Test
    fun cwdHonoredAndBadCwdFailsHonestly() {
        val id = open()
        val (code, out) = runSync(id, "pwd", cwd = "/tmp")
        assertEquals(0, code)
        assertTrue(out.contains("/tmp"))
        val (badCode, badOut) = runSync(id, "pwd", cwd = "/nope-xyz-123")
        assertEquals(1, badCode)
        assertTrue(badOut.contains("cd ไม่ได้"))
    }

    @Test
    fun closeUnknownSessionFails() {
        assertTrue(port.closeSession("ghost") is Outcome.Failure)
    }
}
