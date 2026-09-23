package com.aicodemax.tools.terminal_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.terminal.ExecChunk
import com.aicodemax.tools.terminal.ExecRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemShellPortTest {
    private val port = SystemShellPort()

    private fun openId(): String {
        val session = port.createSession("test")
        assertTrue(session is Outcome.Success)
        return (session as Outcome.Success).value.id
    }

    private data class Collected(val stdout: String, val stderr: String, val exitCode: Int?)

    private fun run(sessionId: String, command: String, timeoutMs: Long = 15_000): Collected {
        val out = StringBuilder()
        val err = StringBuilder()
        var code: Int? = null
        val done = CountDownLatch(1)
        val started = port.exec(
            ExecRequest(sessionId, command, timeoutMs = timeoutMs),
            { chunk: ExecChunk ->
                if (chunk.isStderr) err.append(chunk.text) else out.append(chunk.text)
                if (chunk.finished) {
                    code = chunk.exitCode
                    done.countDown()
                }
            },
        )
        assertTrue(started is Outcome.Success)
        assertTrue(done.await(timeoutMs + 10_000, TimeUnit.MILLISECONDS))
        return Collected(out.toString(), err.toString(), code)
    }

    @Test
    fun echoReturnsStdoutAndZeroExit() {
        val r = run(openId(), "echo hello-shell")
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("hello-shell"))
    }

    @Test
    fun failingCommandReportsNonZeroExit() {
        val r = run(openId(), "exit 3")
        assertEquals(3, r.exitCode)
    }

    @Test
    fun stderrIsSeparatedFromStdout() {
        val r = run(openId(), "echo out1; echo err1 1>&2")
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("out1"))
        assertTrue(r.stderr.contains("err1"))
    }

    @Test
    fun timeoutKillsLongCommand() {
        val r = run(openId(), "sleep 30", timeoutMs = 2_000)
        assertEquals(124, r.exitCode)
        assertTrue(r.stdout.contains("timed out"))
    }

    @Test
    fun unknownSessionFailsHonestly() {
        val done = CountDownLatch(1)
        val started = port.exec(ExecRequest("nope", "echo hi")) { done.countDown() }
        assertTrue(started is Outcome.Failure)
        assertEquals("TERMINAL_NO_SESSION", (started as Outcome.Failure).error.code)
    }

    @Test
    fun sessionLifecycleRoundTrip() {
        val id = openId()
        val listed = port.listSessions()
        assertTrue(listed is Outcome.Success)
        assertTrue((listed as Outcome.Success).value.any { it.id == id })
        assertTrue(port.closeSession(id) is Outcome.Success)
        val listed2 = (port.listSessions() as Outcome.Success).value
        assertTrue(listed2.none { it.id == id })
        val closeAgain = port.closeSession(id)
        assertTrue(closeAgain is Outcome.Failure)
    }

    @Test
    fun shellPathExists() {
        assertTrue(port.shellPath.isNotBlank())
        assertTrue(java.io.File(port.shellPath).canExecute())
    }

    @Test
    fun executorAutoOpensSession() {
        // Gateway path: exec without sessionId must still run (CP-113).
        val executor = TerminalToolExecutor(port)
        val call = com.aicodemax.tools.gateway.ToolCall("t1", "terminal", "exec", mapOf("command" to "echo auto-ok"))
        val result = kotlinx.coroutines.runBlocking { executor.execute(call) } as Outcome.Success
        assertTrue(result.value.ok)
        assertTrue(result.value.output.contains("auto-ok"))
    }
}
