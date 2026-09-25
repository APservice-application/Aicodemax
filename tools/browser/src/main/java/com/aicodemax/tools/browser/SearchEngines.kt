package com.aicodemax.tools.browser

import java.net.URLEncoder

/**
 * CP-147 (spec แก้ai §7): configurable search engines. Chrome-class default
 * is Google; the user may switch (Bing / DuckDuckGo / Brave / custom).
 */
data class SearchEngine(
    val id: String,
    val name: String,
    /** Query URL with `{q}` as the placeholder for the encoded query. */
    val queryUrl: String,
)

object SearchEngines {
    val GOOGLE = SearchEngine("google", "Google", "https://www.google.com/search?q={q}")
    val BING = SearchEngine("bing", "Bing", "https://www.bing.com/search?q={q}")
    val DUCKDUCKGO = SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}")
    val BRAVE = SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q={q}")

    val DEFAULT: SearchEngine = GOOGLE

    fun all(): List<SearchEngine> = listOf(GOOGLE, BING, DUCKDUCKGO, BRAVE)

    fun byId(id: String): SearchEngine? = all().firstOrNull { it.id == id }

    fun custom(name: String, queryUrl: String): SearchEngine {
        require("{q}" in queryUrl) { "custom engine URL must contain {q}" }
        return SearchEngine("custom:" + name.lowercase().replace(Regex("\\s+"), "-"), name, queryUrl)
    }

    fun searchUrl(engine: SearchEngine, query: String): String =
        engine.queryUrl.replace("{q}", URLEncoder.encode(query.trim(), "UTF-8"))
}
