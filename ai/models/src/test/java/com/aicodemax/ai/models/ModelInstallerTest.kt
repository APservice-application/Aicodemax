package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Fake downloader: writes [bytes] in chunks, honoring pause/cancel like the real one. */
    private class FakeDownloader(
        private val bytes: ByteArray = "model-bytes".toByteArray(),
        private val chunkDelayMs: Long = 0,
    ) : ModelDownloader {
        var calls = 0
        override suspend fun download(
            url: String,
            destFile: File,
            onProgress: (done: Long, total: Long) -> Unit,
            controls: DownloadControls,
        ): Outcome<File> {
            calls += 1
            if (!url.startsWith("https://")) {
                return Outcome.Failure(com.aicodemax.core.common.AppError("URL", "bad url"))
            }
            destFile.parentFile?.mkdirs()
            destFile.outputStream().buffered().use { output ->
                var done = 0
                for (chunk in bytes.asList().chunked(2)) {
                    if (controls.isCancelled()) {
                        return Outcome.Failure(com.aicodemax.core.common.AppError("CANCEL", "download cancelled"))
                    }
                    var waited = 0
                    while (controls.isPaused() && waited < 5_000) {
                        Thread.sleep(50)
                        waited += 50
                    }
                    if (controls.isPaused()) {
                        return Outcome.Failure(com.aicodemax.core.common.AppError("PAUSED", "still paused"))
                    }
                    output.write(chunk.toByteArray())
                    done += chunk.size
                    onProgress(done.toLong(), bytes.size.toLong())
                    if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                }
            }
            return Outcome.Success(destFile)
        }
    }

    private fun registryWith(vararg models: ModelDescriptor): ModelRegistry {
        val registry = InMemoryModelRegistry()
        models.forEach { registry.register(it) }
        return registry
    }

    @Test
    fun installMarksRegistryReady() = runBlocking {
        val registry = registryWith(ModelDescriptor("m1", "M1", ModelKind.LOCAL_FULL, capabilities = listOf("chat")))
        val installer = ModelInstaller(registry, FakeDownloader(), tmp.root)
        val done = (installer.install("m1", "https://cdn.example.com/m.bin") as Outcome.Success<InstallProgress>).value
        assertEquals(InstallState.READY, done.state)
        assertEquals(ModelStatus.READY, registry.get("m1")?.status)
        assertTrue(installer.modelFile("m1").isFile)
        assertTrue(installer.install("ghost", "https://x/y") is Outcome.Failure)
    }

    @Test
    fun pauseResumeCompletes() {
        val registry = registryWith(ModelDescriptor("m1", "M1", ModelKind.LOCAL_FULL))
        val installer = ModelInstaller(registry, FakeDownloader(chunkDelayMs = 120), tmp.root)
        var result: Outcome<InstallProgress>? = null
        // Real thread (not a coroutine): the main thread must stay free to pause mid-stream.
        val worker = kotlin.concurrent.thread {
            result = runBlocking { installer.install("m1", "https://cdn.example.com/m.bin") }
        }
        // Wait until the download is provably in flight, then pause mid-stream.
        var waited = 0
        while (installer.progress("m1").state != InstallState.DOWNLOADING && waited < 10_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(InstallState.DOWNLOADING, installer.progress("m1").state)
        installer.pause("m1")
        assertEquals(InstallState.PAUSED, installer.progress("m1").state)
        Thread.sleep(200)
        assertEquals(InstallState.PAUSED, installer.progress("m1").state)
        installer.resume("m1")
        worker.join(10_000)
        assertTrue(result is Outcome.Success)
        assertEquals(InstallState.READY, (result as Outcome.Success<InstallProgress>).value.state)
    }

    @Test
    fun healthDetectsProblems() {
        val registry = registryWith(ModelDescriptor("m1", "M1", ModelKind.LOCAL_FULL, sizeBytes = 11))
        val installer = ModelInstaller(registry, FakeDownloader(), tmp.root)
        val missing = (installer.healthCheck("m1") as Outcome.Success<HealthReport>).value
        assertTrue(!missing.healthy)

        installer.modelFile("m1").writeBytes("short".toByteArray())
        val mismatch = (installer.healthCheck("m1") as Outcome.Success<HealthReport>).value
        assertTrue(!mismatch.healthy)
        assertEquals(ModelStatus.ERROR, registry.get("m1")?.status)

        installer.modelFile("m1").writeBytes("model-bytes".toByteArray())
        val ok = (installer.healthCheck("m1") as Outcome.Success<HealthReport>).value
        assertTrue(ok.healthy)
        assertEquals(ModelStatus.READY, registry.get("m1")?.status)
        assertTrue(installer.healthCheck("ghost") is Outcome.Failure)
    }
}
