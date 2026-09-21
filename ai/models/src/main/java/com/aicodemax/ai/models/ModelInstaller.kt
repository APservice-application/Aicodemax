package com.aicodemax.ai.models

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** CP-07: model install/download/pause/health-check (registry stays the source of truth for status). */
enum class InstallState { NOT_INSTALLED, QUEUED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED, CANCELLED }

data class InstallProgress(
    val modelId: String,
    val state: InstallState,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val detail: String = "",
)

data class HealthReport(
    val modelId: String,
    val healthy: Boolean,
    val detail: String,
)

interface ModelDownloader {
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (done: Long, total: Long) -> Unit,
        controls: DownloadControls,
    ): Outcome<File>
}

interface DownloadControls {
    fun isPaused(): Boolean
    fun isCancelled(): Boolean
}

class JavaNetModelDownloader(
    private val timeoutMs: Int = 30_000,
) : ModelDownloader {
    override suspend fun download(
        url: String,
        destFile: File,
        onProgress: (done: Long, total: Long) -> Unit,
        controls: DownloadControls,
    ): Outcome<File> = runOutcome("MODEL_DOWNLOAD") {
        check(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) model urls: '$url'" }
        destFile.parentFile?.mkdirs()
        val connection = URL(url).openConnection()
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        val total = connection.contentLengthLong.coerceAtLeast(0)
        var done = 0L
        connection.getInputStream().buffered().use { input ->
            destFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (controls.isCancelled()) throw IllegalStateException("download cancelled")
                    while (controls.isPaused()) {
                        if (controls.isCancelled()) throw IllegalStateException("download cancelled")
                        Thread.sleep(100)
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
        }
        destFile
    }
}

class ModelInstaller(
    private val registry: ModelRegistry,
    private val downloader: ModelDownloader,
    modelsDir: File,
) {
    private val base: File = modelsDir.apply { mkdirs() }
    private val states = ConcurrentHashMap<String, InstallProgress>()
    private val paused = ConcurrentHashMap<String, Boolean>()
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    fun progress(modelId: String): InstallProgress =
        states[modelId] ?: InstallProgress(modelId, InstallState.NOT_INSTALLED)

    fun pause(modelId: String) {
        // Only active downloads can pause — terminal states are left alone.
        val current = states[modelId]?.state
        if (current == InstallState.DOWNLOADING || current == InstallState.QUEUED) {
            paused[modelId] = true
            states.computeIfPresent(modelId) { _, progress -> progress.copy(state = InstallState.PAUSED) }
        }
    }

    fun resume(modelId: String) {
        paused[modelId] = false
        states.computeIfPresent(modelId) { _, progress ->
            if (progress.state == InstallState.PAUSED) progress.copy(state = InstallState.DOWNLOADING)
            else progress
        }
    }

    fun cancel(modelId: String) {
        cancelled[modelId] = true
    }

    fun modelFile(modelId: String): File = File(base, "$modelId.bin")

    suspend fun install(modelId: String, url: String): Outcome<InstallProgress> {
        val descriptor = registry.get(modelId)
            ?: return Outcome.Failure(AppError("MODEL_UNKNOWN", "model '$modelId' not registered"))
        states[modelId] = InstallProgress(modelId, InstallState.QUEUED)
        registry.update(descriptor.copy(status = ModelStatus.DOWNLOADING, statusDetail = "queued"))
        paused[modelId] = false
        cancelled[modelId] = false
        states[modelId] = InstallProgress(modelId, InstallState.DOWNLOADING)
        val controls = object : DownloadControls {
            override fun isPaused(): Boolean = paused[modelId] == true
            override fun isCancelled(): Boolean = cancelled[modelId] == true
        }
        val dest = modelFile(modelId)
        val downloaded = downloader.download(
            url,
            dest,
            onProgress = { done, total ->
                val current = states[modelId]
                if (current != null && current.state == InstallState.DOWNLOADING) {
                    states[modelId] = current.copy(bytesDone = done, bytesTotal = total)
                }
            },
            controls = controls,
        )
        return downloaded.fold(
            onSuccess = { file ->
                states[modelId] = InstallProgress(modelId, InstallState.VERIFYING)
                if (!file.isFile || file.length() <= 0) {
                    registry.update(descriptor.copy(status = ModelStatus.ERROR, statusDetail = "empty download"))
                    states[modelId] = InstallProgress(modelId, InstallState.FAILED, detail = "empty download")
                    return Outcome.Failure(AppError("MODEL_EMPTY", "downloaded file is empty"))
                }
                registry.update(descriptor.copy(status = ModelStatus.READY, statusDetail = "installed"))
                val done = InstallProgress(modelId, InstallState.READY, file.length(), file.length())
                states[modelId] = done
                Outcome.Success(done)
            },
            onFailure = { error ->
                val wasCancel = cancelled[modelId] == true
                val state = if (wasCancel) InstallState.CANCELLED else InstallState.FAILED
                registry.update(descriptor.copy(status = ModelStatus.ERROR, statusDetail = error.message))
                states[modelId] = InstallProgress(modelId, state, detail = error.message)
                Outcome.Failure(error)
            },
        )
    }

    /** Health-check: file present + non-empty + size matches registry when known. */
    fun healthCheck(modelId: String): Outcome<HealthReport> {
        val descriptor = registry.get(modelId)
            ?: return Outcome.Failure(AppError("MODEL_UNKNOWN", "model '$modelId' not registered"))
        val file = modelFile(modelId)
        if (!file.isFile) {
            registry.update(descriptor.copy(status = ModelStatus.ERROR, statusDetail = "file missing"))
            return Outcome.Success(HealthReport(modelId, false, "file missing: ${file.path}"))
        }
        if (file.length() <= 0) {
            registry.update(descriptor.copy(status = ModelStatus.ERROR, statusDetail = "file empty"))
            return Outcome.Success(HealthReport(modelId, false, "file empty"))
        }
        if (descriptor.sizeBytes > 0 && file.length() != descriptor.sizeBytes) {
            registry.update(descriptor.copy(status = ModelStatus.ERROR, statusDetail = "size mismatch"))
            return Outcome.Success(
                HealthReport(modelId, false, "size ${file.length()} != expected ${descriptor.sizeBytes}"),
            )
        }
        if (descriptor.status == ModelStatus.ERROR) {
            registry.update(descriptor.copy(status = ModelStatus.READY, statusDetail = "recovered"))
        }
        return Outcome.Success(HealthReport(modelId, true, "ok (${file.length()} bytes)"))
    }
}
