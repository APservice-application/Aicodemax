package com.aicodemax.tools.browser

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/**
 * CP-147 (spec แก้ai §6): URL intelligence. The model must NEVER invent URLs
 * without validation — every navigation input goes through here.
 *
 * - `google` → `https://www.google.com`
 * - `google.com` → `https://google.com`
 * - `www.google.com` → `https://www.google.com`
 * - `https://google.com` → unchanged
 * - NEVER produces `http://google` (bare words gain `www.` + `.com`, https).
 * - Strings with spaces are NOT URLs (search queries) → failure.
 */
object UrlResolver {
    private val singleWord = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    fun normalize(input: String): Outcome<String> {
        val text = input.trim()
        if (text.isEmpty()) return Outcome.Failure(AppError("INVALID_URL", "empty URL"))
        if (text.any { it.isWhitespace() }) {
            return Outcome.Failure(AppError("NOT_A_URL", "input contains spaces — treat as search query"))
        }
        // Dangerous/bare schemes without "//" (javascript:, data:, ftp:, ...).
        val bareScheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$").matchEntire(text)
        // Rest starting with a digit means host:port, not scheme: (localhost:8080).
        if (bareScheme != null && !text.contains("://") && bareScheme.groupValues[2].firstOrNull()?.isDigit() != true) {
            val scheme = bareScheme.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") {
                return Outcome.Failure(AppError("UNSUPPORTED_SCHEME", "scheme '$scheme' is not allowed"))
            }
            return Outcome.Failure(AppError("INVALID_URL", "bad scheme separator (want '://')"))
        }
        val schemeSplit = text.split("://", limit = 2)
        if (schemeSplit.size == 2) {
            val scheme = schemeSplit[0].lowercase()
            val rest = schemeSplit[1]
            if (scheme != "http" && scheme != "https") {
                return Outcome.Failure(AppError("UNSUPPORTED_SCHEME", "scheme '$scheme' is not allowed"))
            }
            if (rest.isEmpty() || rest.startsWith("/")) {
                return Outcome.Failure(AppError("INVALID_URL", "missing host"))
            }
            val host = rest.substringBefore('/').substringBefore('?')
            if (host.equals("localhost", ignoreCase = true) || ipv4.matches(host)) return Outcome.Success(text)
            if (!host.contains('.')) {
                // http://google -> https://www.google.com (never bare http://word).
                if (!singleWord.matches(host)) {
                    return Outcome.Failure(AppError("INVALID_URL", "bad host '$host'"))
                }
                return Outcome.Success("https://www.${host.lowercase()}.com")
            }
            return Outcome.Success("$scheme://$rest")
        }
        if (text.contains("://")) return Outcome.Failure(AppError("INVALID_URL", "bad scheme separator"))
        val host = text.substringBefore('/').substringBefore('?')
        if (host.substringBefore(':').equals("localhost", ignoreCase = true) || ipv4.matches(host)) {
            return Outcome.Success("http://$text")
        }
        if (host.contains('.')) {
            if (host.startsWith('.') || host.endsWith('.') || ".." in host) {
                return Outcome.Failure(AppError("INVALID_URL", "bad host '$host'"))
            }
            return Outcome.Success("https://$text")
        }
        // Bare word -> domain guess.
        if (!singleWord.matches(host)) {
            return Outcome.Failure(AppError("INVALID_URL", "'$text' is not a URL"))
        }
        return Outcome.Success("https://www.${host.lowercase()}.com")
    }
}
