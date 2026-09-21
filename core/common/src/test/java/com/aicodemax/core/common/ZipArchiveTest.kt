package com.aicodemax.core.common

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun zipUnzipRoundtrip() {
        val src = tmp.newFolder("src")
        File(src, "a.txt").writeText("hello")
        File(src, "sub").mkdir()
        File(src, "sub/b.txt").writeText("world")

        val zip = File(tmp.root, "out.zip")
        assertEquals(2, (ZipArchive.zipDir(src, zip) as Outcome.Success<Int>).value)

        val dest = tmp.newFolder("dest")
        assertEquals(2, (ZipArchive.unzip(zip, dest) as Outcome.Success<Int>).value)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world", File(dest, "sub/b.txt").readText())
    }

    @Test
    fun zipDirsPrefixesSections() {
        val a = tmp.newFolder("a")
        File(a, "x.txt").writeText("x")
        val b = tmp.newFolder("b")
        File(b, "y.txt").writeText("y")

        val zip = File(tmp.root, "multi.zip")
        val count = (ZipArchive.zipDirs(mapOf("alpha" to a, "beta" to b), zip) as Outcome.Success<Int>).value
        assertEquals(2, count)

        val dest = tmp.newFolder("dest")
        ZipArchive.unzip(zip, dest)
        assertEquals("x", File(dest, "alpha/x.txt").readText())
        assertEquals("y", File(dest, "beta/y.txt").readText())
        assertTrue(ZipArchive.zipDirs(mapOf("bad/name" to a), zip) is Outcome.Failure)
    }

    @Test
    fun zipSlipRejected() {
        val zip = File(tmp.root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../evil.txt"))
            it.write("x".toByteArray())
            it.closeEntry()
        }
        val dest = tmp.newFolder("dest")
        assertTrue(ZipArchive.unzip(zip, dest) is Outcome.Failure)
        assertTrue(File(tmp.root, "evil.txt").exists().not())
    }
}
