package com.aicodemax.tools.browser

import java.net.URI

/** Page content extraction (CP-26 Browser Engine backfill; MASTER §34). Pure engine for the WebView runtime + AI browser agent. */
object HtmlReader {
    private val scriptStyle = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val tags = Regex("<[^>]+>")
    private val titleTag = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val hrefAttr = Regex("""(?i)href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    private val entities = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&nbsp;" to " ",
    )

    /** Visible-ish text: scripts/styles removed, tags stripped, entities decoded. */
    fun text(html: String): String {
        var out = scriptStyle.replace(html, " ")
        out = tags.replace(out, " ")
        for ((entity, char) in entities) out = out.replace(entity, char)
        return out.replace(Regex("\\s+"), " ").trim()
    }

    fun title(html: String): String =
        titleTag.find(html)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()

    /** Absolute link targets; unresolvable hrefs are skipped. */
    fun links(html: String, baseUrl: String = ""): List<String> {
        val base = if (baseUrl.isBlank()) null else runCatching { URI(baseUrl) }.getOrNull()
        return hrefAttr.findAll(html).mapNotNull { match ->
            val raw = match.groupValues[1].ifBlank { match.groupValues[2] }.ifBlank { match.groupValues[3] }.trim()
            if (raw.isBlank() || raw.startsWith("#") ||
                raw.startsWith("javascript:", ignoreCase = true) ||
                raw.startsWith("mailto:", ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            runCatching {
                val uri = URI(raw)
                if (uri.isAbsolute) uri.toString() else base?.resolve(uri)?.toString()
            }.getOrNull()
        }.distinct().toList()
    }

    private fun decode(text: String): String {
        var out = tags.replace(text, "")
        for ((entity, char) in entities) out = out.replace(entity, char)
        return out
    }
}
