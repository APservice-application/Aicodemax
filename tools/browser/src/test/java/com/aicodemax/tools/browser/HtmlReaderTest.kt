package com.aicodemax.tools.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlReaderTest {
    private val html = """
        <html><head><title>Demo &amp; Co</title>
        <style>.x{color:red}</style>
        <script>alert('hi')</script></head>
        <body><h1>Hello</h1><p>Price &lt; 5 &amp; ok</p>
        <a href="/docs">docs</a><a href="https://cdn.example.com/f.js">cdn</a>
        <a href="#top">top</a><a href="mailto:a@b.c">mail</a></body></html>
    """.trimIndent()

    @Test
    fun extractsTitleAndText() {
        assertEquals("Demo & Co", HtmlReader.title(html))
        val text = HtmlReader.text(html)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("Price < 5 & ok"))
        assertTrue(!text.contains("alert"))
        assertTrue(!text.contains("color:red"))
    }

    @Test
    fun extractsAbsoluteLinks() {
        val links = HtmlReader.links(html, "https://example.com/page")
        assertEquals(listOf("https://example.com/docs", "https://cdn.example.com/f.js"), links)
        assertEquals(listOf("https://cdn.example.com/f.js"), HtmlReader.links(html))
    }
}
