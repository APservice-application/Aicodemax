package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.runtime.ModelStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperModelsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val small = WhisperProfile(
        id = "test-small",
        displayName = "Test",
        url = "https://example.invalid/model.bin",
        fileName = "model.bin",
        expectedBytes = 100L,
        licenseId = "MIT",
    )

    private fun manager(downloader: ModelStore.Downloader = ModelStore.Downloader { _, _, _ -> }): WhisperManager =
        WhisperManager(tmp.root.path, downloader, listOf(small))

    private fun writeModel(bytes: ByteArray) {
        File(tmp.root, small.fileName).writeBytes(bytes)
    }

    private fun goodBytes(): ByteArray = "ggml".toByteArray() + ByteArray(96)

    @Test
    fun statusMissingWhenNoFile() {
        val st = manager().status(small)
        assertTrue(st is WhisperStatus.Missing)
    }

    @Test
    fun statusReadyWithGgmlMagic() {
        writeModel(goodBytes())
        val st = manager().status(small)
        assertTrue(st is WhisperStatus.Ready)
        assertEquals(100L, (st as WhisperStatus.Ready).bytes)
    }

    @Test
    fun statusInvalidOnBadMagic() {
        writeModel("XXXX".toByteArray() + ByteArray(96))
        val st = manager().status(small)
        assertTrue(st is WhisperStatus.Invalid)
    }

    @Test
    fun statusInvalidWhenTooSmall() {
        writeModel("ggml".toByteArray())
        assertTrue(manager().status(small) is WhisperStatus.Invalid)
    }

    @Test
    fun installDownloadsThenValidates() {
        val dl = ModelStore.Downloader { _, dest, _ -> dest.writeBytes(goodBytes()) }
        val res = manager(dl).install(small)
        assertTrue(res is Outcome.Success)
        assertTrue(manager().status(small) is WhisperStatus.Ready)
    }

    @Test
    fun installRejectsBadMagic() {
        val dl = ModelStore.Downloader { _, dest, _ ->
            dest.writeBytes("XXXX".toByteArray() + ByteArray(96))
        }
        val res = manager(dl).install(small)
        assertTrue(res is Outcome.Failure)
        assertEquals("STT_INVALID", (res as Outcome.Failure).error.code)
    }
}
