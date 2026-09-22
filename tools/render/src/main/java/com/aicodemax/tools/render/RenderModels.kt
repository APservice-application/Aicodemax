package com.aicodemax.tools.render

import kotlinx.serialization.Serializable

/**
 * CP-67 render queue models (§19–21: render + QC + approval + export).
 *
 * [RenderJob] is the persisted record: queue state, progress, QC report,
 * approval flag and export result. Jobs live as one JSON file each so a
 * crash never loses the queue.
 */
enum class RenderStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
}

/** Export preset (§21). v0: height cap + bitrate; fps stays source-driven. */
@Serializable
data class RenderPreset(
    val name: String,
    val maxHeight: Int,
    val videoBitrate: Int,
    val includeAudio: Boolean = true,
) {
    companion object {
        val PRESETS = listOf(
            RenderPreset("720p", 720, 4_000_000),
            RenderPreset("480p", 480, 2_000_000),
            RenderPreset("original", 1080, 8_000_000),
        )

        fun byName(name: String?): RenderPreset =
            PRESETS.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PRESETS[0]
    }
}

@Serializable
data class QcCheck(
    val name: String,
    val ok: Boolean,
    val detail: String = "",
)

@Serializable
data class QcReport(
    val passed: Boolean,
    val checks: List<QcCheck> = emptyList(),
) {
    companion object {
        fun single(name: String, ok: Boolean, detail: String = ""): QcReport {
            val check = QcCheck(name, ok, detail)
            return QcReport(ok, listOf(check))
        }
    }
}

@Serializable
data class RenderJob(
    val id: String,
    val projectId: String,
    val preset: RenderPreset,
    val status: RenderStatus,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val outputPath: String = "",
    val previews: List<String> = emptyList(),
    val qc: QcReport? = null,
    val error: String = "",
    val notes: List<String> = emptyList(),
    val approved: Boolean = false,
    val exportedUri: String = "",
)
