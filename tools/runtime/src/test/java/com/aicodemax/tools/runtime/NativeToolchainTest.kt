package com.aicodemax.tools.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeToolchainTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolvePointsIntoNativeLibDir() {
        val paths = NativeToolchain.resolve("/data/app/lib/arm64")
        assertEquals(2, paths.size)
        assertEquals(File("/data/app/lib/arm64/libffmpeg.so"), paths["ffmpeg"])
        assertEquals(File("/data/app/lib/arm64/libffprobe.so"), paths["ffprobe"])
    }

    @Test
    fun missingDirReportsHonestly() {
        val report = NativeToolchain.detect(null) { _, _ -> "x" }
        assertEquals(2, report.size)
        assertTrue(report.values.all { it.startsWith("missing:") })
    }

    @Test
    fun missingFilesReportPerTool() {
        val report = NativeToolchain.detect(tmp.root.path) { _, _ -> "x" }
        assertTrue(report["ffmpeg"]!!.startsWith("missing:"))
    }

    @Test
    fun executableFileGetsVersionLine() {
        val fake = File(tmp.root, "libffmpeg.so")
        fake.writeText("#!/bin/sh\necho fake\n")
        fake.setExecutable(true)
        val report = NativeToolchain.detect(tmp.root.path) { exe, args ->
            assertTrue(exe.endsWith("libffmpeg.so"))
            assertEquals(listOf("-version"), args)
            "ffmpeg version 6.1-fake\nbuilt with libopenh264"
        }
        assertEquals("ffmpeg version 6.1-fake", report["ffmpeg"])
        assertTrue(report["ffprobe"]!!.startsWith("missing:"))
    }

    @Test
    fun formatListsAllTools() {
        val text = NativeToolchain.format(mapOf("a" to "1", "b" to "2"))
        assertTrue(text.contains("• a: 1"))
        assertTrue(text.contains("• b: 2"))
    }
}
