package com.aicodemax.tools.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionManagerTest {
    @Test
    fun grantsDenyAndSnapshot() {
        val manager: PermissionManager = InMemoryPermissionManager()
        manager.decide("files", "read", "t1", PermissionDecision.ALLOW_FOR_TASK)
        manager.decide("browser", "open", "t1", PermissionDecision.ALLOW_ONCE)
        manager.decide("terminal", "exec", "t1", PermissionDecision.DENY)

        assertTrue(manager.consumeGrant("files", "read", "t1"))
        assertTrue(manager.consumeGrant("browser", "open", "t1"))
        assertTrue(!manager.consumeGrant("browser", "open", "t1")) // one-shot spent
        assertTrue(manager.isDenied("terminal", "exec"))

        val snap = manager.snapshot()
        assertEquals(listOf("terminal.exec"), snap.denies)
        assertEquals(1, snap.taskGrants)
        assertEquals(0, snap.oneShots)

        manager.revokeTask("t1")
        assertEquals(0, manager.snapshot().taskGrants)
        manager.revokeAll()
        assertTrue(manager.snapshot().denies.isEmpty())
    }
}
