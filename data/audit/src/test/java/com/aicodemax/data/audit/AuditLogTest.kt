package com.aicodemax.data.audit

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeClock(var tick: Long = 5000L) : Clock {
    override fun nowMillis(): Long = tick++
}

class AuditLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun appendAndQueryNewestFirst() {
        val log: AuditLog = FileAuditLog(tmp.root, FakeClock())
        log.append("AI", "tool.call", "terminal", "ls", allowed = true)
        log.append("USER", "permission.deny", "browser", "", allowed = false)
        log.append("SYSTEM", "boot", "", "", allowed = true)

        assertEquals(3L, log.count())
        val entries = (log.query(2) as Outcome.Success<List<AuditEntry>>).value
        assertEquals(2, entries.size)
        assertEquals("boot", entries[0].action)
        assertEquals("permission.deny", entries[1].action)
    }
}
