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

    @Test
    fun queryFilteredSelects() {
        val log: AuditLog = FileAuditLog(tmp.root, FakeClock())
        log.append("AI", "tool.call", "terminal", "ls", allowed = true)
        log.append("AI", "tool.call", "browser", "open", allowed = false)
        log.append("USER", "permission.grant", "", "", allowed = true)

        val denied = (log.queryFiltered(AuditQuery(actor = "AI", deniedOnly = true))
            as Outcome.Success<List<AuditEntry>>).value
        assertEquals(1, denied.size)
        assertEquals("browser", denied[0].toolId)

        val calls = (log.queryFiltered(AuditQuery(actionPrefix = "tool."))
            as Outcome.Success<List<AuditEntry>>).value
        assertEquals(2, calls.size)

        val since = (log.queryFiltered(AuditQuery(sinceMillis = 5002L))
            as Outcome.Success<List<AuditEntry>>).value
        assertEquals(1, since.size)
        assertEquals("permission.grant", since[0].action)
    }

    @Test
    fun rotateArchivesOversizedLog() {
        val log: AuditLog = FileAuditLog(tmp.root, FakeClock())
        log.append("AI", "tool.call", "t", "x".repeat(200), allowed = true)

        assertEquals(null, (log.rotate(Long.MAX_VALUE) as Outcome.Success<java.io.File?>).value)
        val archived = (log.rotate(10L) as Outcome.Success<java.io.File?>).value
        checkNotNull(archived)
        assertEquals(true, archived.isFile)
        assertEquals(true, archived.name.startsWith("audit-"))
        // Active log starts fresh; the archive keeps the old entry.
        assertEquals(0L, log.count())
        log.append("AI", "after", "", "", allowed = true)
        assertEquals(1L, log.count())
    }
}
