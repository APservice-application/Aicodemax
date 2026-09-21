package com.aicodemax.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {
    @Test
    fun idsAreUniqueAndPrefixed() {
        val ids = (1..1000).map { Ids.newId("task") }.toSet()
        assertEquals(1000, ids.size)
        assertTrue(ids.all { it.startsWith("task_") })
    }
}
