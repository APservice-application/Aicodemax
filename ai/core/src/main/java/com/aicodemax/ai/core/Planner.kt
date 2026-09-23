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
            IntentType.SUBTITLE_TRANSLATE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_SUB", "แปลซับไฟล์ไหนครับ? เช่น แปลซับ a.srt เป็นอังกฤษ"),
                    )
                val direction = intent.parameters["direction"] ?: "th-en"
                val args = mutableMapOf("src" to path, "direction" to direction)
                intent.parameters["engine"]?.let { args["engine"] = it }
                listOf("subtitle.translate" to args)
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
            IntentType.CLIP_SPEED -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ปรับความเร็วคลิปที่เท่าไหร่ครับ? เช่น สปีดคลิปที่ 1 200"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["rate"]?.let { args["rate"] = it }
                listOf("media.timeline.setSpeed" to args)
            }
            IntentType.TRANSITION_SET -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ใส่ทรานซิชันคลิปที่เท่าไหร่ครับ? เช่น ทรานซิชันคลิปที่ 2 เฟด"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["edge"]?.let { args["edge"] = it }
                intent.parameters["kind"]?.let { args["kind"] = it }
                listOf("media.timeline.setTransition" to args)
            }
            IntentType.CLIP_FX -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ใส่เอฟเฟกต์คลิปที่เท่าไหร่ครับ? เช่น เบลอคลิปที่ 1 5"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                for (k in listOf("blur", "vignette", "grain")) {
                    intent.parameters[k]?.let { args[k] = it }
                }
                if (args.size < 2) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_FX", "เอฟเฟกต์อะไรครับ? เบลอ/วิกเน็ต/เกรน + ระดับ เช่น เบลอคลิปที่ 1 5"),
                    )
                }
                listOf("media.timeline.setFx" to args)
            }
            IntentType.TRACK -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "แทร็กคลิปที่เท่าไหร่ครับ? เช่น แทร็กคลิปที่ 1"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["target"]?.let { args["target"] = it }
                listOf("media.timeline.track" to args)
            }
            IntentType.STABILIZE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "กันสั่นคลิปที่เท่าไหร่ครับ? เช่น กันสั่นคลิปที่ 1"),
                    )
                listOf("media.timeline.stabilize" to mapOf("clipIndex" to clip))
            }
            IntentType.COLOR_AUTO -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ออโต้สีคลิปที่เท่าไหร่ครับ? เช่น ออโต้สีคลิปที่ 1"),
                    )
                listOf("media.timeline.colorAuto" to mapOf("clipIndex" to clip))
            }
            IntentType.LUT_SET -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ใส่ LUT ให้คลิปที่เท่าไหร่ครับ? เช่น ใส่ LUT คลิปที่ 1 ไฟล์ warm.cube"),
                    )
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ใช้ไฟล์ .cube ไหนครับ? เช่น ใส่ LUT คลิปที่ 1 ไฟล์ warm.cube"),
                    )
                listOf("media.timeline.lut" to mapOf("clipIndex" to clip, "path" to path))
            }
            IntentType.LUT_CLEAR -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ล้าง LUT คลิปที่เท่าไหร่ครับ?"),
                    )
                listOf("media.timeline.lutClear" to mapOf("clipIndex" to clip))
            }
            IntentType.TEMPLATE_SAVE -> {
                val name = intent.parameters["name"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_NAME", "ตั้งชื่อเทมเพลตว่าอะไรครับ? เช่น บันทึกเทมเพลต \"เปิดคลิป\""),
                    )
                listOf("media.template.save" to mapOf("name" to name))
            }
            IntentType.TEMPLATE_APPLY -> {
                val name = intent.parameters["name"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_NAME", "ใช้เทมเพลตไหนครับ? เช่น ใช้เทมเพลต \"Social Hook\""),
                    )
                listOf("media.template.apply" to mapOf("name" to name))
            }
            IntentType.TEMPLATE_LIST -> listOf("media.template.list" to emptyMap())
            IntentType.TEMPLATE_DELETE -> {
                val name = intent.parameters["name"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_NAME", "ลบเทมเพลตไหนครับ? (ดูชื่อจาก: ดูเทมเพลต)"),
                    )
                listOf("media.template.delete" to mapOf("templateId" to name))
            }
            IntentType.LIB_SEARCH -> {
                val q = intent.parameters["query"]
                if (q == null) listOf("media.library.list" to emptyMap())
                else listOf("media.library.search" to mapOf("query" to q))
            }
            IntentType.GEN_MAKE -> {
                val kind = intent.parameters["kind"] ?: "poster"
                val args = mutableMapOf("kind" to kind)
                intent.parameters["prompt"]?.let { args["prompt"] = it }
                intent.parameters["path"]?.let { args["path"] = it }
                if (args["prompt"] == null && (kind == "poster" || kind == "tts")) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_PROMPT", "บอกข้อความหน่อยครับ เช่น ทำโปสเตอร์ \"เปิดร้าน\""),
                    )
                }
                if (args["path"] == null && kind == "stylize") {
                    return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "แต่งรูปไหนครับ? (ระบุชื่อไฟล์รูป)"),
                    )
                }
                if (args["path"] == null && kind == "thumbnail") {
                    return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ทำปกจากวิดีโอไหนครับ? เช่น ทำปกคลิป v.mp4 เปิดร้าน"),
                    )
                }
                listOf("media.gen.make" to args)
            }
            IntentType.GEN_LIST -> listOf("media.gen.list" to emptyMap())
            IntentType.CLIP_MOTION -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ใส่โมชันให้คลิปที่เท่าไหร่ครับ? เช่น โมชันคลิปที่ 1 ซูมเข้า"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["dir"]?.let { args["dir"] = it }
                intent.parameters["off"]?.let { args["off"] = it }
                listOf("media.timeline.motion" to args)
            }
            IntentType.SLIDESHOW -> {
                val assets = intent.parameters["assets"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_ASSETS", "สไลด์โชว์ใช้รูปไหนบ้างครับ? (บอกชื่อไฟล์ เช่น สไลด์โชว์ a.png,b.png)"),
                    )
                listOf("media.timeline.slideshow" to mapOf("assetIds" to assets))
            }
            IntentType.BEATS -> {
                val clip = intent.parameters["clipIndex"]
                if (clip != null) {
                    listOf("media.timeline.beatsToMarkers" to mapOf("clipIndex" to clip))
                } else {
                    val path = intent.parameters["path"]
                        ?: return Outcome.Failure(
                            AppError("PLAN_NO_FILE", "จับจังหวะไฟล์ไหนครับ? (เช่น จับจังหวะเพลง song.wav หรือ จับจังหวะคลิปที่ 1)"),
                        )
                    listOf("audio.beats" to mapOf("src" to path))
                }
            }
            IntentType.CLIP_VOLUME -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ตั้งเสียงคลิปที่เท่าไหร่ครับ? เช่น เสียงคลิปที่ 1 80"),
                    )
                val volume = intent.parameters["volume"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VALUE", "เสียงเท่าไหร่ครับ (0-100)? เช่น เสียงคลิปที่ 1 80"),
                    )
                listOf("media.timeline.volume" to mapOf("clipIndex" to clip, "volume" to volume))
            }
            IntentType.VOICE_FX -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "เปลี่ยนเสียงไฟล์ไหนครับ? เช่น เปลี่ยนเสียงพูด a.wav เสียงแหลม"),
                    )
                val args = mutableMapOf("src" to path)
                intent.parameters["semitones"]?.let { args["semitones"] = it }
                intent.parameters["robot"]?.let { args["robot"] = it }
                listOf("audio.voicefx" to args)
            }
            IntentType.SYNTH_MUSIC -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["style"]?.let { args["style"] = it }
                intent.parameters["seconds"]?.let { args["seconds"] = it }
                listOf("audio.synthmusic" to args)
            }
            IntentType.SYNTH_SFX -> {
                listOf("audio.synthsfx" to mapOf("kind" to (intent.parameters["kind"] ?: "impact")))
            }
            IntentType.AUTOCUT -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ตัดเงียบคลิปที่เท่าไหร่ครับ? เช่น ตัดเงียบคลิปที่ 1"),
                    )
                listOf("media.timeline.autocut" to mapOf("clipIndex" to clip))
            }
            IntentType.HIGHLIGHTS -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "หาช็อตเด่นคลิปที่เท่าไหร่ครับ? เช่น ช็อตเด่นคลิปที่ 1"),
                    )
                listOf("media.timeline.highlights" to mapOf("clipIndex" to clip))
            }
            IntentType.REFRAME -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "รีเฟรมคลิปที่เท่าไหร่ครับ? เช่น รีเฟรมคลิปที่ 1 เป็นแนวตั้ง"),
                    )
                val step = mutableMapOf("clipIndex" to clip)
                intent.parameters["aspect"]?.let { step["aspect"] = it }
                intent.parameters["subjectX"]?.let { step["subjectX"] = it }
                intent.parameters["subjectY"]?.let { step["subjectY"] = it }
                intent.parameters["punch"]?.let { step["punch"] = it }
                listOf("media.timeline.reframe" to step)
            }
            IntentType.SET_CANVAS -> {
                val aspect = intent.parameters["aspect"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VALUE", "ตั้งแคนวาสเป็นสัดส่วนไหนครับ? (16:9/9:16/1:1/4:5)"),
                    )
                listOf("media.timeline.setCanvas" to mapOf("aspect" to aspect))
            }
            IntentType.COLOR_MATCH -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "จับคู่สีคลิปที่เท่าไหร่ครับ? เช่น จับคู่สีคลิปที่ 2 ตามคลิปที่ 1"),
                    )
                val ref = intent.parameters["refIndex"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_REF", "ให้ตามสีคลิปที่เท่าไหร่ครับ? เช่น จับคู่สีคลิปที่ 2 ตามคลิปที่ 1"),
                    )
                listOf("media.timeline.colorMatch" to mapOf("clipIndex" to clip, "refIndex" to ref))
            }
            IntentType.COLOR_WB -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ปรับไวต์บาลานซ์คลิปที่เท่าไหร่ครับ? เช่น ไวต์บาลานซ์คลิปที่ 1 อัตโนมัติ"),
                    )
                when (intent.parameters["preset"]) {
                    "warm" -> listOf("media.timeline.setColor" to mapOf("clipIndex" to clip, "temperature" to "30", "tint" to "5"))
                    "cool" -> listOf("media.timeline.setColor" to mapOf("clipIndex" to clip, "temperature" to "-30"))
                    else -> listOf("media.timeline.colorAuto" to mapOf("clipIndex" to clip))
                }
            }
            IntentType.CLIP_ENHANCE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ปรับปรุงคลิปที่เท่าไหร่ครับ? เช่น ปรับปรุงคลิปที่ 1"),
                    )
                listOf("media.timeline.enhance" to mapOf("clipIndex" to clip))
            }
            // CP-92 §36 honest defer: 3D camera solve needs a solver we don't ship yet.
            IntentType.CAM_TRACK -> {
                return Outcome.Failure(
                    AppError(
                        "DEFERRED_CAMTRACK",
                        "การแทร็กกล้อง 3D ยังไม่รองรับครับ (ต้องใช้ตัวแก้การเคลื่อนกล้อง 3 มิติที่ยังไม่มี) — ตอนนี้ใช้ ติดตามวัตถุ 2D แทนได้ เช่น ติดตามวัตถุคลิปที่ 1",
                    ),
                )
            }
            // CP-93 honest defer: face-aware beauty needs an on-device face model.
            IntentType.BEAUTY -> {
                return Outcome.Failure(
                    AppError(
                        "DEFERRED_BEAUTY",
                        "โหมดบิวตี้ (ตรวจจับใบหน้า+ปรับผิวเนียน) ยังไม่รองรับครับ — ตอนนี้ใช้ ปรับปรุงคลิป (ลดนอยส์+คมชัด) หรือ แต่งภาพ แทนได้",
                    ),
                )
            }
            IntentType.RECORD_START -> {
                val dst = intent.parameters["dst"] ?: intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_DST", "อัดเสียงเก็บที่ไหนครับ? เช่น อัดเสียงที่ rec.m4a"),
                    )
                listOf("audio.recordStart" to mapOf("dst" to dst))
            }
            IntentType.RECORD_STOP -> listOf("audio.recordStop" to emptyMap())
            // CP-94 honest scope: screen capture needs MediaProjection consent plumbing (roadmap).
            IntentType.SCREEN_RECORD -> {
                return Outcome.Failure(
                    AppError(
                        "DEFERRED_SCREEN",
                        "อัดหน้าจอในแอปยังไม่รองรับครับ — ใช้ตัวอัดหน้าจอของระบบ แล้วนำเข้าไฟล์ด้วย นำเข้าไฟล์ ได้เลย",
                    ),
                )
            }
            IntentType.PODCAST -> {
                val voice = intent.parameters["voice"] ?: intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_VOICE", "ทำพอดแคสต์จากเสียงไหนครับ? เช่น พอดแคสต์ voice.wav เพลง bed.wav"),
                    )
                val args = mutableMapOf("voice" to voice)
                intent.parameters["bed"]?.let { args["bed"] = it }
                intent.parameters["dst"]?.let { args["dst"] = it }
                listOf("audio.podcast" to args)
            }
            IntentType.AUDIO_MIX -> {
                val a = intent.parameters["srcA"] ?: intent.parameters["src"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ผสมเสียงไฟล์ไหนครับ? เช่น ผสมเสียง a.wav กับ b.wav"),
                    )
                val b = intent.parameters["srcB"] ?: intent.parameters["bed"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ผสมกับไฟล์ไหนครับ? เช่น ผสมเสียง a.wav กับ b.wav"),
                    )
                listOf("audio.mix" to mapOf("srcA" to a, "srcB" to b))
            }
            IntentType.AUDIO_NORMALIZE -> {
                val src = intent.parameters["src"] ?: intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "นอร์มัลไลซ์ไฟล์ไหนครับ? เช่น นอร์มัลไลซ์ a.wav"),
                    )
                listOf("audio.normalize" to mapOf("src" to src))
            }
            IntentType.AI_PLAN -> {
                val args = mutableMapOf<String, String>()
                (intent.parameters["mode"] ?: "story").let { args["mode"] = it }
                intent.parameters["topic"]?.let { args["topic"] = it }
                intent.parameters["platform"]?.let { args["platform"] = it }
                listOf("media.text.aiplan" to args)
            }
            IntentType.SCRIPT_VIDEO -> {
                val script = intent.parameters["script"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_SCRIPT", "ขอบทด้วยครับ เช่น บทเป็นวิดีโอ: สวัสดีครับ..."),
                    )
                listOf("media.script.video" to mapOf("script" to script))
            }
            // CP-99 honest defer: lipsync needs a face/voice generative model.
            IntentType.LIPSYNC -> {
                return Outcome.Failure(
                    AppError(
                        "DEFERRED_LIPSYNC",
                        "ลิปซิงค์ (ขยับปากตามเสียง) ยังไม่รองรับครับ — ต้องใช้โมเดล AI เฉพาะทางที่ยังไม่มี ตอนนี้ใช้ ซับไตเติล/พากย์เสียง แทนได้",
                    ),
                )
            }
            // CP-99 honest defer: AI presenter needs avatar generation.
            IntentType.PRESENTER -> {
                return Outcome.Failure(
                    AppError(
                        "DEFERRED_PRESENTER",
                        "ผู้ประกาศ AI (อวตารพูด) ยังไม่รองรับครับ — ตอนนี้ใช้ บทเป็นวิดีโอ (เสียงพากย์+ภาพ+ซับ) แทนได้",
                    ),
                )
            }
            IntentType.BRAND_SAVE -> {
                val name = intent.parameters["name"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_NAME", "แบรนด์ชื่ออะไรครับ? เช่น สร้างแบรนด์ กาแฟดริป #8B4513"),
                    )
                val args = mutableMapOf("name" to name)
                intent.parameters["color"]?.let { args["color"] = it }
                intent.parameters["tagline"]?.let { args["tagline"] = it }
                intent.parameters["logo"]?.let { args["logo"] = it }
                listOf("media.brand.save" to args)
            }
            IntentType.BRAND_APPLY -> {
                val brand = intent.parameters["brand"] ?: intent.parameters["brandId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_BRAND", "ใช้แบรนด์ไหนครับ? (ดู id จาก รายชื่อแบรนด์)"),
                    )
                val args = mutableMapOf("brandId" to brand)
                intent.parameters["projectId"]?.let { args["projectId"] = it }
                listOf("media.brand.apply" to args)
            }
            IntentType.PROJECT_PACKAGE -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["projectId"]?.let { args["projectId"] = it }
                intent.parameters["dst"]?.let { args["dst"] = it }
                listOf("media.package" to args)
            }
            IntentType.RENDER_BATCH -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["all"]?.let { args["all"] = it }
                intent.parameters["projectIds"]?.let { args["projectIds"] = it }
                listOf("render.batch" to args)
            }
            IntentType.VIDEO_PROXY -> {
                val src = intent.parameters["path"] ?: intent.parameters["src"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ทำพร็อกซีจากวิดีโอไหนครับ? เช่น พร็อกซี v.mp4"),
                    )
                val args = mutableMapOf("src" to src)
                intent.parameters["maxDim"]?.let { args["maxDim"] = it }
                listOf("video.proxy" to args)
            }
            IntentType.CACHE_STATUS -> listOf("render.cache.status" to emptyMap())
            IntentType.CACHE_CLEAR -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["days"]?.let { args["days"] = it }
                listOf("render.cache.clear" to args)
            }
            IntentType.HW_INFO -> listOf("render.hwinfo" to emptyMap())
            IntentType.TIMELINE_SHORTCUTS -> listOf("media.timeline.shortcuts" to emptyMap())
            IntentType.VIDEO_SCOPES -> {
                val path = intent.parameters["path"] ?: intent.parameters["src"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ดูสโคปวิดีโอไหนครับ? เช่น ดูสโคป v.mp4"),
                    )
                val args = mutableMapOf("path" to path)
                intent.parameters["atMs"]?.let { args["atMs"] = it }
                listOf("video.scopes" to args)
            }
            IntentType.MULTICAM_SYNC -> {
                val paths = intent.parameters["paths"] ?: intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_FILE", "ซิงก์มุมไหนบ้างครับ? เช่น ซิงก์มัลติแคม a.mp4 b.mp4"),
                    )
                val args = mutableMapOf("paths" to paths)
                intent.parameters["method"]?.let { args["method"] = it }
                listOf("video.multicam.sync" to args)
            }
            IntentType.MULTICAM_CUT -> {
                val group = intent.parameters["group"] ?: intent.parameters["groupId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_GROUP", "ตัดกลุ่มไหนครับ? เช่น ตัดมัลติแคม mc_1 ที่ 5000 มุม 2"),
                    )
                val atMs = intent.parameters["atMs"]
                    ?: return Outcome.Failure(AppError("PLAN_NO_TIME", "ตัดที่เวลาเท่าไหร่ครับ? (ms)"))
                val angle = intent.parameters["angle"]
                    ?: return Outcome.Failure(AppError("PLAN_NO_ANGLE", "ตัดไปมุมที่เท่าไหร่ครับ?"))
                listOf("video.multicam.cut" to mapOf("group" to group, "atMs" to atMs, "angle" to angle))
            }
            IntentType.MULTICAM_EDL -> {
                val group = intent.parameters["group"] ?: intent.parameters["groupId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_GROUP", "ออกรายการตัดกลุ่มไหนครับ? เช่น รายการตัด mc_1"),
                    )
                listOf("video.multicam.edl" to mapOf("group" to group))
            }
            IntentType.CLIP_MASK -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "มาสก์คลิปที่เท่าไหร่ครับ? เช่น มาสก์คลิปที่ 1 วงรี"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["shape"]?.let { args["shape"] = it }
                listOf("media.timeline.setMask" to args)
            }
            IntentType.CLIP_CHROMA -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "กรีนสกรีนคลิปที่เท่าไหร่ครับ? เช่น กรีนสกรีนคลิปที่ 1"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                intent.parameters["hue"]?.let { args["hue"] = it }
                intent.parameters["off"]?.let { args["off"] = it }
                listOf("media.timeline.setChroma" to args)
            }
            IntentType.BG_SET -> {
                val args = mutableMapOf<String, String>()
                intent.parameters["mode"]?.let { args["mode"] = it }
                intent.parameters["color"]?.let { args["color"] = it }
                intent.parameters["assetId"]?.let { args["assetId"] = it }
                listOf("media.timeline.setBackground" to args)
            }
            IntentType.IMAGE_SCOPES -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_PATH", "เช็คแสงรูปไหนครับ? เช่น เช็คแสง รูปทะเล.jpg"),
                    )
                listOf("image.scopes" to mapOf("path" to path))
            }
            IntentType.CLIP_COLOR -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "แก้สีคลิปที่เท่าไหร่ครับ? เช่น สีคลิปที่ 1 โทนอุ่น"),
                    )
                val args = mutableMapOf("clipIndex" to clip)
                for (k in listOf("preset", "brightness", "contrast", "saturation", "temperature", "tint", "highlights", "shadows", "hueShift", "lightness")) {
                    intent.parameters[k]?.let { args[k] = it }
                }
                if (args.size < 2) {
                    return Outcome.Failure(
                        AppError("PLAN_NO_COLOR", "ปรับสีอะไรครับ? โทนอุ่น/โทนเย็น/ขาวดำ/ซีนีม่า หรือ สว่าง/คอนทราสต์/อิ่มสี + ตัวเลข"),
                    )
                }
                listOf("media.timeline.setColor" to args)
            }
            IntentType.KEYFRAME_SET -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ตั้งคีย์เฟรมคลิปที่เท่าไหร่ครับ? เช่น คีย์เฟรมสเกลคลิปที่ 1 150 ตอน 2 วิ"),
                    )
                val prop = intent.parameters["prop"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_KEYPROP", "คีย์เฟรมอะไรครับ? สเกล/หมุน/ทึบ/เสียง/ตำแหน่งx/ตำแหน่งy"),
                    )
                val at = intent.parameters["atMs"] ?: "0"
                val value = intent.parameters["value"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_KEYVALUE", "ค่าคีย์เฟรมเท่าไหร่ครับ? เช่น คีย์เฟรมสเกลคลิปที่ 1 150 ตอน 2 วิ"),
                    )
                val args = mutableMapOf("clipIndex" to clip, "prop" to prop, "atMs" to at, "value" to value)
                intent.parameters["ease"]?.let { args["ease"] = it }
                listOf("media.timeline.setKeyframe" to args)
            }
            IntentType.KEYFRAME_CLEAR -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ลบคีย์เฟรมคลิปที่เท่าไหร่ครับ? เช่น ลบคีย์เฟรมคลิปที่ 1"),
                    )
                listOf("media.timeline.clearKeyframes" to mapOf("clipIndex" to clip))
            }
            IntentType.CLIP_REVERSE -> {
                val clip = intent.parameters["clipIndex"] ?: intent.parameters["clipId"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_CLIP", "ย้อนคลิปที่เท่าไหร่ครับ? เช่น ย้อนคลิปที่ 1"),
                    )
                listOf("media.timeline.setSpeed" to mapOf("clipIndex" to clip, "reverse" to "true"))
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
            IntentType.IMAGE_ADJUST -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "แต่งรูปไหนครับ? เช่น แต่งภาพ a.png สว่าง 20"),
                    )
                val args = mutableMapOf("src" to path)
                for (k in listOf("brightness", "contrast", "saturation", "sharpness")) {
                    intent.parameters[k]?.let { args[k] = it }
                }
                listOf("image.adjust" to args)
            }
            IntentType.IMAGE_UPSCALE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "ขยายรูปไหนครับ? เช่น ขยายภาพ a.png 2"),
                    )
                val args = mutableMapOf("src" to path)
                intent.parameters["scale"]?.let { args["scale"] = it }
                listOf("image.upscale" to args)
            }
            IntentType.IMAGE_RESTORE -> {
                val path = intent.parameters["path"]
                    ?: return Outcome.Failure(
                        AppError("PLAN_NO_IMAGE", "ฟื้นฟูรูปไหนครับ? เช่น ฟื้นฟูภาพเก่า a.png"),
                    )
                listOf("image.restore" to mapOf("src" to path))
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
