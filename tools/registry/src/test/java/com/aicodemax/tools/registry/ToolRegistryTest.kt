package com.aicodemax.tools.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aicodemax.core.common.isSuccess
import com.aicodemax.core.common.isFailure

class ToolRegistryTest {
    private fun descriptor(id: String, runnable: Boolean): ToolDescriptor {
        val exec = if (runnable) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING
        return ToolDescriptor(
            toolId = id,
            displayName = id,
            version = "0.1",
            layers = CapabilityLayer.values().map {
                val status = if (it == CapabilityLayer.RUNTIME || it == CapabilityLayer.EXECUTION) {
                    exec
                } else {
                    CapabilityStatus.AVAILABLE
                }
                LayerCapability(it, status)
            },
        )
    }

    @Test
    fun registerAndQuery() {
        val registry: ToolRegistry = InMemoryToolRegistry()
        assertTrue(registry.register(descriptor("files", true)).isSuccess())
        assertTrue(registry.register(descriptor("browser", false)).isSuccess())
        // Duplicate registration must fail honestly.
        assertTrue(registry.register(descriptor("files", true)).isFailure())
        assertEquals(2, registry.all().size)
        assertEquals(listOf("files"), registry.runnable().map { it.toolId })
        assertTrue(registry.get("browser")?.isRunnable() == false)
    }

    @Test
    fun updateAndUnregister() {
        val registry: ToolRegistry = InMemoryToolRegistry()
        registry.register(descriptor("terminal", false))
        assertFalse(registry.get("terminal")!!.isRunnable())
        assertTrue(registry.update(descriptor("terminal", true)).isSuccess())
        assertTrue(registry.get("terminal")!!.isRunnable())
        assertTrue(registry.unregister("terminal"))
        assertEquals(null, registry.get("terminal"))
    }
}
