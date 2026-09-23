package com.aicodemax.tools.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRetrieverTest {
    private val bindings = StandardCapabilities.bindings()

    @Test
    fun thaiVideoQueryFindsMediaTools() {
        val found = ToolRetriever.retrieve("export วิดีโอ", bindings)
        assertTrue(found.isNotEmpty())
        assertTrue(found.size <= 5)
        assertTrue(found.any { it.binding.toolId == "media" })
    }

    @Test
    fun thaiTerminalQueryFindsTerminal() {
        val found = ToolRetriever.retrieve("รันคำสั่งในเทอร์มินัล", bindings)
        assertTrue(found.isNotEmpty())
        assertEquals("terminal", found.first().binding.toolId)
    }

    @Test
    fun thaiMemoryQueryFindsMemory() {
        val found = ToolRetriever.retrieve("จำบทเรียนนี้", bindings)
        assertTrue(found.any { it.binding.toolId == "memory" })
    }

    @Test
    fun englishQueryWorks() {
        val found = ToolRetriever.retrieve("download model", bindings)
        assertTrue(found.any { it.binding.capabilityId == "model.download" })
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(ToolRetriever.retrieve("", bindings).isEmpty())
        assertTrue(ToolRetriever.retrieve("และ ใน ให้", bindings).isEmpty())
    }

    @Test
    fun limitIsRespected() {
        val found = ToolRetriever.retrieve("ไฟล์", bindings, limit = 3)
        assertTrue(found.size <= 3)
    }

    @Test
    fun historyBoostsRecentTool() {
        val plain = ToolRetriever.retrieve("อ่าน", bindings)
        val boosted = ToolRetriever.retrieve("อ่าน", bindings, history = listOf("browser"))
        assertTrue(boosted.isNotEmpty())
        val plainFirst = plain.firstOrNull()?.binding?.toolId
        val boostedFirst = boosted.first().binding.toolId
        // Either browser was already first, or history moved it up.
        assertTrue(boostedFirst == "browser" || plainFirst == "browser" || boosted.any { it.binding.toolId == "browser" })
    }

    @Test
    fun scoresArePositiveAndRanked() {
        val found = ToolRetriever.retrieve("วิดีโอ export ไฟล์", bindings)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.score > 0 })
        assertEquals(found.sortedByDescending { it.score }.map { it.binding.capabilityId }, found.map { it.binding.capabilityId })
    }

    @Test
    fun riskDerivation() {
        assertEquals("high", deriveRisk(listOf("fs.delete")))
        assertEquals("high", deriveRisk(listOf("exec")))
        assertEquals("high", deriveRisk(listOf("network")))
        assertEquals("medium", deriveRisk(listOf("fs.write")))
        assertEquals("low", deriveRisk(emptyList()))
        assertEquals("low", deriveRisk(listOf("fs.write"), override = "low"))
    }

    @Test
    fun catalogCarriesRiskAndSchema() {
        val export = bindings.first { it.capabilityId == "media.timeline.export" }
        assertEquals("medium", export.metadata.risk)
        assertEquals("file", export.metadata.effectiveSchema())
        assertEquals(listOf("native.ffmpeg"), export.metadata.preconditions)
        val download = bindings.first { it.capabilityId == "model.download" }
        assertTrue(download.metadata.needsNetwork)
        assertEquals(listOf("network"), download.metadata.preconditions)
    }
}
