package com.aicodemax.tools.terminal

/**
 * Command risk classifier (safety gate, มาตรา 17).
 * Patterns adopted from the previous app's ProcessExecutor — catastrophic
 * commands are BANNED outright, sensitive ones are RISKY (need permission).
 */
enum class CommandRisk { SAFE, RISKY, BANNED }

object CommandRiskClassifier {
    private val banned = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/(\s|$)"""),
        // Improvement over the source patterns: any rm-with-flags whose last arg is / or ~.
        Regex("""\brm(\s+-[a-zA-Z-]+)+(\s+\S+)*\s/(\s|$)"""),
        Regex("""\brm\s+-rf\s+~(\s|$)"""),
        Regex("""\brm(\s+-[a-zA-Z-]+)+(\s+\S+)*\s~(\s|$)"""),
        Regex("""\bdd\s+.*of=/dev/(block|sd[a-z])"""),
        Regex("""\bmkfs\b"""),
        Regex("""\bchmod\s+-R\s+777\s+/(\s|$)"""),
        Regex(""":\(\)\{.*\};\s*:"""),
        Regex("""\b(drop|truncate)\s+(table|database)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sudo|su)\b"""),
    )
    private val risky = listOf(
        Regex("""\brm\s+-[a-zA-Z]*r"""),
        Regex("""\bchmod\b|\bchown\b"""),
        Regex("""\bdeploy\b"""),
    )

    fun classify(command: String): CommandRisk = when {
        banned.any { it.containsMatchIn(command) } -> CommandRisk.BANNED
        command.contains("--force") || risky.any { it.containsMatchIn(command) } -> CommandRisk.RISKY
        else -> CommandRisk.SAFE
    }
}
