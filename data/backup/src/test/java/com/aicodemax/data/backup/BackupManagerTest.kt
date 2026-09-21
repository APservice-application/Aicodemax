package com.aicodemax.data.backup

import com.aicodemax.core.common.Outcome
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun exportImportRoundtrip() {
        val root = tmp.newFolder("state")
        File(root, "a.txt").writeText("hello")
        File(root, "sub").mkdir()
        File(root, "sub/b.txt").writeText("world")

        val manager = BackupManager(root)
        val zip = File(tmp.root, "backup.zip")
        val exported = (manager.exportZip(zip) as Outcome.Success<BackupInfo>).value
        assertEquals(2, exported.entries)
        assertTrue(zip.length() > 0)

        val restored = tmp.newFolder("restored")
        val imported = (BackupManager(restored).importZip(zip) as Outcome.Success<BackupInfo>).value
        assertEquals(2, imported.entries)
        assertEquals("hello", File(restored, "a.txt").readText())
        assertEquals("world", File(restored, "sub/b.txt").readText())
    }

    @Test
    fun zipSlipIsRejected() {
        val zip = File(tmp.root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../../evil.txt"))
            it.write("x".toByteArray())
            it.closeEntry()
        }
        val root = tmp.newFolder("victim")
        val result = BackupManager(root).importZip(zip)
        assertTrue(result is Outcome.Failure)
        assertTrue(File(tmp.root, "evil.txt").exists().not())
    }

    @Test
    fun fullBackupRoundtrip() {
        val convs = tmp.newFolder("convs")
        File(convs, "c.json").writeText("{}")
        val mem = tmp.newFolder("mem")
        File(mem, "m.json").writeText("[]")

        val manager = BackupManager(tmp.root)
        val zip = File(tmp.root, "full.zip")
        val exported = (manager.exportFull(zip, mapOf("conversations" to convs, "memory" to mem))
            as Outcome.Success<BackupInfo>).value
        assertEquals(2, exported.entries)

        val dest = tmp.newFolder("restore")
        val imported = (manager.importFull(zip, dest) as Outcome.Success<BackupInfo>).value
        assertEquals(2, imported.entries)
        assertEquals("{}", File(dest, "conversations/c.json").readText())
        assertEquals("[]", File(dest, "memory/m.json").readText())
    }

    @Test
    fun badSectionNameFailsHonestly() {
        val dir = tmp.newFolder("d")
        val result = BackupManager(tmp.root).exportFull(File(tmp.root, "x.zip"), mapOf("a/b" to dir))
        assertTrue(result is Outcome.Failure)
    }

    @Test
    fun listBackupsNewestFirst() {
        val dir = tmp.newFolder("backups")
        val old = File(dir, "old.zip").apply { writeBytes(byteArrayOf(1)); setLastModified(1000) }
        val new = File(dir, "new.zip").apply { writeBytes(byteArrayOf(2)); setLastModified(2000) }
        File(dir, "note.txt").writeText("ignored")

        val listed = BackupManager(tmp.root).listBackups(dir)
        assertEquals(listOf("new.zip", "old.zip"), listed.map { it.fileName })
        assertEquals(old.length(), listed[1].bytes)
        assertEquals(new.length(), listed[0].bytes)
    }
}
