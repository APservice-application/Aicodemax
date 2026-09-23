package com.aicodemax.ai.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProvidersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun loadedRuntime(): FakeAiRuntime {
        val rt = FakeAiRuntime(script = { p -> "echo:${p.takeLast(20)}" })
        val model = tmp.newFile("t.gguf").apply { writeBytes(ByteArray(8)) }
        rt.loadModel(model.path)
        return rt
    }

    private fun userReq(text: String = "สวัสดี"): ChatRequest =
        ChatRequest(listOf(ChatMessage(ChatRole.USER, text)))

    @Test
    fun templateFramesMultiTurn() {
        val prompt = ChatTemplate.qwen25(
            listOf(
                ChatMessage(ChatRole.USER, "hi"),
                ChatMessage(ChatRole.ASSISTANT, "hello"),
                ChatMessage(ChatRole.USER, "bye"),
            ),
        )
        assertTrue(prompt.startsWith("<|im_start|>system\n"))
        assertTrue(prompt.contains("<|im_start|>assistant\nhello\n<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertEquals(2, prompt.split("<|im_start|>user").size - 1)
    }

    @Test
    fun localProviderChatsThroughRuntime() {
        val provider = LocalModelProvider(loadedRuntime())
        assertTrue(provider.status() is ProviderStatus.Available)
        val pieces = mutableListOf<String>()
        val reply = provider.chat(userReq(), TokenSink { pieces.add(it) }) as Outcome.Success
        assertTrue(reply.value.text.startsWith("echo:"))
        assertEquals("local", reply.value.via)
        assertTrue(pieces.isNotEmpty())
    }

    @Test
    fun localProviderHonestWithoutModel() {
        val provider = LocalModelProvider(FakeAiRuntime())
        val st = provider.status()
        assertTrue(st is ProviderStatus.Unavailable)
        assertTrue(provider.chat(userReq()) is Outcome.Failure)
    }

    @Test
    fun localProviderRejectsEmptyConversation() {
        val provider = LocalModelProvider(loadedRuntime())
        val res = provider.chat(ChatRequest(listOf(ChatMessage(ChatRole.SYSTEM, "x"))))
        assertTrue(res is Outcome.Failure)
    }

    @Test
    fun cloudStubIsHonest() {
        val provider = CloudModelProvider()
        assertTrue(provider.status() is ProviderStatus.NeedsSetup)
        val res = provider.chat(userReq())
        assertTrue(res is Outcome.Failure)
        assertTrue((res as Outcome.Failure).error.message.contains("cloud provider"))
    }

    @Test
    fun cloudSenderRoundTrips() {
        val provider = CloudModelProvider(sender = CloudSender { req, onToken ->
            onToken.onToken("C:")
            onToken.onToken(req.messages.last().content)
            Outcome.Success("C:" + req.messages.last().content)
        })
        assertTrue(provider.status() is ProviderStatus.Available)
        val reply = provider.chat(userReq("hi")) as Outcome.Success
        assertEquals("C:hi", reply.value.text)
        assertEquals("cloud", reply.value.via)
    }

    @Test
    fun remoteStubIsHonest() {
        val provider = RemoteModelProvider()
        assertTrue(provider.status() is ProviderStatus.NeedsSetup)
        assertTrue(provider.chat(userReq()) is Outcome.Failure)
    }

    @Test
    fun hybridPrefersLocal() {
        val hybrid = HybridModelProvider(
            LocalModelProvider(loadedRuntime()),
            CloudModelProvider(sender = CloudSender { _, _ -> Outcome.Success("from-cloud") }),
        )
        val reply = hybrid.chat(userReq()) as Outcome.Success
        assertEquals("local", reply.value.via)
    }

    @Test
    fun hybridFallsBackToCloud() {
        val hybrid = HybridModelProvider(
            LocalModelProvider(FakeAiRuntime()),
            CloudModelProvider(sender = CloudSender { _, _ -> Outcome.Success("from-cloud") }),
        )
        assertTrue(hybrid.status() is ProviderStatus.Available)
        val reply = hybrid.chat(userReq()) as Outcome.Success
        assertEquals("from-cloud", reply.value.text)
        assertEquals("cloud", reply.value.via)
    }

    @Test
    fun hybridLocalOnlyNeverTouchesCloud() {
        var cloudHit = false
        val hybrid = HybridModelProvider(
            LocalModelProvider(FakeAiRuntime()),
            CloudModelProvider(sender = CloudSender { _, _ ->
                cloudHit = true
                Outcome.Success("x")
            }),
            strategy = HybridStrategy.LOCAL_ONLY,
        )
        assertTrue(hybrid.chat(userReq()) is Outcome.Failure)
        assertTrue(!cloudHit)
    }

    @Test
    fun hybridFailsHonestlyWhenNothingReady() {
        val hybrid = HybridModelProvider(LocalModelProvider(FakeAiRuntime()), CloudModelProvider())
        assertTrue(hybrid.status() is ProviderStatus.Unavailable)
        assertTrue(hybrid.chat(userReq()) is Outcome.Failure)
    }

    @Test
    fun registryTracksDefault() {
        val registry = ProviderRegistry()
            .register(LocalModelProvider(FakeAiRuntime()))
            .register(CloudModelProvider(), makeDefault = true)
        assertEquals("cloud", registry.default()?.providerId)
        assertTrue(registry.setDefault("local"))
        assertEquals("local", registry.default()?.providerId)
        assertTrue(!registry.setDefault("nope"))
        assertEquals(2, registry.list().size)
        assertEquals(2, registry.statusLines().size)
        assertTrue(registry.statusLines()[0].startsWith("local "))
    }
}
