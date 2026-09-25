package com.aicodemax.tools.browser

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-147: spec แก้ai §6 acceptance — the model must never invent bad URLs. */
class UrlResolverTest {
    private fun ok(input: String): String {
        val r = UrlResolver.normalize(input)
        assertTrue(input, r is Outcome.Success)
        return (r as Outcome.Success).value
    }

    private fun fails(input: String): String {
        val r = UrlResolver.normalize(input)
        assertTrue(input, r is Outcome.Failure)
        return (r as Outcome.Failure).error.code
    }

    @Test
    fun specExamples() {
        assertEquals("https://www.google.com", ok("google"))
        assertEquals("https://google.com", ok("google.com"))
        assertEquals("https://www.google.com", ok("www.google.com"))
        assertEquals("https://google.com", ok("https://google.com"))
    }

    @Test
    fun neverBareHttpWord() {
        assertEquals("https://www.google.com", ok("http://google"))
        assertTrue(!ok("google").startsWith("http://"))
    }

    @Test
    fun queriesAreNotUrls() {
        assertEquals("NOT_A_URL", fails("Qwen3 4B"))
        // Glued Thai (no spaces) is still not a URL — IntentResolver searches it.
        assertEquals("INVALID_URL", fails("หาร้านซ่อมมอเตอร์ไซค์"))
        assertEquals("NOT_A_URL", fails("hello world"))
    }

    @Test
    fun rejectsDangerousAndBroken() {
        assertEquals("UNSUPPORTED_SCHEME", fails("javascript:alert(1)"))
        assertEquals("UNSUPPORTED_SCHEME", fails("ftp://x.com/f"))
        assertEquals("INVALID_URL", fails(""))
        assertEquals("INVALID_URL", fails("https://"))
        assertEquals("INVALID_URL", fails("google..com"))
    }

    @Test
    fun keepsPathsAndLocalhost() {
        assertEquals("https://google.com/x?q=1", ok("google.com/x?q=1"))
        assertEquals("http://localhost:8080/a", ok("localhost:8080/a"))
        assertEquals("https://example.com:8443/p", ok("https://example.com:8443/p"))
    }
}
