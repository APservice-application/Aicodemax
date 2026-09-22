package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatProviderTest {
    private class FakeTransport(
        val replies: MutableMap<String, String> = mutableMapOf(),
        var lastUrl: String = "",
        var lastHeaders: Map<String, String> = emptyMap(),
        var lastBody: String = "",
    ) : LlmTransport {
        override suspend fun postJson(url: String, headers: Map<String, String>, body: String): Outcome<String> {
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return replies[url]?.let { Outcome.Success(it) }
                ?: Outcome.Failure(com.aicodemax.core.common.AppError("NO_STUB", url))
        }

        override suspend fun get(url: String, headers: Map<String, String>): Outcome<String> =
            postJson(url, headers, "")
    }

    private val chatReply = """{"choices":[{"message":{"role":"assistant","content":"สวัสดีครับ"}}]}"""

    @Test
    fun chatPostsOpenAiShapeAndParsesContent() = runBlocking {
        val transport = FakeTransport(
            mutableMapOf("https://x.test/v1/chat/completions" to chatReply),
        )
        val provider = OpenAiCompatProvider("https://x.test/v1/", { "k" }, transport)
        val reply = (provider.chat("m", listOf(LlmMessage("user", "hi"))) as Outcome.Success<LlmReply>).value
        assertEquals("สวัสดีครับ", reply.content)
        assertEquals("https://x.test/v1/chat/completions", transport.lastUrl)
        assertEquals("Bearer k", transport.lastHeaders["Authorization"])
        assertTrue(transport.lastBody.contains("\"model\":\"m\""))
        assertTrue(transport.lastBody.contains("\"role\":\"user\""))
    }

    @Test
    fun chatWithoutKeyOmitsAuthHeader() = runBlocking {
        val transport = FakeTransport(
            mutableMapOf("http://127.0.0.1:8080/v1/chat/completions" to chatReply),
        )
        val provider = OpenAiCompatProvider("http://127.0.0.1:8080/v1", { null }, transport)
        provider.chat("local", listOf(LlmMessage("user", "hi")))
        assertTrue(!transport.lastHeaders.containsKey("Authorization"))
    }

    @Test
    fun providerErrorIsHonest() = runBlocking {
        val transport = FakeTransport(
            mutableMapOf("https://x.test/v1/chat/completions" to """{"error":{"message":"bad key"}}"""),
        )
        val provider = OpenAiCompatProvider("https://x.test/v1", { "k" }, transport)
        val result = provider.chat("m", listOf(LlmMessage("user", "hi")))
        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error.message.contains("bad key"))
    }

    @Test
    fun healthCountsModels() = runBlocking {
        val transport = FakeTransport(
            mutableMapOf("https://x.test/v1/models" to """{"data":[{"id":"a"},{"id":"b"}]}"""),
        )
        val provider = OpenAiCompatProvider("https://x.test/v1", { null }, transport)
        val health = (provider.health() as Outcome.Success<String>).value
        assertEquals("ok (2 models)", health)
    }

    @Test
    fun visionMessageUsesImageUrl() = runBlocking {
        val transport = FakeTransport(
            mutableMapOf("https://x.test/v1/chat/completions" to chatReply),
        )
        val provider = OpenAiCompatProvider("https://x.test/v1", { null }, transport)
        provider.chat("m", listOf(LlmMessage("user", "นี่คืออะไร", "QUJD")))
        assertTrue(transport.lastBody.contains("image_url"))
        assertTrue(transport.lastBody.contains("data:image/jpeg;base64,QUJD"))
    }
}
