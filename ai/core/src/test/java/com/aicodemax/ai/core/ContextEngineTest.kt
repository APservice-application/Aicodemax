package com.aicodemax.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEngineTest {
    @Test
    fun everythingFits() {
        val sections = listOf(
            ContextSection("a", "hello", 1),
            ContextSection("b", "world", 0),
        )
        val result = ContextEngine.fit(sections, maxChars = 100)
        assertEquals(2, result.kept.size)
        assertTrue(result.droppedIds.isEmpty())
        assertEquals(0, result.truncatedChars)
    }

    @Test
    fun lowPriorityDropsFirst() {
        val sections = listOf(
            ContextSection("low", "l".repeat(80), 0),
            ContextSection("high", "h".repeat(80), 10),
        )
        val result = ContextEngine.fit(sections, maxChars = 100)
        assertEquals(listOf("high"), result.kept.map { it.id })
        assertEquals(listOf("low"), result.droppedIds)
    }

    @Test
    fun exactFitDropsRest() {
        val sections = listOf(
            ContextSection("a", "a".repeat(80), 2),
            ContextSection("b", "b".repeat(80), 1),
            ContextSection("c", "c".repeat(80), 0),
        )
        val result = ContextEngine.fit(sections, maxChars = 160)
        assertEquals(2, result.kept.size)
        assertEquals(80, result.kept[1].text.length)
        assertEquals(listOf("c"), result.droppedIds)
        assertEquals(0, result.truncatedChars)
    }

    @Test
    fun tinyRoomTruncatesHonestly() {
        val sections = listOf(ContextSection("big", "x".repeat(1000), 5))
        val result = ContextEngine.fit(sections, maxChars = 200)
        assertEquals(200, result.kept.single().text.length)
        assertEquals(800, result.truncatedChars)
        assertEquals(ContextEngine.estimateTokens("x".repeat(200)), result.estimatedTokens)
    }
}
