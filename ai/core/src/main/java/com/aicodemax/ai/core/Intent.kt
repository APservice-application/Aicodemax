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

    // Polite Thai request wrappers (vocabulary adopted from the previous app).
    // Longest-first; stripped only when ≥2 chars remain.
    private val leadingPrefixes = listOf(
        "ช่วยด้วย", "ช่วยหน่อย", "ฉันอยากให้", "ฉันต้องการ", "ผมอยากให้", "ผมต้องการ",
        "รบกวน", "กรุณา", "ช่วย", "กูขอ", "กุขอ", "ขอ",
    ).sortedByDescending { it.length }
    private val trailingSuffixes = listOf(
        "หน่อยครับ", "หน่อยค่ะ", "หน่อยคับ", "หน่อยนะ", "หน่อยสิ", "หน่อยเถอะ",
        "สักหน่อย", "ให้หน่อย", "ให้ด้วย", "ด้วยครับ", "ด้วยค่ะ", "ด้วยเลย",
        "นะครับ", "นะค่ะ", "หน่อย", "ด้วย", "ครับ", "ค่ะ", "คับ", "จ้า", "จ๊ะ", "นะ", "สิ", "ที",
    ).sortedByDescending { it.length }

    /** Strips polite wrappers so "ช่วยสร้างไฟล์ a.txt หน่อยครับ" parses as a command. */
    fun clean(text: String): String {
        var s = text.trim()
        for (prefix in leadingPrefixes) {
            if (s.startsWith(prefix) && s.length - prefix.length >= 2) {
                s = s.drop(prefix.length).trim()
            }
        }
        for (suffix in trailingSuffixes) {
            if (s.endsWith(suffix) && s.length - suffix.length >= 2) {
                s = s.dropLast(suffix.length).trim()
            }
        }
        return s
    }

    fun parse(text: String): UserIntent {
        val t = clean(text)
        val lower = t.lowercase()
        val file = fileNamePattern.find(t)?.value
        val dir = dirPattern.find(t)?.groupValues?.get(1)

        fun params(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

        return when {
            lower.startsWith("run test") || lower.startsWith("ทดสอบ") || lower == "test" ||
                lower.startsWith("test ") ->
                UserIntent(IntentType.RUN_TESTS, text)
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
                lower.startsWith("terminal") ->
                UserIntent(IntentType.RUN_COMMAND, text, params("command" to t))
            lower.startsWith("เปิดเว็บ") || lower.startsWith("open url") ||
                lower.startsWith("http") ->
                UserIntent(
                    IntentType.OPEN_URL, text,
                    params("url" to t.substringAfter(" ").split(Regex("\\s+")).firstOrNull()?.trim()),
                )
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
