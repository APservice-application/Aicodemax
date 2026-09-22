package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaKind
import com.aicodemax.tools.capability.CapabilityResolver
import com.aicodemax.tools.capability.StandardCapabilities
import com.aicodemax.tools.media.MediaProjectPort

/**
 * CP-65: data-driven edit planning (MASTER_ARCHITECTURE §27 — no hard-coded
 * workflow). Reads platform presets ([EditPresets]) + project assets and emits
 * timeline steps through the [CapabilityResolver], so the same gates/audit as
 * every other plan apply. v0 assembly heuristic: videos in name order, stills
 * after, first audio as music bed; the LLM refines via media.* ACTION calls.
 */
data class EditPreset(
    val platform: String,
    /** Target cap for the whole cut. */
    val maxDurationMs: Long,
    /** Cap per source clip segment. */
    val maxClipMs: Long,
    /** Hold time per still image. */
    val stillMs: Long,
    val note: String,
)

object EditPresets {
    val tiktok = EditPreset("TikTok", 180_000, 30_000, 3_000, "แนวตั้ง สั้น-กระชับ")
    val facebook = EditPreset("Facebook", 240_000, 45_000, 3_000, "จั่วหัวไว 3 วิแรก")
    val youtube = EditPreset("YouTube", 600_000, 90_000, 4_000, "เล่าเรื่องได้ยาว")
    val self = EditPreset("เก็บไว้ดูเอง", 600_000, 120_000, 4_000, "ไม่จำกัดแพลตฟอร์ม")
    val default = EditPreset("default", 300_000, 60_000, 3_000, "ค่ากลาง")

    fun forPlatform(name: String?): EditPreset = when (name?.trim()) {
        "TikTok" -> tiktok
        "Facebook" -> facebook
        "YouTube" -> youtube
        "เก็บไว้ดูเอง" -> self
        else -> default
    }
}

class EditingPlanner(
    private val media: MediaProjectPort,
    private val resolver: CapabilityResolver = StandardCapabilities.defaultResolver(),
) {
    suspend fun plan(intent: UserIntent): Outcome<Plan> {
        val projectId = when (val list = media.listProjects()) {
            is Outcome.Failure -> return list
            is Outcome.Success -> list.value.firstOrNull()?.id
                ?: return Outcome.Failure(
                    AppError("PLAN_NO_PROJECT", "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่แล้วเพิ่มไฟล์ก่อนครับ"),
                )
        }
        val assets = when (val all = media.listAssets(projectId)) {
            is Outcome.Failure -> return all
            is Outcome.Success -> all.value
        }
        if (assets.isEmpty()) {
            return Outcome.Failure(
                AppError("PLAN_NO_ASSETS", "โปรเจกต์ยังไม่มีไฟล์ — เพิ่มไฟล์ก่อนครับ (เช่น เพิ่มไฟล์ a.mp4)"),
            )
        }
        val preset = EditPresets.forPlatform(intent.parameters["platform"])
        val goal = intent.parameters["goal"]
        val videos = assets.filter { it.kind == MediaKind.VIDEO }.sortedBy { it.originalName }
        val images = assets.filter { it.kind == MediaKind.IMAGE }.sortedBy { it.originalName }
        val audios = assets.filter { it.kind == MediaKind.AUDIO }.sortedBy { it.originalName }

        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var cursor = 0L
        for (video in videos) {
            val sourceDur = video.facts["durationMs"]?.toLongOrNull() ?: preset.maxClipMs
            val seg = minOf(sourceDur.coerceAtLeast(1000), preset.maxClipMs)
            requests.add(
                "media.timeline.addClip" to mapOf(
                    "projectId" to projectId,
                    "assetId" to video.id,
                    "startMs" to "0",
                    "endMs" to seg.toString(),
                    "atMs" to cursor.toString(),
                ),
            )
            cursor += seg
        }
        for (image in images) {
            requests.add(
                "media.timeline.addClip" to mapOf(
                    "projectId" to projectId,
                    "assetId" to image.id,
                    "startMs" to "0",
                    "endMs" to preset.stillMs.toString(),
                    "atMs" to cursor.toString(),
                ),
            )
            cursor += preset.stillMs
        }
        val music = audios.firstOrNull()
        if (music != null && cursor > 0) {
            val musicDur = music.facts["durationMs"]?.toLongOrNull() ?: cursor
            requests.add(
                "media.timeline.addClip" to mapOf(
                    "projectId" to projectId,
                    "assetId" to music.id,
                    "startMs" to "0",
                    "endMs" to minOf(musicDur, cursor).toString(),
                    "atMs" to "0",
                ),
            )
        }
        requests.add("media.version.save" to mapOf("projectId" to projectId))

        val steps = mutableListOf<PlanStep>()
        for ((capabilityId, args) in requests) {
            when (val resolved = resolver.resolve(capabilityId, args)) {
                is Outcome.Failure -> return resolved
                is Outcome.Success -> {
                    val cap = resolved.value
                    steps.add(PlanStep(Ids.newId("step"), cap.toolId, cap.action, args, description = capabilityId))
                }
            }
        }
        val over = if (cursor > preset.maxDurationMs) " (ยาวเกินเป้า ${preset.maxDurationMs / 1000}วิ — ตัดเพิ่มได้)" else ""
        val note = "แผน${preset.platform} (${preset.note})" +
            (if (goal != null) " เป้าหมาย: $goal" else "") +
            " — ${requests.size - 1} คลิป ยาว ~${cursor / 1000}วิ$over"
        return Outcome.Success(Plan(steps, note))
    }
}
