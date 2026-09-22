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
