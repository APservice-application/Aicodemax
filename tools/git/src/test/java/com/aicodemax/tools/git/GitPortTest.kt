package com.aicodemax.tools.git

import org.junit.Assert.assertFalse
import org.junit.Test

class GitPortTest {
    @Test
    fun gitIsNotRunnableBeforePhase18() {
        val descriptor = gitDescriptorToday()
        assertFalse(descriptor.isRunnable())
        assertFalse(descriptor.missingReasons().isEmpty())
    }
}
