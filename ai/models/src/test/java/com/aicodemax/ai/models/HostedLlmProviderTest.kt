package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedLlmProviderTest {
    private data class Call(val url: String, val headers: Map<String, String>, val body: String?)
    private class FakeTransport : HostedHttpTransport {
        val calls = mutableListOf<Call>()
        var respond: (Call) -> HostedHttpResponse = { HostedHttpResponse(503, "") }
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HostedHttpResponse {
            val call = Call(url, headers, body)
            calls.add(call)
            return respond(call)
        }
    }

    @Test fun noConsentNeverSendsUserPrompt() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("gemini", "gemini-secret123")
            selectModel("gemini", "models/gemini-example")
        }
        val fake = FakeTransport()
        val outcome = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "secret prompt")))
        assertTrue(outcome is Outcome.Failure)
        assertEquals(0, fake.calls.size)
    }

    @Test fun invalidKeyMovesToNextKeyAndGeminiUsesOwnSchema() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("gemini", "invalid-secret123")
            add("gemini", "valid-secret-456")
            selectModel("gemini", "models/gemini-example")
            setNetworkConsent(true)
        }
        val fake = FakeTransport()
        fake.respond = { call ->
            if (call.headers["x-goog-api-key"] == "invalid-secret123") HostedHttpResponse(401, "")
            else HostedHttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"สวัสดี"}]}}]}""")
        }
        val provider = HostedLlmProvider(store, fake)
        val response = provider.chat("ignored", listOf(
            LlmMessage("system", "private system"), LlmMessage("user", "สวัสดี"),
        )) as Outcome.Success
        assertEquals("สวัสดี", response.value.content)
        assertTrue(provider.lastSuccess.value.contains("Google Gemini API"))
        assertEquals(2, fake.calls.size)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-example:generateContent", fake.calls[0].url)
        assertTrue(fake.calls[1].body!!.contains("systemInstruction"))
        assertFalse(fake.calls[1].url.contains("valid-secret-456"))
    }

    @Test fun rateLimitSkipsRestOfSameProviderThenUsesConfiguredNextProvider() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("gemini", "first-google-key")
            add("gemini", "second-google-key")
            selectModel("gemini", "models/gemini-example")
            add("openai", "openai-secret123")
            selectModel("openai", "gpt-4o-mini")
            selectPrimary("gemini")
            setNetworkConsent(true)
        }
        val fake = FakeTransport()
        fake.respond = { call ->
            if (call.url.contains("googleapis.com")) HostedHttpResponse(429, "rate limited", 60)
            else HostedHttpResponse(200, """{"choices":[{"message":{"content":"from openai"}}]}""")
        }
        val response = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "go")))
        assertEquals("from openai", (response as Outcome.Success).value.content)
        assertEquals(2, fake.calls.size) // NOT second Google key
        assertTrue(fake.calls[1].url.contains("api.openai.com"))
    }

    @Test fun modelAvailableOnlyToSecondKeyIsTriedWithoutRotatingOnRateLimit() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("openai", "first-secret-123")
            add("openai", "second-secret-456")
            selectModel("openai", "private-model")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { call ->
            if (call.headers["Authorization"] == "Bearer first-secret-123") HostedHttpResponse(404, "")
            else HostedHttpResponse(200, """{"choices":[{"message":{"content":"second key"}}]}""")
        } }
        val result = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "go"))) as Outcome.Success
        assertEquals("second key", result.value.content)
        assertEquals(2, fake.calls.size)
    }

    @Test fun invalidRequestNeverRetriesOrSwitches() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("openai", "first-secret-123")
            add("openai", "second-secret-456")
            selectModel("openai", "gpt-4o-mini")
            add("groq", "groq-secret-123")
            selectModel("groq", "some-model")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(400, "invalid request") } }
        val result = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "go"))) as Outcome.Failure
        assertEquals("HOSTED_HTTP_400", result.error.code)
        assertEquals(1, fake.calls.size)
    }

    @Test fun forbiddenPolicyCannotBeBypassedByCyclingKeys() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("openai", "first-secret-123")
            add("openai", "second-secret-456")
            selectModel("openai", "gpt-4o-mini")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(403, "forbidden") } }
        val result = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "go"))) as Outcome.Failure
        assertEquals("HOSTED_HTTP_403", result.error.code)
        assertEquals(1, fake.calls.size)
    }

    @Test fun cohereDoesNotDropImagesAndFallsBackWithoutSending() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("cohere", "cohere-secret123")
            selectModel("cohere", "command-vision")
            add("openai", "openai-secret123")
            selectModel("openai", "gpt-4o-mini")
            selectPrimary("cohere")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(200,
            """{"choices":[{"message":{"content":"saw image"}}]}""") } }
        val response = HostedLlmProvider(store, fake).chat("ignored",
            listOf(LlmMessage("user", "describe", "iVBORw0KGgoAAA"))) as Outcome.Success
        assertEquals("saw image", response.value.content)
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls[0].url.contains("api.openai.com"))
        assertTrue(fake.calls[0].body!!.contains("data:image/png;base64"))
    }

    @Test fun unparseableSuccessNeverRepeatsPaidPrompt() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("openai", "first-secret-123")
            selectModel("openai", "gpt-4o-mini")
            add("groq", "groq-secret-123")
            selectModel("groq", "some-model")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(200, "{}") } }
        val result = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "go"))) as Outcome.Failure
        assertEquals("HOSTED_REPLY", result.error.code)
        assertEquals(1, fake.calls.size)
    }

    @Test fun anthropicHasSeparateSystemAndParsesTextBlocks() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("anthropic", "claude-secret-123")
            selectModel("anthropic", "claude-new")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(200,
            """{"content":[{"type":"text","text":"คำตอบ"}]}""") } }
        val response = HostedLlmProvider(store, fake).chat("ignored", listOf(
            LlmMessage("system", "Thai"), LlmMessage("user", "go"))) as Outcome.Success
        assertEquals("คำตอบ", response.value.content)
        assertTrue(fake.calls[0].url.endsWith("/v1/messages"))
        assertTrue(fake.calls[0].body!!.contains("\"system\":\"Thai\""))
        assertEquals("claude-secret-123", fake.calls[0].headers["x-api-key"])
    }

    @Test fun currentOpenAiResponsesSchemaIsUsedForReasoningModels() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("openai", "openai-secret123")
            selectModel("openai", "gpt-5.4")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(200,
            """{"output":[{"content":[{"type":"output_text","text":"ตอบแล้ว"}]}]}""") } }
        val response = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "hi"))) as Outcome.Success
        assertEquals("ตอบแล้ว", response.value.content)
        assertTrue(fake.calls[0].url.endsWith("/v1/responses"))
        assertTrue(fake.calls[0].body!!.contains("max_output_tokens"))
    }

    @Test fun nonChatSelectionNeverRegistersAsBrainEvenAfterRestart() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("openai", "openai-secret123")
            selectModel("openai", "gpt-image-new", canTryChat = false)
            setNetworkConsent(true)
        }
        val fake = FakeTransport()
        val manager = HostedLlmProvider(store, fake)
        assertEquals(null, manager.selectedRoute())
        assertTrue(manager.routeLabels().isEmpty())
        assertTrue(manager.chat("ignored", listOf(LlmMessage("user", "hi"))) is Outcome.Failure)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun perplexityAgentRoutesViaResponsesAliasNotChatCompletions() = runBlocking {
        val store = InMemoryHostedKeyStore().apply {
            add("perplexity", "pplx-secret123")
            selectModel("perplexity", "perplexity/sonar")
            setNetworkConsent(true)
        }
        val fake = FakeTransport().apply { respond = { HostedHttpResponse(200,
            """{"output":[{"content":[{"type":"output_text","text":"answer with source"}]}]}""") } }
        val response = HostedLlmProvider(store, fake).chat("ignored", listOf(LlmMessage("user", "question"))) as Outcome.Success
        assertEquals("answer with source", response.value.content)
        assertTrue(fake.calls[0].url.endsWith("/v1/responses"))
    }

    @Test fun noHardKeyLimitAndChangingConfigRevokesOldConsent() {
        val store = InMemoryHostedKeyStore()
        repeat(25) { store.add("openai", "secret-value-${it + 100}") }
        assertEquals(25, store.keys("openai").size)
        store.selectModel("openai", "model-from-api")
        store.setNetworkConsent(true)
        store.add("groq", "groq-secret-xyz")
        assertFalse(store.networkConsent())
    }
}
