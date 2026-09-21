package com.aicodemax.data.audit

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-53: volume behavior — thousands of entries stay queryable and rotatable. */
class AuditVolumeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun twoThousandEntriesQueryable() {
        val log: AuditLog = FileAuditLog(tmp.newFolder("audit"))
        repeat(2000) { index ->
            val result = log.append(
                actor = if (index % 2 == 0) "AI" else "USER",
                action = "tool.call",
                toolId = "files",
                detail = "op-$index",
                allowed = index % 10 != 0,
            )
            assertTrue(result is Outcome.Success)
        }
        assertEquals(2000L, log.count())
        val page = (log.query(100) as Outcome.Success<List<AuditEntry>>).value
        assertEquals(100, page.size)
        assertEquals("op-1999", page[0].detail)
        val denied = (log.queryFiltered(AuditQuery(deniedOnly = true, limit = 500))
            as Outcome.Success<List<AuditEntry>>).value
        assertEquals(200, denied.size)
    }

    @Test
    fun rotationKeepsServiceAvailable() {
        val log: AuditLog = FileAuditLog(tmp.newFolder("audit"))
        repeat(200) { log.append("AI", "tool.call", "t", "x".repeat(100)) }
        val archived = (log.rotate(1024L) as Outcome.Success<java.io.File?>).value
        checkNotNull(archived)
        assertTrue(archived.length() > 1024L)
        log.append("AI", "after-rotation", "", "")
        assertEquals(1L, log.count())
        assertEquals(
            "after-rotation",
            (log.query(5) as Outcome.Success<List<AuditEntry>>).value.single().action,
        )
    }
}
