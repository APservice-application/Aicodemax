package com.aicodemax.tools.supabase_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.supabase.SupaResponse
import com.aicodemax.tools.supabase.SupaTransport
import com.aicodemax.tools.supabase.SupabaseClient
import com.aicodemax.tools.supabase.SupabaseConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseToolExecutorTest {
    private class FakeTransport : SupaTransport {
        override fun get(baseUrl: String, path: String, key: String): Outcome<SupaResponse> =
            Outcome.Success(
                when {
                    path == "/auth/v1/health" && key == "good" -> SupaResponse(200, "{\"version\":\"2.x\"}")
                    path.startsWith("/rest/v1/todos") && key == "good" -> SupaResponse(200, "[{\"id\":1}]")
                    else -> SupaResponse(401, "bad key")
                },
            )

        override fun post(
            baseUrl: String,
            path: String,
            key: String,
            jsonBody: String,
            extraHeaders: Map<String, String>,
        ): Outcome<SupaResponse> = Outcome.Success(SupaResponse(201, "[{\"id\":2}]"))
    }

    private fun run(key: String?, action: String, args: Map<String, String>): ToolResult =
        runBlocking {
            val config = key?.let { SupabaseConfig("https://xyz.supabase.co", it) }
            val client = SupabaseClient(FakeTransport(), { config })
            val call = ToolCall(id = "c1", toolId = "supabase", action = action, args = args)
            (SupabaseToolExecutor(client).execute(call) as Outcome.Success<ToolResult>).value
        }

    @Test
    fun healthAndQueryFlow() {
        val h = run("good", "health", emptyMap())
        assertTrue(h.output.ifBlank { h.error }, h.ok)
        val q = run("good", "query", mapOf("table" to "todos", "limit" to "5"))
        assertTrue(q.output.ifBlank { q.error }, q.ok && q.output.contains("\"id\":1"))
        val bad = run("bad", "query", mapOf("table" to "todos"))
        assertTrue(!bad.ok && bad.error.contains("คีย์ไม่ถูกต้อง"))
        val none = run(null, "health", emptyMap())
        assertTrue(!none.ok && none.error.contains("Project URL"))
    }

    @Test
    fun authParseFailureIsHonest() {
        val r = run("good", "auth", mapOf("email" to "a@x.co", "password" to "pw"))
        // Fake returns a row body, not a session — parse must fail honestly.
        assertTrue(!r.ok)
        val missing = run("good", "auth", mapOf("email" to "a@x.co"))
        assertTrue(!missing.ok && missing.error.contains("email + password"))
    }

    @Test
    fun badArgsAreHonest() {
        assertTrue(!run("good", "query", emptyMap()).ok)
        assertTrue(!run("good", "insert", mapOf("table" to "todos")).ok)
        val r = run("good", "drop", emptyMap())
        assertTrue(!r.ok && r.error.contains("unknown action"))
        assertEquals("supabase", SupabaseToolExecutor(SupabaseClient(FakeTransport(), { null })).toolId)
    }
}
