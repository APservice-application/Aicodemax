package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.core.common.runOutcome
import com.aicodemax.tools.runtime.ModelStore
import java.io.File

/**
 * CP-125 (spec Phase 7 + §24–25): model profiles, install/validate/switch/
 * delete, and the license gate. Downloads go through [ModelStore].
 */
object ModelLicenses {
    data class License(val id: String, val url: String, val redistributable: Boolean)

    val APACHE_2_0 = License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", true)
    val MIT = License("MIT", "https://opensource.org/licenses/MIT", true)

    private val known = mapOf(APACHE_2_0.id to APACHE_2_0, MIT.id to MIT)

    fun get(id: String): License? = known[id]

    /** Only allowlisted licenses may ship as the default model (§25). */
    fun requireRedistributable(licenseId: String) {
        val lic = known[licenseId]
            ?: throw IllegalArgumentException("license ไม่รู้จัก ($licenseId) — ตรวจสอบสิทธิ์ก่อน (§25)")
        if (!lic.redistributable) throw IllegalArgumentException("license ${lic.id} แจกต่อไม่ได้")
    }
}

enum class ModelPack { DEFAULT, OPTIONAL }

data class ModelProfile(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val version: String,
    val licenseId: String,
    val quantization: String,
    val architecture: String,
    val params: String,
    val family: String = "qwen25",
    val pack: ModelPack = ModelPack.OPTIONAL,
    val expectedBytes: Long? = null,
    val minRamMb: Int = 2048,
    val ctxTrain: Int = 32768,
) {
    fun toSpec(): ModelStore.ModelSpec =
        ModelStore.ModelSpec(displayName, url, fileName, expectedBytes)
}

object ModelCatalog {
    private const val HF = "https://huggingface.co/Qwen"

    val DEFAULT_MODEL = ModelProfile(
        id = "qwen2.5-0.5b-q4_k_m",
        displayName = "Qwen2.5 0.5B (Q4_K_M)",
        url = "$HF/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        version = "1.0",
        licenseId = "Apache-2.0",
        quantization = "Q4_K_M",
        architecture = "qwen2",
        params = "0.5B",
        pack = ModelPack.DEFAULT,
        expectedBytes = 397_000_000L,
        minRamMb = 2048,
    )

    val OPTIONAL_1_5B = ModelProfile(
        id = "qwen2.5-1.5b-q4_k_m",
        displayName = "Qwen2.5 1.5B (Q4_K_M)",
        url = "$HF/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        version = "1.0",
        licenseId = "Apache-2.0",
        quantization = "Q4_K_M",
        architecture = "qwen2",
        params = "1.5B",
        expectedBytes = 1_000_000_000L,
        minRamMb = 4096,
    )

    fun all(): List<ModelProfile> = listOf(DEFAULT_MODEL, OPTIONAL_1_5B)
}

sealed interface ModelInstallStatus {
    data class Ready(val path: String, val bytes: Long, val info: Gguf.Info) : ModelInstallStatus
    data class Missing(val expectedPath: String) : ModelInstallStatus
    data class Invalid(val path: String, val reason: String) : ModelInstallStatus
}

class ModelManager(
    private val defaultDir: String,
    private val optionalDir: String,
    private val downloader: ModelStore.Downloader = ModelStore.urlDownloader(),
    private val profiles: List<ModelProfile> = ModelCatalog.all(),
) {
    private fun dirFor(profile: ModelProfile): String =
        if (profile.pack == ModelPack.DEFAULT) defaultDir else optionalDir

    fun fileFor(profile: ModelProfile): File = File(dirFor(profile), profile.fileName)

    fun list(): List<ModelProfile> = profiles

    fun find(id: String): ModelProfile? = profiles.firstOrNull { it.id == id }

    fun status(profile: ModelProfile): ModelInstallStatus {
        val file = fileFor(profile)
        if (!file.isFile || file.length() == 0L) return ModelInstallStatus.Missing(file.path)
        return try {
            val info = Gguf.read(file)
            val archOk = info.architecture == null || info.architecture == profile.architecture
            if (!archOk) {
                ModelInstallStatus.Invalid(file.path, "architecture ไม่ตรง (ไฟล์: ${info.architecture}, ต้องการ: ${profile.architecture})")
            } else {
                ModelInstallStatus.Ready(file.path, file.length(), info)
            }
        } catch (e: Exception) {
            ModelInstallStatus.Invalid(file.path, e.message ?: "อ่าน GGUF ไม่ได้")
        }
    }

    fun install(
        profile: ModelProfile,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Outcome<File> {
        if (profile.pack == ModelPack.DEFAULT) {
            try {
                ModelLicenses.requireRedistributable(profile.licenseId)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(com.aicodemax.core.common.AppError("MODEL_LICENSE", e.message ?: "license"))
            }
        }
        return ModelStore.download(dirFor(profile), downloader, profile.toSpec(), onProgress).fold(
            onSuccess = { file ->
                when (val st = status(profile)) {
                    is ModelInstallStatus.Ready -> Outcome.Success(file)
                    is ModelInstallStatus.Invalid -> Outcome.Failure(
                        com.aicodemax.core.common.AppError("MODEL_INVALID", st.reason),
                    )
                    is ModelInstallStatus.Missing -> Outcome.Failure(
                        com.aicodemax.core.common.AppError("MODEL_INVALID", "โหลดเสร็จแต่ไม่เจอไฟล์"),
                    )
                }
            },
            onFailure = { Outcome.Failure(it) },
        )
    }

    fun delete(profile: ModelProfile): Outcome<Unit> = runOutcome("MODEL_DELETE") {
        val file = fileFor(profile)
        if (file.isFile && !file.delete()) throw IllegalStateException("ลบไฟล์ไม่ได้: ${file.path}")
    }

    // -- active model ------------------------------------------------------

    private fun activeFile(): File = File(defaultDir, "..").canonicalFile.resolve("active_model.txt")

    fun activeId(): String? {
        val f = activeFile()
        return if (f.isFile) f.readText().trim().ifEmpty { null } else null
    }

    /** Switch the active model (file must validate first). */
    fun setActive(id: String): Outcome<ModelProfile> = runOutcome("MODEL_SWITCH") {
        val profile = find(id) ?: throw IllegalArgumentException("ไม่รู้จักโมเดล: $id")
        when (val st = status(profile)) {
            is ModelInstallStatus.Ready -> {
                activeFile().apply { parentFile?.mkdirs() }.writeText(id)
                profile
            }
            is ModelInstallStatus.Missing -> throw IllegalStateException("โมเดลยังไม่ติดตั้ง: $id")
            is ModelInstallStatus.Invalid -> throw IllegalStateException("โมเดลใช้ไม่ได้: ${st.reason}")
        }
    }

    fun active(): ModelProfile? = activeId()?.let { find(it) } ?: profiles.firstOrNull { it.pack == ModelPack.DEFAULT }
}
