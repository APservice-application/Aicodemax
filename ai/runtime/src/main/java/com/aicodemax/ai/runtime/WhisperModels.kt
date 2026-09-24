package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.runtime.ModelStore
import java.io.File

/**
 * CP-140: file-STT model (whisper.cpp `base`, ~148MB, MIT).
 * Same rule as the LLM: the weights are NEVER in git nor in the APK —
 * the app downloads them once from the official HuggingFace repo on
 * user request; transcription runs in-process via JNI.
 */
data class WhisperProfile(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val expectedBytes: Long,
    val licenseId: String,
) {
    fun toSpec(): ModelStore.ModelSpec =
        ModelStore.ModelSpec(displayName, url, fileName, expectedBytes)
}

object WhisperCatalog {
    val BASE = WhisperProfile(
        id = "whisper-base",
        displayName = "Whisper base (148MB)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        fileName = "ggml-base.bin",
        expectedBytes = 147_951_465L,
        licenseId = "MIT",
    )

    fun all(): List<WhisperProfile> = listOf(BASE)
}

sealed interface WhisperStatus {
    data class Ready(val path: String, val bytes: Long) : WhisperStatus
    data class Missing(val expectedPath: String) : WhisperStatus
    data class Invalid(val path: String, val reason: String) : WhisperStatus
}

/** Download + validate the STT model. Pure JVM (downloader injected). */
open class WhisperManager(
    private val modelsDir: String,
    private val downloader: ModelStore.Downloader = ModelStore.urlDownloader(),
    private val profiles: List<WhisperProfile> = WhisperCatalog.all(),
) {
    fun fileFor(profile: WhisperProfile): File = File(modelsDir, profile.fileName)

    fun list(): List<WhisperProfile> = profiles

    fun find(id: String): WhisperProfile? = profiles.firstOrNull { it.id == id }

    open fun status(profile: WhisperProfile): WhisperStatus {
        val file = fileFor(profile)
        if (!file.isFile || file.length() == 0L) return WhisperStatus.Missing(file.path)
        val min = (profile.expectedBytes * 0.95).toLong()
        if (file.length() < min) {
            return WhisperStatus.Invalid(file.path, "ไฟล์ไม่ครบ (${file.length()} bytes จาก ${profile.expectedBytes})")
        }
        return try {
            val magic = ByteArray(4)
            file.inputStream().use { input ->
                val n = input.read(magic)
                if (n < 4) return WhisperStatus.Invalid(file.path, "ไฟล์สั้นผิดปกติ")
            }
            // Classic ggml model magic: "ggml".
            if (magic[0] != 'g'.code.toByte() || magic[1] != 'g'.code.toByte() ||
                magic[2] != 'm'.code.toByte() || magic[3] != 'l'.code.toByte()
            ) {
                WhisperStatus.Invalid(file.path, "ไฟล์ไม่ใช่โมเดล ggml (magic ไม่ตรง)")
            } else {
                WhisperStatus.Ready(file.path, file.length())
            }
        } catch (e: Exception) {
            WhisperStatus.Invalid(file.path, e.message ?: "อ่านไฟล์ไม่ได้")
        }
    }

    fun install(
        profile: WhisperProfile,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Outcome<File> {
        ModelLicenses.requireRedistributable(profile.licenseId)
        return ModelStore.download(modelsDir, downloader, profile.toSpec(), onProgress).fold(
            onSuccess = { file ->
                when (val st = status(profile)) {
                    is WhisperStatus.Ready -> Outcome.Success(file)
                    is WhisperStatus.Invalid -> Outcome.Failure(
                        com.aicodemax.core.common.AppError("STT_INVALID", st.reason),
                    )
                    is WhisperStatus.Missing -> Outcome.Failure(
                        com.aicodemax.core.common.AppError("STT_INVALID", "โหลดเสร็จแต่ไม่เจอไฟล์"),
                    )
                }
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun delete(profile: WhisperProfile): Outcome<Unit> = runOutcome("STT_DELETE") {
        val file = fileFor(profile)
        if (file.isFile && !file.delete()) throw IllegalStateException("ลบไฟล์ไม่ได้: ${file.path}")
    }
}
