package com.aicodemax.tools.media

import com.aicodemax.core.common.Outcome
import com.aicodemax.data.media.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGenTest {
    private class FakeCloud : CloudGenProvider {
        override val id: String = "fake"
        override val label: String = "fake-cloud"
        override fun supports(kind: String): Boolean = kind == GenKinds.TEXT2MUSIC
        override suspend fun generate(request: GenRequest, dstDir: String): Outcome<GenResult> =
            Outcome.Success(GenResult("mem://song.mp3", MediaKind.AUDIO, "fake"))
    }

    @Test
    fun emptyRegistryIsHonest() = runBlocking {
        val port = InMemoryGenPort()
        assertTrue(port.cloudStatus().contains("ยังไม่มี"))
        val cap = port.list().find { it.kind == GenKinds.TEXT2VIDEO }!!
        assertTrue(!cap.available && cap.note.contains("§29"))
        val out = port.generate(GenRequest(GenKinds.TEXT2VIDEO, "cat"))
        assertTrue(out is Outcome.Failure)
        assertEquals("GEN_UNAVAILABLE", (out as Outcome.Failure).error.code)
    }

    @Test
    fun registeredProviderTakesOver() = runBlocking {
        val registry = CloudGenRegistry()
        registry.register(FakeCloud())
        assertTrue(registry.statusLine().contains("fake-cloud"))
        val port = InMemoryGenPort(registry)
        val cap = port.list().find { it.kind == GenKinds.TEXT2MUSIC }!!
        assertTrue(cap.available && cap.note.contains("fake-cloud"))
        val out = port.generate(GenRequest(GenKinds.TEXT2MUSIC, "lofi")) as Outcome.Success
        assertEquals("mem://song.mp3", out.value.path)
        registry.unregister("fake")
        assertTrue(registry.providersFor(GenKinds.TEXT2MUSIC).isEmpty())
    }

    @Test
    fun stubFailsWithSetupHint() = runBlocking {
        val stub = StubCloudGenProvider(setOf(GenKinds.TEXT2SFX))
        assertTrue(stub.supports(GenKinds.TEXT2SFX))
        val out = stub.generate(GenRequest(GenKinds.TEXT2SFX, "boom"), "mem://x")
        assertTrue(out is Outcome.Failure)
        assertEquals("CLOUD_UNCONFIGURED", (out as Outcome.Failure).error.code)
    }
}
