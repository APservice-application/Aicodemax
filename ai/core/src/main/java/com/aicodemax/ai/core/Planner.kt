package com.aicodemax.ai.core

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.capability.CapabilityResolver
import com.aicodemax.tools.capability.StandardCapabilities

data class PlanStep(
    val id: String,
    val toolId: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    val needsPermission: Boolean = false,
    val description: String = "",
)

data class Plan(
    val steps: List<PlanStep>,
    val note: String = "",
)

interface Planner {
    suspend fun plan(intent: UserIntent): Outcome<Plan>
}

/**
 * Rule-based v0 planner — thinks in CAPABILITIES, never in shell commands
 * (MASTER_ARCHITECTURE §3/§61/§63). Every step goes through the
 * [CapabilityResolver], which picks native engines first and the terminal
 * CLI adapter only as a last resort (or honest BLOCKED when nothing runs).
 */
class RuleBasedPlanner(
    private val resolver: CapabilityResolver = StandardCapabilities.defaultResolver(),
    private val editPlanner: EditingPlanner? = null,
) : Planner {
    /** Capabilities that always need explicit user permission. */
    private val sensitive = setOf("files.delete", "skill.remove")

    override suspend fun plan(intent: UserIntent): Outcome<Plan> {
        val requests = when (intent.type) {
            IntentType.CREATE_FILE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a file name, e.g. สร้างไฟล์ notes.txt: hello"),
                    )
                val content = intent.parameters["content"] ?: ""
                listOf(
                    "editor.set" to mapOf("path" to path, "content" to content),
                    "editor.save" to mapOf("path" to path),
                )
            }
            IntentType.READ_FILE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a file name, e.g. อ่านไฟล์ notes.txt"),
                    )
                listOf("files.read" to mapOf("path" to path))
            }
            IntentType.LIST_FILES -> listOf(
                "files.list" to mapOf("path" to (intent.parameters["path"] ?: "")),
            )
            IntentType.MAKE_DIR -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a folder name, e.g. mkdir folder docs"),
                    )
                listOf("files.mkdir" to mapOf("path" to path))
            }
            IntentType.DELETE_PATH -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "please include a path to delete"),
                    )
                listOf("files.delete" to mapOf("path" to path))
            }
            IntentType.RUN_COMMAND -> listOf(
                "terminal.exec" to mapOf(
                    "command" to (intent.parameters["command"] ?: ""),
                    "sessionId" to (intent.parameters["sessionId"] ?: ""),
                ),
            )
            IntentType.OPEN_URL -> {
                val url = intent.parameters["url"]?.trim().orEmpty()
                if (url.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_URL", "please include a URL, e.g. เปิดเว็บ example.com"),
                    )
                }
                listOf("browser.open" to mapOf("url" to url))
            }
            IntentType.BUILD_PROJECT -> listOf("build.project" to emptyMap())
            IntentType.RUN_TESTS -> listOf("test.run" to emptyMap())
            IntentType.GIT_ACTION -> gitRequests(intent) ?: return gitFailure(intent)
            IntentType.SEARCH_FILES -> {
                val query = intent.parameters["query"]?.trim().orEmpty()
                if (query.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_QUERY", "ค้นหาอะไรครับ? เช่น ค้นหา TODO"))
                }
                listOf("files.search" to mapOf("query" to query))
            }
            IntentType.BROWSER_OPEN -> {
                val url = intent.parameters["url"]?.trim().orEmpty()
                if (url.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_URL", "เปิดเว็บไหนครับ? เช่น เปิดดู example.com"))
                }
                listOf("browser.open" to mapOf("url" to url))
            }
            IntentType.BROWSER_CLOSE -> {
                val tabId = intent.parameters["tabId"]?.trim().orEmpty()
                if (tabId.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_TAB", "ปิดแท็บไหนครับ? เช่น ปิดแท็บ 1"))
                }
                listOf("browser.close" to mapOf("tabId" to tabId))
            }
            IntentType.BROWSER_LIST -> listOf("browser.list" to emptyMap())
            IntentType.DEBUG_CODE -> {
                val error = intent.parameters["error"]?.trim().orEmpty()
                if (error.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_ERROR", "วาง error มาเลยครับ เช่น แก้บั๊ก: NullPointerException ..."),
                    )
                }
                listOf("debug.analyze" to mapOf("error" to error))
            }
            IntentType.MEMORY_SAVE -> {
                val key = intent.parameters["key"]?.trim().orEmpty()
                val value = intent.parameters["value"]?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_MEMORY", "บันทึกอะไรครับ? เช่น บันทึก wifi: รหัส 1234"),
                    )
                }
                listOf("memory.save" to mapOf("key" to key, "value" to value))
            }
            IntentType.MEMORY_RECALL -> {
                val key = intent.parameters["key"]?.trim().orEmpty()
                if (key.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_MEMORY", "ถามเรื่องอะไรครับ? เช่น ความจำ wifi"))
                }
                listOf("memory.recall" to mapOf("key" to key))
            }
            IntentType.VOICE_SPEAK -> {
                val say = intent.parameters["text"]?.trim().orEmpty()
                if (say.isBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_VOICE", "ให้พูดว่าอะไรครับ? เช่น อ่านให้ฟัง: สวัสดีตอนเช้า"),
                    )
                }
                listOf("voice.speak" to mapOf("text" to say))
            }
            IntentType.VOICE_LISTEN -> listOf("voice.listen" to emptyMap())
            IntentType.PROJECT_NEW -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["name"]?.let { args["name"] = it }
                listOf("media.project.create" to args)
            }
            IntentType.PROJECT_LIST -> listOf("media.project.list" to emptyMap())
            IntentType.ASSET_IMPORT -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_MEDIA_FILE", "เพิ่มไฟล์ไหนครับ? เช่น เพิ่มไฟล์ a.mp4"),
                    )
                listOf("media.asset.import" to mapOf("path" to path))
            }
            IntentType.PROJECT_VERSION -> listOf("media.version.save" to emptyMap())
            IntentType.PROJECT_RESTORE -> {
                val version = intent.parameters["version"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VERSION", "ย้อนไปเวอร์ชันไหนครับ? เช่น ย้อนเวอร์ชัน 1"),
                    )
                listOf("media.version.restore" to mapOf("version" to version))
            }
            IntentType.SUBTITLE_MAKE -> {
                val transcript = intent.parameters["transcript"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_TRANSCRIPT", "ทำซับว่าอะไรครับ? เช่น ทำซับ a.wav: สวัสดีครับทุกคน"),
                    )
                val args = mutableMapOf("transcript" to transcript)
                intent.parameters["path"]?.let { args["mediaPath"] = it }
                intent.parameters["durationMs"]?.let { args["durationMs"] = it }
                listOf("subtitle.make" to args)
            }
            IntentType.SUBTITLE_SHIFT -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_SUB", "เลื่อนซับไฟล์ไหนครับ? เช่น เลื่อนซับ a.srt 500"),
                    )
                val offset = intent.parameters["offsetMs"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_OFFSET", "เลื่อนกี่มิลลิวินาทีครับ? เช่น เลื่อนซับ a.srt 500"),
                    )
                listOf("subtitle.shift" to mapOf("src" to path, "offsetMs" to offset))
            }
            IntentType.SUBTITLE_BURN -> {
                val src = intent.parameters["src"]
                val srt = intent.parameters["srt"]
                if (src == null || srt == null) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_BURN", "บอกไฟล์วิดีโอกับซับครับ เช่น ฝังซับ a.mp4 a.srt"),
                    )
                }
                listOf("subtitle.burn" to mapOf("src" to src, "srt" to srt))
            }
            IntentType.CLIP_SPLIT -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                val at = intent.parameters["atMs"]
                if (clip == null || at == null) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_SPLIT", "แยกคลิปที่เท่าไหร่ ตรงไหนครับ? เช่น แยกคลิปที่ 1 นาทีที่ 2"),
                    )
                }
                listOf("media.timeline.splitClip" to mapOf("clipIndex" to clip, "atMs" to at))
            }
            IntentType.CLIP_TRIM -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                val start = intent.parameters["startMs"]
                val end = intent.parameters["endMs"]
                if (clip == null || (start == null && end == null)) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_TRIM", "ทริมคลิปที่เท่าไหร่ครับ? เช่น ทริมคลิปที่ 1 เริ่ม 5 วิ จบ 20 วิ"),
                    )
                }
                val args = mutableMapOf("clipIndex" to clip)
                start?.let { args["startMs"] = it }
                end?.let { args["endMs"] = it }
                listOf("media.timeline.trimClip" to args)
            }
            IntentType.CLIP_MOVE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                val to = intent.parameters["toAtMs"]
                if (clip == null || to == null) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_MOVE", "ย้ายคลิปที่เท่าไหร่ ไปตรงไหนครับ? เช่น ย้ายคลิปที่ 2 ไปนาทีที่ 1"),
                    )
                }
                listOf("media.timeline.moveClip" to mapOf("clipIndex" to clip, "toAtMs" to to))
            }
            IntentType.CLIP_DELETE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ลบคลิปที่เท่าไหร่ครับ? เช่น ลบคลิปที่ 2"),
                    )
                listOf("media.timeline.deleteClip" to mapOf("clipIndex" to clip))
            }
            IntentType.CLIP_DUPLICATE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "สำเนาคลิปที่เท่าไหร่ครับ? เช่น สำเนาคลิปที่ 1"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["atMs"]?.let { args["atMs"] = it }
                listOf("media.timeline.duplicateClip" to args)
            }
            IntentType.CLIP_ROTATE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "หมุนคลิปที่เท่าไหร่ครับ? เช่น หมุนคลิปที่ 1 90"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["rotation"]?.let { args["rotation"] = it }
                listOf("media.timeline.transformClip" to args)
            }
            IntentType.CLIP_FLIP -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "พลิกคลิปที่เท่าไหร่ครับ? เช่น พลิกคลิปที่ 1"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["flipH"]?.let { args["flipH"] = it }
                intent.parameters["flipV"]?.let { args["flipV"] = it }
                listOf("media.timeline.transformClip" to args)
            }
            IntentType.CLIP_FREEZE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ฟรีซคลิปที่เท่าไหร่ครับ? เช่น ฟรีซคลิปที่ 1 3 วิ"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["holdMs"]?.let { args["holdMs"] = it }
                listOf("media.timeline.freezeFrame" to args)
            }
            IntentType.TEXT_ADD -> {
                val content = intent.parameters["text"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_TEXT", "ใส่ข้อความว่าอะไรครับ? เช่น เพิ่มข้อความ: เปิดร้านแล้ว!"),
                    )
                val args = mutableMapOf("text" to content)
                intent.parameters["preset"]?.let { args["preset"] = it }
                intent.parameters["startMs"]?.let { args["startMs"] = it }
                listOf("media.timeline.addText" to args)
            }
            IntentType.TEXT_REMOVE -> {
                val index = intent.parameters["textIndex"] ?: intent.parameters["textId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_TEXT", "ลบข้อความที่เท่าไหร่ครับ? ดูเลขจาก ดูไทม์ไลน์"),
                    )
                listOf("media.timeline.removeText" to mapOf("textIndex" to index))
            }
            IntentType.TEXT_IDEA -> {
                val args = mutableMapOf("kind" to (intent.parameters["kind"] ?: "caption"))
                intent.parameters["topic"]?.let { args["topic"] = it }
                intent.parameters["platform"]?.let { args["platform"] = it }
                listOf("media.text.ideas" to args)
            }
            IntentType.MARKER_ADD -> {
                val at = intent.parameters["atMs"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_MARKER", "ปักมาร์กเกอร์ตรงไหนครับ? เช่น มาร์กเกอร์ไฮไลต์ นาทีที่ 2"),
                    )
                val args = mutableMapOf("atMs" to at)
                intent.parameters["label"]?.let { args["label"] = it }
                listOf("media.timeline.addMarker" to args)
            }
            IntentType.MARKER_REMOVE -> {
                val id = intent.parameters["markerId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_MARKER", "ลบมาร์กเกอร์ไหนครับ? ดูรหัสจาก timeline.get ก่อนครับ"),
                    )
                listOf("media.timeline.removeMarker" to mapOf("markerId" to id))
            }
            IntentType.TRACK_FLAGS -> {
                val track = intent.parameters["trackId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_TRACK", "แทร็กไหนครับ? เช่น ล็อกแทร็ก V1"),
                    )
                val args = mutableMapOf("trackId" to track)
                intent.parameters["locked"]?.let { args["locked"] = it }
                intent.parameters["muted"]?.let { args["muted"] = it }
                intent.parameters["hidden"]?.let { args["hidden"] = it }
                listOf("media.timeline.trackFlags" to args)
            }
            IntentType.PROJECT_RENAME -> {
                val name = intent.parameters["name"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_NAME", "ตั้งชื่อใหม่ว่าอะไรครับ? เช่น เปลี่ยนชื่อโปรเจกต์ ทริปทะเล"),
                    )
                listOf("media.project.rename" to mapOf("name" to name))
            }
            IntentType.PROJECT_DELETE -> listOf("media.project.delete" to emptyMap())
            IntentType.PROJECT_DUPLICATE -> listOf("media.project.duplicate" to emptyMap())
            IntentType.PROJECT_CHECKPOINT -> listOf("media.checkpoint.save" to emptyMap())
            IntentType.EDIT_UNDO -> listOf("media.edit.undo" to emptyMap())
            IntentType.EDIT_REDO -> listOf("media.edit.redo" to emptyMap())
            IntentType.RENDER_START -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["preset"]?.let { args["preset"] = it }
                listOf("render.runNow" to args)
            }
            IntentType.RENDER_STATUS -> listOf("render.list" to emptyMap())
            IntentType.RENDER_APPROVE -> listOf("render.approve" to emptyMap())
            IntentType.RENDER_EXPORT, IntentType.SHARE_MEDIA ->
                listOf("render.export" to emptyMap())
            IntentType.VIDEO_INFO -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VIDEO", "ดูวิดีโอไฟล์ไหนครับ? เช่น ข้อมูลวิดีโอ clip.mp4"),
                    )
                listOf("video.info" to mapOf("path" to path))
            }
            IntentType.VIDEO_TRIM -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VIDEO", "ตัดวิดีโอไฟล์ไหนครับ? เช่น ตัดวิดีโอ a.mp4 0,10000"),
                    )
                val start = intent.parameters["startMs"]
                val end = intent.parameters["endMs"]
                if (start == null || end == null) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_RANGE", "บอกช่วงเวลาด้วยครับ (มิลลิวินาที) เช่น ตัดวิดีโอ a.mp4 0,10000"),
                    )
                }
                listOf("video.trim" to mapOf("src" to path, "startMs" to start, "endMs" to end))
            }
            IntentType.VIDEO_THUMB -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VIDEO", "จับภาพปกไฟล์ไหนครับ? เช่น ภาพปก a.mp4 2000"),
                    )
                val args = mutableMapOf("src" to path)
                intent.parameters["timeMs"]?.let { args["timeMs"] = it }
                listOf("video.thumbnail" to args)
            }
            IntentType.VIDEO_AUDIO -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VIDEO", "ดึงเสียงไฟล์ไหนครับ? เช่น ดึงเสียง a.mp4"),
                    )
                listOf("video.extractAudio" to mapOf("src" to path))
            }
            IntentType.AUDIO_INFO -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_AUDIO", "ดูเสียงไฟล์ไหนครับ? เช่น ข้อมูลเสียง song.mp3"),
                    )
                listOf("audio.info" to mapOf("path" to path))
            }
            IntentType.AUDIO_TRIM -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_AUDIO", "ตัดเสียงไฟล์ไหนครับ? เช่น ตัดเสียง a.wav 0,5000"),
                    )
                val start = intent.parameters["startMs"]
                val end = intent.parameters["endMs"]
                if (start == null || end == null) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_RANGE", "บอกช่วงเวลาด้วยครับ (มิลลิวินาที) เช่น ตัดเสียง a.wav 0,5000"),
                    )
                }
                listOf("audio.trim" to mapOf("src" to path, "startMs" to start, "endMs" to end))
            }
            IntentType.AUDIO_CONCAT -> {
                val srcs = intent.parameters["srcs"]
                if (srcs.isNullOrBlank()) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_AUDIO", "ต่อไฟล์ไหนบ้างครับ? เช่น ต่อเสียง a.wav b.wav"),
                    )
                }
                listOf("audio.concat" to mapOf("srcs" to srcs))
            }
            IntentType.AUDIO_GAIN -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_AUDIO", "ปรับเสียงไฟล์ไหนครับ? เช่น เร่งเสียง a.wav 6"),
                    )
                val db = intent.parameters["db"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_DB", "ปรับกี่ dB ครับ? เช่น เร่งเสียง a.wav 6 (เบาเสียง a.wav 6 = -6)"),
                    )
                listOf("audio.gain" to mapOf("src" to path, "db" to db))
            }
            IntentType.AUDIO_FADE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_AUDIO", "เฟดไฟล์ไหนครับ? เช่น เฟดเสียง a.wav 1000,2000"),
                    )
                val args = mutableMapOf("src" to path)
                intent.parameters["inMs"]?.let { args["inMs"] = it }
                intent.parameters["outMs"]?.let { args["outMs"] = it }
                listOf("audio.fade" to args)
            }
            IntentType.IMAGE_INFO -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "ดูรูปไหนครับ? เช่น ข้อมูลรูป photo.png"),
                    )
                listOf("image.info" to mapOf("path" to path))
            }
            IntentType.IMAGE_RESIZE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "ย่อรูปไหนครับ? เช่น ย่อรูป photo.png เหลือ 800"),
                    )
                val args = mutableMapOf("src" to path)
                intent.parameters["maxDim"]?.let { args["maxDim"] = it }
                listOf("image.resize" to args)
            }
            IntentType.IMAGE_CROP -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "ครอปรูปไหนครับ? เช่น ครอปรูป a.png 10,20,100,100"),
                    )
                val x = intent.parameters["x"]
                val y = intent.parameters["y"]
                val w = intent.parameters["w"]
                val h = intent.parameters["h"]
                if (x == null || y == null || w == null || h == null) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_CROP", "บอกกรอบด้วยครับ เช่น ครอปรูป a.png 10,20,100,100 (x,y,กว้าง,สูง)"),
                    )
                }
                listOf("image.crop" to mapOf("src" to path, "x" to x, "y" to y, "w" to w, "h" to h))
            }
            IntentType.IMAGE_ROTATE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "หมุนรูปไหนครับ? เช่น หมุนรูป a.png 90"),
                    )
                val args = mutableMapOf("src" to path)
                intent.parameters["degrees"]?.let { args["degrees"] = it }
                listOf("image.rotate" to args)
            }
            IntentType.IMAGE_GRAY -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "ทำรูปไหนให้ขาวดำครับ? เช่น รูปขาวดำ a.png"),
                    )
                listOf("image.grayscale" to mapOf("src" to path))
            }
            IntentType.SKILL_LIST -> listOf("skill.list" to emptyMap())
            IntentType.SKILL_GET -> {
                val id = intent.parameters["id"]?.trim().orEmpty()
                if (id.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_SKILL", "อ่านสกิลไหนครับ? เช่น สกิล aicode-tools"))
                }
                listOf("skill.get" to mapOf("id" to id))
            }
            IntentType.SKILL_REMOVE -> {
                val id = intent.parameters["id"]?.trim().orEmpty()
                if (id.isBlank()) {
                    return Outcome.Failure(AppError("PLAN_NO_SKILL", "ลบสกิลไหนครับ? เช่น ลบสกิล my-note"))
                }
                listOf("skill.remove" to mapOf("id" to id))
            }
            // Chat-handled or pending-engine intents: honest guidance, no fake steps.
            IntentType.STOP_TASK -> return Outcome.Failure(
                AppError("PLAN_STOP", "กดปุ่มหยุดในแชท/Task Center ได้เลยครับ งานจะหยุดทันที"),
            )
            IntentType.SYSTEM_STATUS -> return Outcome.Failure(
                AppError("PLAN_STATUS", "ดูสถานะเครื่องได้ที่หน้า Home ครับ"),
            )
            IntentType.OPEN_SETTINGS -> return Outcome.Failure(
                AppError("PLAN_SETTINGS", "เปิดหน้า Settings ที่แถบล่าง (ไอคอนฟันเฟือง) ได้เลยครับ"),
            )
            IntentType.MEDIA_EDIT -> {
                val planner = editPlanner
                if (planner == null) {
                    return Outcome.Failure(
                        AppError(
                            "PLAN_MEDIA_PENDING",
                            "งานตัดย่อยทำได้แล้วครับ (ตัดวิดีโอ/ดึงเสียง/ภาพปก/ย่อรูป/ตัดเสียง) — ส่วนตัดต่อเต็มรูปแบบตาม timeline มาใน CP-64..67 ครับ",
                        ),
                    )
                }
                return planner.plan(intent)
            }
            IntentType.LLM_CONNECT -> return Outcome.Failure(
                AppError(
                    "PLAN_LLM_GUIDE",
                    "ใส่ base URL + API key + model ที่หน้า Models แล้วกดเชื่อมต่อได้เลยครับ (key อยู่ในหน่วยความจำเท่านั้น ไม่ต้องพิมพ์ในแชท)",
                ),
            )
            IntentType.CHAT, IntentType.UNKNOWN -> return Outcome.Failure(
                AppError("PLAN_NOT_ACTIONABLE", "nothing to plan for chat"),
            )
        }

        val steps = mutableListOf<PlanStep>()
        for ((capabilityId, args) in requests) {
            when (val resolved = resolver.resolve(capabilityId, args)) {
                is Outcome.Failure -> return resolved
                is Outcome.Success -> {
                    val cap = resolved.value
                    steps.add(
                        PlanStep(
                            Ids.newId("step"),
                            cap.toolId,
                            cap.action,
                            cap.args,
                            needsPermission = capabilityId in sensitive,
                            description = capabilityId,
                        ),
                    )
                }
            }
        }
        return Outcome.Success(Plan(steps))
    }

    private fun gitRequests(intent: UserIntent): List<Pair<String, Map<String, String>>>? {
        val action = intent.parameters["action"] ?: "status"
        val repo = intent.parameters["repo"] ?: ""
        if (action == "commit") {
            val message = intent.parameters["message"]?.trim().orEmpty()
            if (message.isBlank()) return null
            return listOf(
                "git.stage" to mapOf("repo" to repo),
                "git.commit" to mapOf("repo" to repo, "message" to message),
            )
        }
        if (action !in setOf("status", "log", "ensure", "stage")) return null
        return listOf("git.$action" to mapOf("repo" to repo))
    }

    private fun gitFailure(intent: UserIntent): Outcome<Plan> {
        val action = intent.parameters["action"] ?: "status"
        return if (action == "commit") {
            Outcome.Failure(
                AppError("PLAN_NO_MESSAGE", "please include a message, e.g. git commit -m \"done\""),
            )
        } else {
            Outcome.Failure(
                AppError("PLAN_UNSUPPORTED", "git $action ยังไม่รองรับ (รองรับ: status / log / commit)"),
            )
        }
    }
}
