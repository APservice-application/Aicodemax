package com.aicodemax.tools.git

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitPortTest {
    @Test
    fun gitIsRunnableWithJGitRuntime() {
        val descriptor = gitDescriptorToday()
        assertTrue(descriptor.isRunnable())
        assertEquals(CapabilityStatus.AVAILABLE, descriptor.layerStatus(CapabilityLayer.RUNTIME))
        assertEquals(CapabilityStatus.AVAILABLE, descriptor.layerStatus(CapabilityLayer.EXECUTION))
    }
}
