package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val tiny = ModelStore.ModelSpec("tiny", "http://x/y.gguf", "y.gguf", expectedBytes = 100L)

    @Test
    fun statusMissingWhenEmpty() {
        val st = ModelStore.status(tmp.root.path, tiny)
        assertTrue(st is ModelStore.ModelStatus.Missing)
        assertEquals((st as ModelStore.ModelStatus.Missing).url, "http://x/y.gguf")
    }

    @Test
    fun statusReadyWhenFilePresent() {
        java.io.File(tmp.root, "y.gguf").writeBytes(ByteArray(100))
        val st = ModelStore.status(tmp.root.path, tiny)
        assertTrue(st is ModelStore.ModelStatus.Ready)
        assertEquals(100L, (st as ModelStore.ModelStatus.Ready).bytes)
    }

    @Test
    fun downloadSkipsCompleteFile() {
        java.io.File(tmp.root, "y.gguf").writeBytes(ByteArray(100))
        var called = false
        val outcome = ModelStore.download(tmp.root.path, ModelStore.Downloader { _, _, _ -> called = true }, tiny)
        assertTrue(outcome is Outcome.Success)
        assertTrue(!called)
    }

    @Test
    fun downloadFetchesAndRenames() {
        var progress = 0L
        val outcome = ModelStore.download(
            tmp.root.path,
            ModelStore.Downloader { _, dest, onProgress ->
                dest.writeBytes(ByteArray(100))
                onProgress(100, 100)
            },
            tiny,
        ) { dn, _ -> progress = dn }
        assertTrue(outcome is Outcome.Success)
        assertEquals(100L, progress)
        assertTrue(java.io.File(tmp.root, "y.gguf").isFile)
        assertTrue(!java.io.File(tmp.root, "y.gguf.part").exists())
    }

    @Test
    fun downloadRejectsIncompleteFile() {
        val outcome = ModelStore.download(
            tmp.root.path,
            ModelStore.Downloader { _, dest, _ -> dest.writeBytes(ByteArray(10)) },
            tiny,
        )
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("ไม่ครบ"))
    }

    @Test
    fun pinnedModelRejectsCorruptDownloadsAndDoesNotReuseBadCache() {
        val valid = ByteArray(100) { 7 }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(valid)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val pinned = tiny.copy(expectedSha256 = sha)
        val dest = java.io.File(tmp.root, "y.gguf")
        val bad = ModelStore.download(tmp.root.path,
            ModelStore.Downloader { _, part, _ -> part.writeBytes(ByteArray(100)) }, pinned)
        assertTrue(bad is Outcome.Failure)
        assertTrue((bad as Outcome.Failure).error.message.contains("SHA-256"))
        assertTrue(!java.io.File(tmp.root, "y.gguf.part").exists())
        dest.writeBytes(ByteArray(100)) // same size, wrong hash must not be accepted
        var fetched = false
        val repaired = ModelStore.download(tmp.root.path,
            ModelStore.Downloader { _, part, _ -> fetched = true; part.writeBytes(valid) }, pinned)
        assertTrue(repaired is Outcome.Success)
        assertTrue(fetched)
        assertTrue(dest.readBytes().contentEquals(valid))
    }

    @Test
    fun downloaderErrorIsHonest() {
        val outcome = ModelStore.download(
            tmp.root.path,
            ModelStore.Downloader { _, _, _ -> throw IllegalStateException("net down") },
            tiny,
        )
        assertTrue(outcome is Outcome.Failure)
        assertTrue((outcome as Outcome.Failure).error.message.contains("net down"))
    }
}
