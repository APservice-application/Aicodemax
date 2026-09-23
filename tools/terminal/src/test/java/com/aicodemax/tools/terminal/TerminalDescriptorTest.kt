package com.aicodemax.tools.terminal

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TerminalDescriptorTest {
    @Test
    fun terminalIsRunnableWithSystemShell() {
        val descriptor = terminalDescriptorToday()
        assertTrue(descriptor.isRunnable())
        assertEquals(
            CapabilityStatus.AVAILABLE,
            descriptor.layerStatus(CapabilityLayer.RUNTIME),
        )
        assertEquals(
            CapabilityStatus.AVAILABLE,
            descriptor.layerStatus(CapabilityLayer.EXECUTION),
        )
        // Every non-available layer must still explain why (honesty rule).
        val incomplete = descriptor.layers.filter { it.status != CapabilityStatus.AVAILABLE }
        assertTrue(incomplete.all { it.reason.isNotBlank() })
        assertFalse(incomplete.any { it.status == CapabilityStatus.MISSING })
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
