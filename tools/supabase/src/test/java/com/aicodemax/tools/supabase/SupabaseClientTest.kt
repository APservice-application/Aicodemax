package com.aicodemax.tools.supabase

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseClientTest {
    private class FakeTransport(
        private val responses: Map<String, SupaResponse> = emptyMap(),
        private val default: SupaResponse = SupaResponse(200, "[]"),
    ) : SupaTransport {
        val requests = mutableListOf<String>()
        var lastKey: String? = null
        var lastBody: String? = null

        override fun get(baseUrl: String, path: String, key: String): Outcome<SupaResponse> {
            requests += "GET $path"
            lastKey = key
            return Outcome.Success(responses[path] ?: default)
        }

        override fun post(
            baseUrl: String,
            path: String,
            key: String,
            jsonBody: String,
            extraHeaders: Map<String, String>,
        ): Outcome<SupaResponse> {
            requests += "POST $path"
            lastKey = key
            lastBody = jsonBody
            return Outcome.Success(responses[path] ?: default)
        }
    }

    private fun client(
        transport: FakeTransport,
        config: SupabaseConfig? = SupabaseConfig("https://xyz.supabase.co", "test-key"),
    ) = SupabaseClient(transport, { config })

    @Test
    fun healthOk() {
        val t = FakeTransport(mapOf("/auth/v1/health" to SupaResponse(200, "{\"version\":\"2.x\"}")))
        val res = client(t).health()
        assertTrue(res is Outcome.Success)
        assertTrue((res as Outcome.Success).value.contains("ตอบกลับปกติ"))
        assertEquals("test-key", t.lastKey)
    }

    @Test
    fun healthWithoutConfigFailsHonestly() {
        val res = client(FakeTransport(), null).health()
        assertTrue(res is Outcome.Failure)
        assertTrue((res as Outcome.Failure).error.message.contains("Project URL"))
    }

    @Test
    fun healthBadKeyExplains401() {
        val t = FakeTransport(mapOf("/auth/v1/health" to SupaResponse(401, "{\"msg\":\"bad key\"}")))
        val res = client(t).health()
        assertTrue(res is Outcome.Failure)
        assertTrue((res as Outcome.Failure).error.message.contains("คีย์ไม่ถูกต้อง"))
    }

    @Test
    fun signInParsesSession() {
        val t = FakeTransport(
            mapOf(
                "/auth/v1/token?grant_type=password" to
                    SupaResponse(200, "{\"access_token\":\"tok123\",\"token_type\":\"bearer\",\"expires_in\":3600}"),
            ),
        )
        val res = client(t).signIn("a@x.co", "pw")
        assertTrue(res is Outcome.Success)
        val session = (res as Outcome.Success).value
        assertEquals("tok123", session.accessToken)
        assertEquals(3600, session.expiresIn)
        assertTrue(t.lastBody!!.contains("a@x.co"))
        assertTrue(client(t).signIn("", "pw") is Outcome.Failure)
    }

    @Test
    fun queryBuildsPostgrestPath() {
        val t = FakeTransport(mapOf("/rest/v1/todos?select=*&limit=5" to SupaResponse(200, "[{\"id\":1}]")))
        val res = client(t).query("todos", "*", 5)
        assertTrue(res is Outcome.Success)
        assertEquals("[{\"id\":1}]", (res as Outcome.Success).value)
        assertTrue(client(t).query("todos;drop", "*", 5) is Outcome.Failure)
        assertTrue(client(t).query("todos", "*", 0) is Outcome.Failure)
    }

    @Test
    fun insertSendsRow() {
        val t = FakeTransport(mapOf("/rest/v1/todos" to SupaResponse(201, "[{\"id\":2}]")))
        val res = client(t).insert("todos", "{\"title\":\"hi\"}")
        assertTrue(res is Outcome.Success)
        assertTrue((res as Outcome.Success).value.contains("\"id\":2"))
        assertEquals("{\"title\":\"hi\"}", t.lastBody)
        assertTrue(client(t).insert("todos", "  ") is Outcome.Failure)
    }

    @Test
    fun configValidation() {
        assertTrue(SupabaseConfig("", "").validate() is Outcome.Failure)
        assertTrue(SupabaseConfig("ftp://x", "k").validate() is Outcome.Failure)
        assertTrue(SupabaseConfig("https://xyz.supabase.co/", "k").validate() is Outcome.Success)
        val store = InMemorySupabaseConfigStore()
        assertTrue(store.load() == null)
        store.save(SupabaseConfig("https://xyz.supabase.co/", "k"))
        assertEquals("https://xyz.supabase.co", store.load()!!.url)
        store.clear()
        assertTrue(store.load() == null)
    }
}
