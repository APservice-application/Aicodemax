package com.aicodemax.ai.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageLayoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ensureCreatesAllDirs() {
        val layout = StorageLayout(tmp.root).ensureDirs()
        listOf(layout.runtime, layout.modelsDefault, layout.modelsOptional, layout.tools, layout.workspaces, layout.cache, layout.logs, layout.agent)
            .forEach { assertTrue(it.isDirectory) }
    }

    @Test
    fun migrateMovesLegacyModelAndWorkspace() {
        File(tmp.root, "models").mkdirs()
        File(tmp.root, "models/old.gguf").writeBytes(byteArrayOf(1))
        File(tmp.root, "workspace/proj").mkdirs()
        File(tmp.root, "workspace/proj/a.txt").writeText("a")

        val migration = StorageLayout(tmp.root).migrate()
        assertEquals(2, migration.moved.size)
        assertTrue(File(tmp.root, "models/default/old.gguf").isFile)
        assertTrue(File(tmp.root, "workspaces/proj/a.txt").isFile)
        assertTrue(!File(tmp.root, "models/old.gguf").exists())

        // Idempotent: second run moves nothing.
        val again = StorageLayout(tmp.root).migrate()
        assertTrue(again.moved.isEmpty())
    }
}
