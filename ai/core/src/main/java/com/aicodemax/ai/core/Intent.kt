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
    private val dirPattern = Regex("(?:folder|โฟลเดอร์|dir)\\s+([\\w\\-.]+)", RegexOption.IGNORE_CASE)

    fun parse(text: String): UserIntent {
        val t = text.trim()
        val lower = t.lowercase()
        val file = fileNamePattern.find(t)?.value
        val dir = dirPattern.find(t)?.groupValues?.get(1)

        fun params(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

        return when {
            lower.startsWith("run test") || lower.startsWith("ทดสอบ") || lower == "test" ||
                lower.startsWith("test ") ->
                UserIntent(IntentType.RUN_TESTS, t)
            lower.startsWith("สร้างไฟล์") || lower.startsWith("create file") -> {
                val content = t.substringAfter(":", "").trim().ifBlank { "// created by Aicodemax\n" }
                UserIntent(IntentType.CREATE_FILE, t, params("path" to file, "content" to content))
            }
            lower.startsWith("อ่านไฟล์") || lower.startsWith("read file") ||
                lower.startsWith("show file") ->
                UserIntent(IntentType.READ_FILE, t, params("path" to file))
            lower.startsWith("ดูไฟล์") || lower.startsWith("list files") ||
                lower == "ls" || lower.startsWith("ls ") ->
                UserIntent(IntentType.LIST_FILES, t, params("path" to (dir ?: "")))
            lower.startsWith("สร้างโฟลเดอร์") || lower.startsWith("make dir") ||
                lower.startsWith("mkdir") ->
                UserIntent(IntentType.MAKE_DIR, t, params("path" to (dir ?: file)))
            lower.startsWith("ลบไฟล์") || lower.startsWith("delete ") ->
                UserIntent(IntentType.DELETE_PATH, t, params("path" to (file ?: dir)))
            lower.startsWith("รัน") || lower.startsWith("run ") ||
                lower.startsWith("terminal") ->
                UserIntent(IntentType.RUN_COMMAND, t, params("command" to t))
            lower.startsWith("เปิดเว็บ") || lower.startsWith("open url") ||
                lower.startsWith("http") ->
                UserIntent(IntentType.OPEN_URL, t, params("url" to t.substringAfter(" ").trim()))
            lower.startsWith("build") || lower.startsWith("บิลด์") ->
                UserIntent(IntentType.BUILD_PROJECT, t)
            lower.startsWith("git ") || lower.startsWith("commit") ->
                UserIntent(IntentType.GIT_ACTION, t)
            else -> UserIntent(IntentType.CHAT, t)
        }
    }
}
