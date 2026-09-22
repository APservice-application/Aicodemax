package com.aicodemax.ai.core

enum class IntentType {
    CHAT,
    CREATE_FILE,
    READ_FILE,
    LIST_FILES,
    MAKE_DIR,
    DELETE_PATH,
    RUN_COMMAND,
    OPEN_URL,
    BUILD_PROJECT,
    RUN_TESTS,
    GIT_ACTION,
    SEARCH_FILES,
    STOP_TASK,
    SYSTEM_STATUS,
    MEMORY_SAVE,
    MEMORY_RECALL,
    BROWSER_OPEN,
    BROWSER_CLOSE,
    BROWSER_LIST,
    DEBUG_CODE,
    MEDIA_EDIT,
    SHARE_MEDIA,
    OPEN_SETTINGS,
    LLM_CONNECT,
    SKILL_LIST,
    SKILL_GET,
    SKILL_REMOVE,
    VOICE_SPEAK,
    VOICE_LISTEN,
    IMAGE_INFO,
    IMAGE_RESIZE,
    IMAGE_CROP,
    IMAGE_ROTATE,
    IMAGE_GRAY,
    AUDIO_INFO,
    AUDIO_TRIM,
    AUDIO_CONCAT,
    AUDIO_GAIN,
    AUDIO_FADE,
    VIDEO_INFO,
    VIDEO_TRIM,
    VIDEO_THUMB,
    VIDEO_AUDIO,
    PROJECT_NEW,
    PROJECT_LIST,
    ASSET_IMPORT,
    PROJECT_VERSION,
    PROJECT_RESTORE,
    PROJECT_RENAME,
    PROJECT_DELETE,
    PROJECT_DUPLICATE,
    PROJECT_CHECKPOINT,
    EDIT_UNDO,
    EDIT_REDO,
    CLIP_SPLIT,
    CLIP_TRIM,
    CLIP_MOVE,
    CLIP_DELETE,
    CLIP_DUPLICATE,
    CLIP_ROTATE,
    CLIP_FLIP,
    CLIP_FREEZE,
    TEXT_ADD,
    TEXT_REMOVE,
    TEXT_IDEA,
    CLIP_SPEED,
    CLIP_REVERSE,
    KEYFRAME_SET,
    KEYFRAME_CLEAR,
    TRANSITION_SET,
    CLIP_FX,
    CLIP_COLOR,
    IMAGE_SCOPES,
    CLIP_MASK,
    CLIP_CHROMA,
    BG_SET,
    TRACK,
    STABILIZE,
    COLOR_AUTO,
    LUT_SET,
    LUT_CLEAR,
    TEMPLATE_SAVE,
    TEMPLATE_APPLY,
    TEMPLATE_LIST,
    TEMPLATE_DELETE,
    LIB_SEARCH,
    GEN_MAKE,
    GEN_LIST,
    CLIP_MOTION,
    SLIDESHOW,
    MARKER_ADD,
    MARKER_REMOVE,
    TRACK_FLAGS,
    SUBTITLE_MAKE,
    SUBTITLE_SHIFT,
    SUBTITLE_BURN,
    RENDER_START,
    RENDER_STATUS,
    RENDER_APPROVE,
    RENDER_EXPORT,
    UNKNOWN,
}

data class UserIntent(
    val type: IntentType,
    val rawText: String,
    val parameters: Map<String, String> = emptyMap(),
    val confidence: Double = 1.0,
)

/** Rule-based v0 intent parser (ML understanding plugs in behind [UserIntent] later). */
object IntentParser {
    private val fileNamePattern = Regex("[\\w\\-.]+\\.[A-Za-z0-9]{1,5}")
    private val dirPattern = Regex("(?:^|\\s)(?:folder|โฟลเดอร์|dir)\\s+([\\w\\-.]+)", RegexOption.IGNORE_CASE)
    private val digitsPattern = Regex("\\d+")

    /** Strips polite wrappers + fillers so "ช่วยสร้างไฟล์ a.txt หน่อยครับ" parses as a command. */
    fun clean(text: String): String {
        var s = text.trim()
        for (prefix in ThaiVocabulary.politePrefixes) {
            if (s.startsWith(prefix) && s.length - prefix.length >= 2) {
                s = s.drop(prefix.length).trim()
            }
        }
        for (suffix in ThaiVocabulary.politeSuffixes) {
            if (s.endsWith(suffix) && s.length - suffix.length >= 2) {
                s = s.dropLast(suffix.length).trim()
            }
        }
        // Leading fillers (location qualifiers, greetings, hesitations).
        var changed = true
        while (changed) {
            changed = false
            for (filler in ThaiVocabulary.leadingFillers) {
                if (s != filler && s.startsWith(filler) && s.length - filler.length >= 2) {
                    s = s.drop(filler.length).trim()
                    changed = true
                }
            }
        }
        // Trailing fillers: scope qualifiers only (never content).
        for (filler in ThaiVocabulary.trailingFillers) {
            if (s != filler && s.endsWith(filler) && s.length - filler.length >= 2) {
                s = s.dropLast(filler.length).trim()
            }
        }
        return s
    }

    private fun containsAny(haystack: String, words: List<String>): Boolean =
        words.any { haystack.contains(it) }

    private fun findPlatform(text: String): String? =
        ThaiVocabulary.platforms.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { text.contains(it.key) }
            ?.value

    /** 1-based clip number after คลิป/คลิปที่. */
    private fun parseClipIndex(text: String): String? =
        Regex("คลิป(?:ที่)?\\s*(\\d+)").find(text)?.groupValues?.get(1)

    /**
     * Thai time expression → ms. "2 นาที 30 วิ" / "นาทีที่ 2" / "90 วิ" /
     * bare trailing "90" (= sec, chat-friendly; tool-level ms stays exact
     * for API callers). The clip index ("คลิปที่ 1") is never eaten as time.
     */
    private fun parseTimeMs(text: String): Long? {
        val clean = text.replace(Regex("คลิป(?:ที่)?\\s*\\d+"), "")
        val minMatch = Regex("(?:(\\d+)\\s*นาที|นาที(?:ที่)?\\s*(\\d+))").find(clean)
        val min = minMatch?.groupValues?.get(1)?.toLongOrNull()
            ?: minMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0
        val secMatch = Regex("(?:(\\d+)\\s*วิ(?:นาที)?|วิ(?:นาที)?(?:ที่)?\\s*(\\d+))").find(clean)
        val sec = secMatch?.groupValues?.get(1)?.toLongOrNull()
            ?: secMatch?.groupValues?.get(2)?.toLongOrNull()
        if (sec != null) return min * 60_000 + sec * 1000
        if (min > 0) return min * 60_000
        return Regex("(\\d+)\\s*$").find(clean)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
    }

    private fun parseTrackId(text: String): String? =
        Regex("([VA]\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase()

    fun parse(text: String): UserIntent {
        val t = clean(text)
        val lower = t.lowercase()
        val file = fileNamePattern.find(t)?.value
        val dir = dirPattern.find(t)?.groupValues?.get(1)

        fun params(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

        // CP-57 automation intents (specific phrases first).
        if (containsAny(t, ThaiVocabulary.stopWords)) {
            return UserIntent(IntentType.STOP_TASK, text)
        }
        if (containsAny(t, ThaiVocabulary.mediaWords)) {
            return UserIntent(IntentType.MEDIA_EDIT, text, params("platform" to findPlatform(t)))
        }
        if (containsAny(t, ThaiVocabulary.shareWords)) {
            return UserIntent(IntentType.SHARE_MEDIA, text, params("platform" to findPlatform(t)))
        }
        if (containsAny(t, ThaiVocabulary.speakWords)) {
            val rest = ThaiVocabulary.speakWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
                .removePrefix(":").trim()
            return UserIntent(IntentType.VOICE_SPEAK, text, params("text" to rest.ifBlank { t }))
        }
        if (containsAny(t, ThaiVocabulary.listenWords)) {
            return UserIntent(IntentType.VOICE_LISTEN, text)
        }
        if (containsAny(t, ThaiVocabulary.imageInfoWords)) {
            return UserIntent(IntentType.IMAGE_INFO, text, params("path" to file))
        }
        // Digits inside the file name (photo2.png) must not become params.
        val tNoFile = if (file != null) t.replace(file, "") else t
        if (containsAny(t, ThaiVocabulary.imageResizeWords)) {
            val maxDim = digitsPattern.find(tNoFile)?.value
            return UserIntent(IntentType.IMAGE_RESIZE, text, params("path" to file, "maxDim" to maxDim))
        }
        if (containsAny(t, ThaiVocabulary.imageCropWords)) {
            val nums = digitsPattern.findAll(tNoFile).map { it.value }.toList()
            return UserIntent(
                IntentType.IMAGE_CROP, text,
                params(
                    "path" to file,
                    "x" to nums.getOrNull(0), "y" to nums.getOrNull(1),
                    "w" to nums.getOrNull(2), "h" to nums.getOrNull(3),
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.imageRotateWords)) {
            val degrees = digitsPattern.find(tNoFile)?.value
            return UserIntent(IntentType.IMAGE_ROTATE, text, params("path" to file, "degrees" to degrees))
        }
        if (containsAny(t, ThaiVocabulary.imageGrayWords)) {
            return UserIntent(IntentType.IMAGE_GRAY, text, params("path" to file))
        }
        if (containsAny(t, ThaiVocabulary.audioInfoWords)) {
            return UserIntent(IntentType.AUDIO_INFO, text, params("path" to file))
        }
        if (containsAny(t, ThaiVocabulary.audioTrimWords)) {
            val nums = digitsPattern.findAll(tNoFile).map { it.value }.toList()
            return UserIntent(
                IntentType.AUDIO_TRIM, text,
                params("path" to file, "startMs" to nums.getOrNull(0), "endMs" to nums.getOrNull(1)),
            )
        }
        if (containsAny(t, ThaiVocabulary.audioConcatWords)) {
            val files = fileNamePattern.findAll(t).map { it.value }.toList()
            return UserIntent(IntentType.AUDIO_CONCAT, text, params("srcs" to files.joinToString("|").ifBlank { null }))
        }
        if (containsAny(t, ThaiVocabulary.audioGainWords)) {
            val digits = digitsPattern.find(tNoFile)?.value
            val negative = t.contains("เบาเสียง")
            val db = digits?.let { if (negative) "-$it" else it }
            return UserIntent(IntentType.AUDIO_GAIN, text, params("path" to file, "db" to db))
        }
        if (containsAny(t, ThaiVocabulary.projectNewWords)) {
            val name = ThaiVocabulary.projectNewWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
            return UserIntent(IntentType.PROJECT_NEW, text, params("name" to name.ifBlank { null }))
        }
        if (containsAny(t, ThaiVocabulary.versionSaveWords)) {
            return UserIntent(IntentType.PROJECT_VERSION, text)
        }
        if (containsAny(t, ThaiVocabulary.versionRestoreWords)) {
            val version = digitsPattern.find(tNoFile)?.value
            return UserIntent(IntentType.PROJECT_RESTORE, text, params("version" to version))
        }
        if (containsAny(t, ThaiVocabulary.assetImportWords)) {
            return UserIntent(IntentType.ASSET_IMPORT, text, params("path" to file))
        }
        if (containsAny(t, ThaiVocabulary.markerRemoveWords)) {
            val id = Regex("(mark_[A-Za-z0-9]+)").find(t)?.groupValues?.get(1)
            return UserIntent(IntentType.MARKER_REMOVE, text, params("markerId" to id))
        }
        if (containsAny(t, ThaiVocabulary.markerAddWords)) {
            val time = parseTimeMs(t)
            var label = t
            for (w in ThaiVocabulary.markerAddWords) label = label.replace(w, "")
            label = label.replace(Regex("\\d+\\s*(นาที|วิ|วินาที)"), "").replace(Regex("(นาที|วิ|วินาที)(ที่)?\\s*\\d+"), "").trim()
            return UserIntent(
                IntentType.MARKER_ADD, text,
                params("atMs" to time?.toString(), "label" to label.ifBlank { null }),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackUnlockWords)) {
            return UserIntent(
                IntentType.TRACK_FLAGS, text,
                params("trackId" to parseTrackId(t), "locked" to "false"),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackLockWords)) {
            return UserIntent(
                IntentType.TRACK_FLAGS, text,
                params("trackId" to parseTrackId(t), "locked" to "true"),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackMuteWords)) {
            return UserIntent(
                IntentType.TRACK_FLAGS, text,
                params("trackId" to parseTrackId(t), "muted" to "true"),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackUnmuteWords)) {
            return UserIntent(
                IntentType.TRACK_FLAGS, text,
                params("trackId" to parseTrackId(t), "muted" to "false"),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackHideWords)) {
            return UserIntent(
                IntentType.TRACK_FLAGS, text,
                params("trackId" to parseTrackId(t), "hidden" to "true"),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackShowWords)) {
            return UserIntent(
                IntentType.TRACK_FLAGS, text,
                params("trackId" to parseTrackId(t), "hidden" to "false"),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipSplitWords)) {
            return UserIntent(
                IntentType.CLIP_SPLIT, text,
                params("clipIndex" to parseClipIndex(t), "atMs" to parseTimeMs(t)?.toString()),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipTrimWords)) {
            val startPart = when {
                t.contains("เริ่ม") -> t.substringAfter("เริ่ม")
                t.contains("ตั้งแต่") -> t.substringAfter("ตั้งแต่")
                else -> ""
            }
            val endPart = when {
                t.contains("จบ") -> t.substringAfter("จบ")
                t.contains("ถึง") -> t.substringAfter("ถึง")
                else -> ""
            }
            return UserIntent(
                IntentType.CLIP_TRIM, text,
                params(
                    "clipIndex" to parseClipIndex(t),
                    "startMs" to (if (t.contains("เริ่ม") || t.contains("ตั้งแต่")) parseTimeMs(startPart)?.toString() else null),
                    "endMs" to (if (t.contains("จบ") || t.contains("ถึง")) parseTimeMs(endPart)?.toString() else null),
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipMoveWords)) {
            return UserIntent(
                IntentType.CLIP_MOVE, text,
                params("clipIndex" to parseClipIndex(t), "toAtMs" to parseTimeMs(t)?.toString()),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipDeleteWords)) {
            return UserIntent(IntentType.CLIP_DELETE, text, params("clipIndex" to parseClipIndex(t)))
        }
        if (containsAny(t, ThaiVocabulary.clipDuplicateWords)) {
            return UserIntent(
                IntentType.CLIP_DUPLICATE, text,
                params("clipIndex" to parseClipIndex(t), "atMs" to parseTimeMs(t)?.toString()),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipRotateWords)) {
            val noClip = t.replace(Regex("คลิป(?:ที่)?\\s*\\d+"), "")
            val deg = Regex("(\\d+)").find(noClip)?.groupValues?.get(1)?.toIntOrNull()
            val rotation = if (deg == null) 90 else ((deg % 360 + 360) % 360 + 45) / 90 * 90 % 360
            return UserIntent(
                IntentType.CLIP_ROTATE, text,
                params("clipIndex" to parseClipIndex(t), "rotation" to rotation.toString()),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipFlipWords)) {
            val vertical = t.contains("บนล่าง")
            return UserIntent(
                IntentType.CLIP_FLIP, text,
                params(
                    "clipIndex" to parseClipIndex(t),
                    "flipH" to if (vertical) null else "true",
                    "flipV" to if (vertical) "true" else null,
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipFreezeWords)) {
            return UserIntent(
                IntentType.CLIP_FREEZE, text,
                params("clipIndex" to parseClipIndex(t), "holdMs" to parseTimeMs(t)?.toString()),
            )
        }
        if (containsAny(t, ThaiVocabulary.textRemoveWords)) {
            val index = Regex("ข้อความ(?:ที่)?\\s*(\\d+)").find(t)?.groupValues?.get(1)
            return UserIntent(IntentType.TEXT_REMOVE, text, params("textIndex" to index))
        }
        if (containsAny(t, ThaiVocabulary.textAddWords)) {
            val after = t.substringAfter(":", "").trim()
            val cleaned = if (after.isNotEmpty()) {
                after
            } else {
                ThaiVocabulary.textAddWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
            }
            val preset = listOf("title", "lower", "caption", "hook", "cta").firstOrNull { cleaned.contains(it) }
            val content = preset?.let { cleaned.replace(it, "").trim() } ?: cleaned
            return UserIntent(
                IntentType.TEXT_ADD, text,
                params(
                    "text" to content.ifBlank { null },
                    "preset" to preset,
                    "startMs" to parseTimeMs(t)?.toString(),
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.textIdeaWords)) {
            val kind = when {
                t.contains("หัวข้อ") -> "title"
                t.contains("สโลแกน") -> "hook"
                t.contains("ขาย") || t.contains("ชวน") -> "cta"
                else -> "caption"
            }
            val topic = ThaiVocabulary.textIdeaWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
            return UserIntent(
                IntentType.TEXT_IDEA, text,
                params("kind" to kind, "topic" to topic.ifBlank { null }, "platform" to findPlatform(t)),
            )
        }
        if (containsAny(t, ThaiVocabulary.transitionWords)) {
            val kind = when {
                t.contains("ดีซอล์ฟ") || t.contains("ละลาย") -> "dissolve"
                t.contains("ไวป์") || t.contains("ปาด") -> when {
                    t.contains("ขวา") -> "wiperight"
                    t.contains("บน") -> "wipeup"
                    t.contains("ล่าง") -> "wipedown"
                    else -> "wipeleft"
                }
                else -> "fade"
            }
            val edge = if (t.contains("ขาออก") || t.contains("ท้าย")) "out" else "in"
            return UserIntent(
                IntentType.TRANSITION_SET, text,
                params("clipIndex" to parseClipIndex(t), "edge" to edge, "kind" to kind),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipFxWords)) {
            val noClip = t.replace(Regex("คลิป(?:ที่)?\\s*\\d+"), "")
            val value = Regex("(\\d+)").find(noClip)?.groupValues?.get(1)
            return UserIntent(
                IntentType.CLIP_FX, text,
                params(
                    "clipIndex" to parseClipIndex(t),
                    "blur" to (value.takeIf { t.contains("เบลอ") }),
                    "vignette" to (value.takeIf { t.contains("วิกเน็ต") }),
                    "grain" to (value.takeIf { t.contains("เกรน") }),
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.trackWords)) {
            val target = Regex("ข้อความ(?:ที่)?\\s*(\\d+)").find(t)?.groupValues?.get(1)?.let { "text:$it" }
            return UserIntent(
                IntentType.TRACK, text,
                params("clipIndex" to parseClipIndex(t), "target" to target),
            )
        }
        if (containsAny(t, ThaiVocabulary.stabWords)) {
            return UserIntent(IntentType.STABILIZE, text, params("clipIndex" to parseClipIndex(t)))
        }
        if (containsAny(t, ThaiVocabulary.clipMaskWords)) {
            val shape = if (t.contains("วงรี") || t.contains("วงกลม")) "ellipse" else "rect"
            return UserIntent(
                IntentType.CLIP_MASK, text,
                params("clipIndex" to parseClipIndex(t), "shape" to shape),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipChromaWords)) {
            val off = t.contains("ปิด")
            val hue = if (t.contains("ฟ้า") || t.contains("น้ำเงิน")) "240" else null
            return UserIntent(
                IntentType.CLIP_CHROMA, text,
                params("clipIndex" to parseClipIndex(t), "hue" to hue, "off" to (if (off) "true" else null)),
            )
        }
        if (containsAny(t, ThaiVocabulary.bgWords)) {
            val mode = when {
                t.contains("เบลอ") -> "blur"
                t.contains("รูป") -> "image"
                t.contains("ดำ") -> "black"
                else -> "color"
            }
            val color = when {
                t.contains("แดง") -> "FF0000"
                t.contains("เขียว") -> "00AA00"
                t.contains("น้ำเงิน") || t.contains("ฟ้า") -> "0044FF"
                t.contains("ขาว") -> "FFFFFF"
                else -> null
            }
            return UserIntent(
                IntentType.BG_SET, text,
                params("mode" to mode, "color" to color),
            )
        }
        if (containsAny(t, ThaiVocabulary.imageScopesWords)) {
            return UserIntent(IntentType.IMAGE_SCOPES, text, params("path" to file))
        }
        if (containsAny(t, ThaiVocabulary.genListWords)) {
            return UserIntent(IntentType.GEN_LIST, text, params())
        }
        if (containsAny(t, ThaiVocabulary.genWords)) {
            val kind = when {
                t.contains("โปสเตอร์") -> "poster"
                t.contains("พื้นหลัง") -> "background"
                t.contains("แต่งรูป") -> "stylize"
                t.contains("เสียง") || t.contains("พากย์") || t.contains("บรรยาย") -> "tts"
                else -> "poster"
            }
            val prompt = Regex("[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
                ?: text.replace(Regex("(?i)สร้างภาพ|สร้างโปสเตอร์|ทำโปสเตอร์|สร้างพื้นหลัง|ทำเสียงพูด|เสียงบรรยาย|พากย์เสียง|แต่งรูป"), "").trim().ifBlank { null }
            return UserIntent(
                IntentType.GEN_MAKE, text,
                params("kind" to kind, "prompt" to prompt, "path" to file),
            )
        }
        if (containsAny(lower, ThaiVocabulary.slideshowWords)) {
            val names = fileNamePattern.findAll(t).map { it.value }.toList()
            return UserIntent(IntentType.SLIDESHOW, text, params("assets" to names.joinToString(",").ifBlank { null }))
        }
        if (containsAny(t, ThaiVocabulary.motionWords)) {
            val dir = when {
                t.contains("ซูมออก") -> "out"
                t.contains("ซูมเข้า") || t.contains("ซูม") -> "in"
                t.contains("ซ้าย") -> "left"
                t.contains("ขวา") -> "right"
                t.contains("ขึ้น") -> "up"
                t.contains("ลง") -> "down"
                else -> null
            }
            val off = t.contains("ปิด") || t.contains("หยุด")
            return UserIntent(
                IntentType.CLIP_MOTION, text,
                params("clipIndex" to parseClipIndex(t), "dir" to dir, "off" to (if (off) "true" else null)),
            )
        }
        if (containsAny(lower, ThaiVocabulary.templateWords)) {
            val name = Regex("[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
            return when {
                t.contains("บันทึก") || t.contains("เซฟ") ->
                    UserIntent(IntentType.TEMPLATE_SAVE, text, params("name" to name))
                t.contains("ใช้") || t.contains("เอา") ->
                    UserIntent(IntentType.TEMPLATE_APPLY, text, params("name" to name))
                t.contains("ลบ") ->
                    UserIntent(IntentType.TEMPLATE_DELETE, text, params("name" to name))
                else -> UserIntent(IntentType.TEMPLATE_LIST, text, params())
            }
        }
        if (containsAny(lower, ThaiVocabulary.libraryWords)) {
            val q = text.replace(Regex("(?i)ค้นหา|คลัง|ไลบรารี|library"), "").trim()
            return UserIntent(IntentType.LIB_SEARCH, text, params("query" to q.ifBlank { null }))
        }
        if (containsAny(lower, ThaiVocabulary.lutWords)) {
            if (t.contains("ล้าง") || t.contains("ลบ") || t.contains("ถอด")) {
                return UserIntent(IntentType.LUT_CLEAR, text, params("clipIndex" to parseClipIndex(t)))
            }
            return UserIntent(
                IntentType.LUT_SET, text,
                params("clipIndex" to parseClipIndex(t), "path" to file),
            )
        }
        if (containsAny(t, ThaiVocabulary.colorAutoWords)) {
            return UserIntent(IntentType.COLOR_AUTO, text, params("clipIndex" to parseClipIndex(t)))
        }
        if (containsAny(t, ThaiVocabulary.clipColorWords)) {
            val preset = when {
                t.contains("ขาวดำ") -> "bw"
                t.contains("ซีนีม่า") -> "cinema"
                t.contains("โทนอุ่น") || t.contains("อุ่น") -> "warm"
                t.contains("โทนเย็น") || t.contains("เย็น") -> "cool"
                t.contains("สดใส") -> "vivid"
                else -> null
            }
            val noClip = t.replace(Regex("คลิป(?:ที่)?\\s*\\d+"), "")
            val value = Regex("(\\d+)").find(noClip)?.groupValues?.get(1)
            val neg = t.contains("ลด") || t.contains("-")
            val signed = value?.let { if (neg) "-$it" else it }
            return UserIntent(
                IntentType.CLIP_COLOR, text,
                params(
                    "clipIndex" to parseClipIndex(t),
                    "preset" to preset,
                    "brightness" to (signed.takeIf { t.contains("สว่าง") }),
                    "contrast" to (signed.takeIf { t.contains("คอนทราสต์") }),
                    "saturation" to (signed.takeIf { t.contains("อิ่มสี") || t.contains("สด") }),
                    "temperature" to (signed.takeIf { t.contains("อุณหภูมิ") }),
                    "tint" to (signed.takeIf { t.contains("ทินต์") }),
                    "highlights" to (signed.takeIf { t.contains("ไฮไลต์") }),
                    "shadows" to (signed.takeIf { t.contains("แชโดว์") || t.contains("เงา") }),
                    "exposure" to (signed.takeIf { t.contains("เอ็กซ์โพเชอร์") || t.contains("รับแสง") }),
                    "whites" to (signed.takeIf { t.contains("จุดขาว") || t.contains("ไวท์") }),
                    "blacks" to (signed.takeIf { t.contains("จุดดำ") || t.contains("แบล็ก") }),
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.keyframeClearWords)) {
            return UserIntent(IntentType.KEYFRAME_CLEAR, text, params("clipIndex" to parseClipIndex(t)))
        }
        if (containsAny(t, ThaiVocabulary.keyframeWords)) {
            val prop = when {
                t.contains("สเกล") || t.contains("ขนาด") -> "scale"
                t.contains("หมุน") -> "rotation"
                t.contains("ทึบ") || t.contains("โปร่งใส") -> "opacity"
                t.contains("เสียง") || lower.contains("volume") -> "volume"
                t.contains("ตำแหน่งx") || t.contains("ตำแหน่ง x") -> "posX"
                t.contains("ตำแหน่งy") || t.contains("ตำแหน่ง y") -> "posY"
                else -> null
            }
            val noClip = t.replace(Regex("คลิป(?:ที่)?\\s*\\d+"), "")
            val noTime = noClip.replace(Regex("\\d+\\s*(?:นาที|วิ(?:นาที)?)|(?:นาที|วิ(?:นาที)?)(?:ที่)?\\s*\\d+"), "")
            val value = Regex("(\\d+)").find(noTime)?.groupValues?.get(1)
            val ease = if (t.contains("นุ่ม")) "easeinout" else null
            return UserIntent(
                IntentType.KEYFRAME_SET, text,
                params(
                    "clipIndex" to parseClipIndex(t),
                    "prop" to prop,
                    "atMs" to parseTimeMs(t)?.toString(),
                    "value" to value,
                    "ease" to ease,
                ),
            )
        }
        if (containsAny(t, ThaiVocabulary.clipReverseWords)) {
            return UserIntent(IntentType.CLIP_REVERSE, text, params("clipIndex" to parseClipIndex(t)))
        }
        if (containsAny(t, ThaiVocabulary.clipSpeedWords)) {
            val noClip = t.replace(Regex("คลิป(?:ที่)?\\s*\\d+"), "")
            val digits = Regex("(\\d+)").find(noClip)?.groupValues?.get(1)?.toIntOrNull()
            val rate = when {
                digits != null -> digits
                t.contains("ช้าลง") -> 50
                t.contains("เร็วขึ้น") -> 200
                t.contains("ปกติ") -> 100
                else -> null
            }
            return UserIntent(
                IntentType.CLIP_SPEED, text,
                params("clipIndex" to parseClipIndex(t), "rate" to rate?.toString()),
            )
        }
        if (containsAny(t, ThaiVocabulary.projectRenameWords)) {
            val name = ThaiVocabulary.projectRenameWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
            return UserIntent(IntentType.PROJECT_RENAME, text, params("name" to name.ifBlank { null }))
        }
        if (containsAny(t, ThaiVocabulary.projectDeleteWords)) {
            return UserIntent(IntentType.PROJECT_DELETE, text)
        }
        if (containsAny(t, ThaiVocabulary.projectDuplicateWords)) {
            return UserIntent(IntentType.PROJECT_DUPLICATE, text)
        }
        if (containsAny(t, ThaiVocabulary.checkpointWords)) {
            return UserIntent(IntentType.PROJECT_CHECKPOINT, text)
        }
        if (containsAny(t, ThaiVocabulary.undoWords)) {
            return UserIntent(IntentType.EDIT_UNDO, text)
        }
        if (containsAny(t, ThaiVocabulary.redoWords)) {
            return UserIntent(IntentType.EDIT_REDO, text)
        }
        if (containsAny(t, ThaiVocabulary.projectListWords)) {
            return UserIntent(IntentType.PROJECT_LIST, text)
        }
        if (containsAny(t, ThaiVocabulary.subtitleMakeWords)) {
            val after = t.substringAfter(":", "").trim()
            val transcript = if (after.isNotEmpty()) {
                after
            } else {
                ThaiVocabulary.subtitleMakeWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
            }
            val digits = digitsPattern.find(tNoFile)?.value
            return UserIntent(
                IntentType.SUBTITLE_MAKE, text,
                params("path" to file, "transcript" to transcript.ifBlank { null }, "durationMs" to digits),
            )
        }
        if (containsAny(t, ThaiVocabulary.subtitleShiftWords)) {
            val digits = digitsPattern.find(tNoFile)?.value
            val negative = t.contains("ถอย")
            val offset = digits?.let { if (negative) "-$it" else it }
            return UserIntent(IntentType.SUBTITLE_SHIFT, text, params("path" to file, "offsetMs" to offset))
        }
        if (containsAny(t, ThaiVocabulary.subtitleBurnWords)) {
            val files = fileNamePattern.findAll(t).map { it.value }.toList()
            val srt = files.firstOrNull { it.lowercase().endsWith(".srt") }
            val src = files.firstOrNull { it != srt }
            return UserIntent(IntentType.SUBTITLE_BURN, text, params("src" to src, "srt" to srt))
        }
        if (containsAny(t, ThaiVocabulary.renderStatusWords)) {
            return UserIntent(IntentType.RENDER_STATUS, text)
        }
        if (containsAny(t, ThaiVocabulary.renderApproveWords)) {
            return UserIntent(IntentType.RENDER_APPROVE, text)
        }
        if (containsAny(t, ThaiVocabulary.renderExportWords)) {
            return UserIntent(IntentType.RENDER_EXPORT, text)
        }
        if (containsAny(t, ThaiVocabulary.renderStartWords)) {
            val preset = listOf("720p", "480p", "original").firstOrNull { t.contains(it) }
            return UserIntent(IntentType.RENDER_START, text, params("preset" to preset))
        }
        if (containsAny(t, ThaiVocabulary.videoInfoWords)) {
            return UserIntent(IntentType.VIDEO_INFO, text, params("path" to file))
        }
        if (containsAny(t, ThaiVocabulary.videoTrimWords)) {
            val nums = digitsPattern.findAll(tNoFile).map { it.value }.toList()
            return UserIntent(
                IntentType.VIDEO_TRIM, text,
                params("path" to file, "startMs" to nums.getOrNull(0), "endMs" to nums.getOrNull(1)),
            )
        }
        if (containsAny(t, ThaiVocabulary.videoThumbWords)) {
            val timeMs = digitsPattern.find(tNoFile)?.value
            return UserIntent(IntentType.VIDEO_THUMB, text, params("path" to file, "timeMs" to timeMs))
        }
        if (containsAny(t, ThaiVocabulary.videoAudioWords)) {
            return UserIntent(IntentType.VIDEO_AUDIO, text, params("path" to file))
        }
        if (containsAny(t, ThaiVocabulary.audioFadeWords)) {
            val nums = digitsPattern.findAll(tNoFile).map { it.value }.toList()
            return UserIntent(
                IntentType.AUDIO_FADE, text,
                params("path" to file, "inMs" to nums.getOrNull(0), "outMs" to nums.getOrNull(1)),
            )
        }
        if (containsAny(t, ThaiVocabulary.debugWords)) {
            val error = t.substringAfter(":", t).trim()
            return UserIntent(IntentType.DEBUG_CODE, text, params("error" to error.ifBlank { t }))
        }
        if (containsAny(t, ThaiVocabulary.searchWords)) {
            val query = t.substringAfter("ค้นหา").trim().ifBlank { t }
            return UserIntent(IntentType.SEARCH_FILES, text, params("query" to query))
        }
        if (containsAny(t, ThaiVocabulary.memorySaveWords)) {
            val rest = ThaiVocabulary.memorySaveWords.fold(t) { acc, w -> acc.replace(w, "") }.trim()
                .ifBlank { t }
            val key = rest.substringBefore(":").substringBefore(" ").trim().ifBlank { "note" }
            val value = rest.substringAfter(":", rest.substringAfter(" ", "")).trim().ifBlank { rest }
            return UserIntent(IntentType.MEMORY_SAVE, text, params("key" to key, "value" to value))
        }
        if (containsAny(t, ThaiVocabulary.memoryRecallWords)) {
            val key = t.replace("ความจำ", "").trim().ifBlank { t }
            return UserIntent(IntentType.MEMORY_RECALL, text, params("key" to key))
        }
        if (containsAny(t, ThaiVocabulary.statusWords)) {
            return UserIntent(IntentType.SYSTEM_STATUS, text)
        }
        if (containsAny(t, ThaiVocabulary.settingsWords)) {
            return UserIntent(IntentType.OPEN_SETTINGS, text)
        }
        if (containsAny(t, ThaiVocabulary.llmConnectWords)) {
            return UserIntent(IntentType.LLM_CONNECT, text)
        }
        // CP-58 skills: "สกิล" list, "สกิล <id>" read, "ลบสกิล <id>" remove.
        if (t.contains("สกิล") || lower.contains("skill")) {
            val rest = t.replace("สกิล", "").replace("skill", "", ignoreCase = true).trim()
                .removePrefix("ดู").trim()
            if (rest.startsWith("ลบ") || rest.startsWith("remove", ignoreCase = true)) {
                val id = rest.removePrefix("ลบ")
                    .replace("remove", "", ignoreCase = true).trim()
                return UserIntent(IntentType.SKILL_REMOVE, text, params("id" to id.ifBlank { rest }))
            }
            if (rest.isBlank()) {
                return UserIntent(IntentType.SKILL_LIST, text)
            }
            return UserIntent(IntentType.SKILL_GET, text, params("id" to rest.split(Regex("\\s+")).first()))
        }
        // Browser: close beats open beats list ("ปิดแท็บ" contains "แท็บ").
        // NOTE: startsWith only — "เปิด" literally contains "ปิด" (เ+ปิด), so
        // contains-matching would send every เปิด command to BROWSER_CLOSE.
        if (ThaiVocabulary.browserCloseWords.any { t.startsWith(it) }) {
            val tabId = digitsPattern.find(t)?.value
            return UserIntent(IntentType.BROWSER_CLOSE, text, params("tabId" to tabId))
        }
        if (lower.startsWith("เปิดเว็บ") || lower.startsWith("open url") || lower.startsWith("http")) {
            val url = if (lower.startsWith("http")) {
                t.split(Regex("\\s+")).firstOrNull()?.trim()
            } else {
                t.substringAfter(" ", "").trim().split(Regex("\\s+")).firstOrNull()?.trim()
            }
            return UserIntent(IntentType.OPEN_URL, text, params("url" to url?.ifBlank { null }))
        }
        val openPrefix = listOf("เปิดดู", "เปิด").firstOrNull { lower.startsWith(it) }
        if (openPrefix != null) {
            val url = t.drop(openPrefix.length).trim().split(Regex("\\s+")).firstOrNull()?.trim()
            return UserIntent(IntentType.BROWSER_OPEN, text, params("url" to url?.ifBlank { null }))
        }
        if (containsAny(t, ThaiVocabulary.browserListWords)) {
            return UserIntent(IntentType.BROWSER_LIST, text)
        }
        if (containsAny(t, ThaiVocabulary.testWords)) {
            return UserIntent(IntentType.RUN_TESTS, text)
        }
        // Legacy + file intents.
        return when {
            lower.startsWith("run test") || lower.startsWith("ทดสอบ") || lower == "test" ||
                lower.startsWith("test ") ->
                UserIntent(IntentType.RUN_TESTS, text)
            ThaiVocabulary.writeWords.any { lower.startsWith(it.lowercase()) } ||
                lower.startsWith("สร้างไฟล์") || lower.startsWith("create file") -> {
                val content = t.substringAfter(":", "").trim().ifBlank { "// created by Aicodemax\n" }
                UserIntent(IntentType.CREATE_FILE, text, params("path" to file, "content" to content))
            }
            lower.startsWith("อ่านไฟล์") || lower.startsWith("read file") ||
                lower.startsWith("show file") ->
                UserIntent(IntentType.READ_FILE, text, params("path" to file))
            lower.startsWith("ดูไฟล์") || lower.startsWith("list files") ||
                lower == "ls" || lower.startsWith("ls ") ->
                UserIntent(IntentType.LIST_FILES, text, params("path" to (dir ?: "")))
            lower.startsWith("สร้างโฟลเดอร์") || lower.startsWith("make dir") ||
                lower.startsWith("mkdir") -> {
                // Strip the command word, then an optional "folder" word, then take the name.
                val after = t.substringAfter(" ", "").trim()
                    .removePrefix("folder ").removePrefix("โฟลเดอร์ ").removePrefix("dir ").trim()
                val name = after.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
                UserIntent(IntentType.MAKE_DIR, text, params("path" to name.ifBlank { dir ?: file }))
            }
            lower.startsWith("ลบไฟล์") || lower.startsWith("delete ") ->
                UserIntent(IntentType.DELETE_PATH, text, params("path" to (file ?: dir)))
            lower.startsWith("รัน") || lower.startsWith("run ") ||
                lower.startsWith("terminal") ||
                containsAny(lower, ThaiVocabulary.terminalWords.map { it.lowercase() }) ->
                UserIntent(IntentType.RUN_COMMAND, text, params("command" to t))
            lower.startsWith("build") || lower.startsWith("บิลด์") ->
                UserIntent(IntentType.BUILD_PROJECT, text)
            isGitCommand(lower) ->
                UserIntent(IntentType.GIT_ACTION, text, gitParams(t))
            else -> UserIntent(IntentType.CHAT, text)
        }
    }

    private fun isGitCommand(lower: String): Boolean {
        if (lower.startsWith("git ") || lower == "git") return true
        val first = lower.split(Regex("\\s+")).firstOrNull().orEmpty()
        return first in setOf(
            "commit", "push", "pull", "fetch", "clone", "checkout", "branch",
            "merge", "github", "กิตฮับ",
        )
    }

    private fun gitParams(t: String): Map<String, String> {
        val tokens = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val verb = if (tokens.firstOrNull()?.lowercase() == "git") {
            tokens.getOrNull(1)?.lowercase().orEmpty()
        } else {
            tokens.firstOrNull()?.lowercase().orEmpty()
        }
        val action = when (verb) {
            "status", "st" -> "status"
            "log" -> "log"
            "commit", "ci" -> "commit"
            "add", "stage" -> "stage"
            "init", "ensure" -> "ensure"
            "" -> "status"
            else -> verb
        }
        val message = t.substringAfter("-m", "").trim().removeSurrounding("\"").trim()
            .ifBlank { t.substringAfter(":", "").trim() }
        return buildMap {
            put("action", action)
            put("repo", "")
            if (message.isNotBlank()) put("message", message)
        }
    }
}
