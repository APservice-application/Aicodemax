package com.aicodemax.tools.terminal

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    @Test
    fun createListMarkClose() {
        val registry = SessionRegistry(maxSessions = 2)
        val a = (registry.create("one") as Outcome.Success<TerminalSession>).value
        registry.create("two")
        assertTrue(registry.create("three") is Outcome.Failure)
        assertEquals(2, registry.list().size)

        val running = (registry.mark(a.id, SessionState.RUNNING) as Outcome.Success<TerminalSession>).value
        assertEquals(SessionState.RUNNING, running.state)
        assertTrue(registry.mark("ghost", SessionState.RUNNING) is Outcome.Failure)

        assertTrue(registry.close(a.id) is Outcome.Success)
        assertTrue(registry.close(a.id) is Outcome.Failure)
        assertEquals(1, registry.list().size)
    }
}
