package com.aicodemax.tools.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** CP-147: spec แก้ai §7 acceptance — URL vs SEARCH vs COMMAND split. */
class IntentResolverTest {
    @Test
    fun specExamples() {
        assertEquals(
            ResolvedIntent(NavIntent.URL, "https://github.com"),
            IntentResolver.resolve("เปิด github.com"),
        )
        assertEquals(
            ResolvedIntent(NavIntent.URL, "https://www.google.com"),
            IntentResolver.resolve("เปิด Google"),
        )
        assertEquals(
            ResolvedIntent(NavIntent.SEARCH, "Qwen3 4B"),
            IntentResolver.resolve("ค้นหา Qwen3 4B"),
        )
        assertEquals(
            ResolvedIntent(NavIntent.SEARCH, "หาร้านซ่อมมอเตอร์ไซค์ศรีสะเกษ"),
            IntentResolver.resolve("หาร้านซ่อมมอเตอร์ไซค์ศรีสะเกษ"),
        )
    }

    @Test
    fun bareQueryIsSearchNeverUrl() {
        val r = IntentResolver.resolve("Qwen3 4B")
        assertEquals(NavIntent.SEARCH, r.intent)
        assertEquals("Qwen3 4B", r.target)
    }

    @Test
    fun bareUrlShapesStayUrl() {
        assertEquals(NavIntent.URL, IntentResolver.resolve("github.com").intent)
        assertEquals(NavIntent.URL, IntentResolver.resolve("https://a.com/x").intent)
        assertEquals(NavIntent.URL, IntentResolver.resolve("google").intent)
    }

    @Test
    fun commandsResolve() {
        assertEquals(
            ResolvedIntent(NavIntent.COMMAND, "new_tab"),
            IntentResolver.resolve("แท็บใหม่"),
        )
        assertEquals(
            ResolvedIntent(NavIntent.COMMAND, "back"),
            IntentResolver.resolve("/back"),
        )
    }

    @Test
    fun searchEnginesBuildUrls() {
        assertEquals(
            "https://www.google.com/search?q=Qwen3+4B",
            SearchEngines.searchUrl(SearchEngines.DEFAULT, "Qwen3 4B"),
        )
        assertEquals("google", SearchEngines.byId("google")?.id)
        val custom = SearchEngines.custom("My", "https://x.test/s?q={q}")
        assertEquals("https://x.test/s?q=a+b", SearchEngines.searchUrl(custom, "a b"))
    }
}
