package com.aicodemax.tools.terminal

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TerminalDescriptorTest {
    @Test
    fun terminalIsNotRunnableBeforePhase16() {
        val descriptor = terminalDescriptorToday()
        assertFalse(descriptor.isRunnable())
        assertEquals(
            CapabilityStatus.AVAILABLE,
            descriptor.layerStatus(CapabilityLayer.CAPABILITY_API),
        )
        assertEquals(
            CapabilityStatus.MISSING,
            descriptor.layerStatus(CapabilityLayer.RUNTIME),
        )
        // Every missing layer must explain why (honesty rule).
        val missing = descriptor.layers.filter { it.status == CapabilityStatus.MISSING }
        assertFalse(missing.isEmpty())
        assertTrue(missing.all { it.reason.isNotBlank() })
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
