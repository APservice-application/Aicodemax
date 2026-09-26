package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedModelDiscoveryTest {
    private class FakeTransport : HostedHttpTransport {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        var respond: (String, Map<String, String>) -> HostedHttpResponse = { _, _ -> HostedHttpResponse(404, "") }
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HostedHttpResponse {
            calls.add(Triple(method, url, headers))
            return respond(url, headers)
        }
    }

    @Test fun googleListsAllPagesAndClassifiesEmbeddingWithoutDroppingIt() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("gemini", "secretgoogle123") }
        val fake = FakeTransport()
        fake.respond = { url, _ ->
            if (url.contains("pageToken=")) HostedHttpResponse(200, """{"models":[{"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}""")
            else HostedHttpResponse(200, """{"models":[{"name":"models/gemini-next","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"abc+def"}""")
        }
        val result = HostedModelDiscovery(fake, keys).list("gemini") as Outcome.Success
        assertTrue(result.value.complete)
        assertEquals(2, result.value.models.size)
        assertEquals(setOf(HostedCapability.CHAT), result.value.models[0].capabilities)
        assertEquals(setOf(HostedCapability.EMBEDDING), result.value.models[1].capabilities)
        assertTrue(fake.calls[1].second.contains("pageToken=abc%2Bdef"))
        assertFalse(fake.calls[0].second.contains("secretgoogle123"))
        assertEquals("secretgoogle123", fake.calls[0].third["x-goog-api-key"])
    }

    @Test fun geminiImageModelIsNotFalselyPresentedAsChat() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("gemini", "secretgoogle123") }
        val fake = FakeTransport().apply { respond = { _, _ -> HostedHttpResponse(200,
            """{"models":[{"name":"models/gemini-3.1-flash-image","supportedGenerationMethods":["generateContent"]}]}""") } }
        val result = HostedModelDiscovery(fake, keys).list("gemini") as Outcome.Success
        assertEquals(1, result.value.models.size)
        assertTrue(HostedCapability.IMAGE in result.value.models[0].capabilities)
        assertFalse(result.value.models[0].canTryChat)
    }

    @Test fun anthropicPaginationAndBadFirstKeyFallThrough() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply {
            add("anthropic", "invalid-first-key")
            add("anthropic", "valid-second-key")
        }
        val fake = FakeTransport()
        fake.respond = { url, headers ->
            when {
                headers["x-api-key"] == "invalid-first-key" -> HostedHttpResponse(401, "")
                url.contains("after_id=") -> HostedHttpResponse(200, """{"data":[{"id":"claude-2"}],"has_more":false}""")
                else -> HostedHttpResponse(200, """{"data":[{"id":"claude-1"}],"has_more":true,"last_id":"claude-1"}""")
            }
        }
        val result = HostedModelDiscovery(fake, keys).list("anthropic") as Outcome.Success
        assertEquals(2, result.value.models.size)
        assertEquals(3, fake.calls.size)
        assertEquals("2023-06-01", fake.calls[2].third["anthropic-version"])
        assertTrue(fake.calls[2].second.contains("after_id=claude-1"))
    }

    @Test fun catalogsFromSeparateValidKeysAreUnioned() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply {
            add("openai", "first-key-here")
            add("openai", "second-key-here")
        }
        val fake = FakeTransport()
        fake.respond = { _, headers ->
            val id = if (headers["Authorization"] == "Bearer first-key-here") "gpt-first" else "gpt-second"
            HostedHttpResponse(200, """{"data":[{"id":"$id"}]}""")
        }
        val result = HostedModelDiscovery(fake, keys).list("openai") as Outcome.Success
        assertTrue(result.value.complete)
        assertEquals(setOf("gpt-first", "gpt-second"), result.value.models.map { it.id }.toSet())
        assertEquals(2, fake.calls.size)
    }

    @Test fun rateLimitNeverCyclesSameProviderKeys() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply {
            add("openai", "first-key-here")
            add("openai", "second-key-here")
        }
        val fake = FakeTransport()
        fake.respond = { _, _ -> HostedHttpResponse(429, "blocked") }
        val result = HostedModelDiscovery(fake, keys).list("openai") as Outcome.Failure
        assertEquals("HOSTED_HTTP_429", result.error.code)
        assertEquals(1, fake.calls.size)
    }

    @Test fun coherePaginationAndAllTypesAreExposed() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("cohere", "cohere-secret-here") }
        val fake = FakeTransport()
        fake.respond = { url, _ ->
            if (url.contains("page_token=")) HostedHttpResponse(200,
                """{"models":[{"name":"rerank-now","endpoints":["rerank"]}]}""")
            else HostedHttpResponse(200,
                """{"models":[{"name":"command-fast","endpoints":["chat"]},{"name":"embed-now","endpoints":["embed"]}],"next_page_token":"page-two"}""")
        }
        val result = HostedModelDiscovery(fake, keys).list("cohere") as Outcome.Success
        assertEquals(3, result.value.models.size)
        assertTrue(result.value.models[0].canTryChat)
        assertFalse(result.value.models[1].canTryChat)
        assertTrue(HostedCapability.RERANK in result.value.models[2].capabilities)
    }

    @Test fun openAiDoesNotHideAnyReturnedIdsOrClaimUnknownPricesAreFree() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("openai", "example-token-1234") }
        val fake = FakeTransport()
        fake.respond = { _, _ -> HostedHttpResponse(200,
            """{"data":[{"id":"gpt-new"},{"id":"text-embedding-new"},{"id":"sora-new"},{"id":"gpt-image-new"}]}""") }
        val result = HostedModelDiscovery(fake, keys).list("openai") as Outcome.Success
        assertEquals(4, result.value.models.size)
        assertTrue(HostedCapability.UNKNOWN in result.value.models.first().capabilities)
        assertTrue(HostedCapability.EMBEDDING in result.value.models[1].capabilities)
        assertTrue(HostedCapability.VIDEO in result.value.models[2].capabilities)
        assertTrue(HostedCapability.IMAGE in result.value.models[3].capabilities)
        assertEquals(null, result.value.models.first().priceNote)
    }

    @Test fun togetherCatalogIncludesAllReturnedTypesInsteadOfOnlyChat() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("together", "together-secret123") }
        val fake = FakeTransport().apply { respond = { _, _ -> HostedHttpResponse(200,
            """[{"id":"team/chat","type":"chat","display_name":"Chat"},{"id":"team/image","type":"image"},{"id":"team/embed","type":"embedding"}]""") } }
        val result = HostedModelDiscovery(fake, keys).list("together") as Outcome.Success
        assertEquals(3, result.value.models.size)
        assertEquals("Chat", result.value.models[0].name)
        assertTrue(HostedCapability.CHAT in result.value.models[0].capabilities)
        assertTrue(HostedCapability.IMAGE in result.value.models[1].capabilities)
        assertTrue(HostedCapability.EMBEDDING in result.value.models[2].capabilities)
        assertFalse(result.value.models[2].canTryChat)
    }

    @Test fun huggingFaceRouterListsOnlyItsPublishedInferenceModels() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("huggingface", "hf_key_for_tests_123") }
        val fake = FakeTransport().apply { respond = { _, _ -> HostedHttpResponse(200,
            """{"data":[{"id":"deepseek-ai/DeepSeek-V4-Pro","architecture":{"input_modalities":["text"],"output_modalities":["text"]}}]}""") } }
        val result = HostedModelDiscovery(fake, keys).list("huggingface") as Outcome.Success
        assertEquals(1, result.value.models.size)
        assertTrue(result.value.models[0].canTryChat)
        assertEquals("https://router.huggingface.co/v1/models", fake.calls[0].second)
    }

    @Test fun unusableCatalogRowsMakeCompletenessFalse() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("openai", "example-token-1234") }
        val fake = FakeTransport().apply { respond = { _, _ -> HostedHttpResponse(200,
            """{"data":[{"id":"gpt-ok"},{"owner":"no model id"}]}""") } }
        val result = HostedModelDiscovery(fake, keys).list("openai") as Outcome.Success
        assertEquals(1, result.value.models.size)
        assertFalse(result.value.complete)
    }

    @Test fun malformedCatalogIsNotReportedAsComplete() = runBlocking {
        val keys = InMemoryHostedKeyStore().apply { add("openai", "example-token-1234") }
        val fake = FakeTransport().apply { respond = { _, _ -> HostedHttpResponse(200, "{}") } }
        val result = HostedModelDiscovery(fake, keys).list("openai") as Outcome.Failure
        assertEquals("HOSTED_CATALOG", result.error.code)
    }
}
