package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.runtime.ModelStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiRuntimeManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ggufBytes(arch: String = "qwen2"): ByteArray {
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        val out = java.io.ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        out.write(le32(3))
        out.write(le64(0))
        out.write(le64(1))
        val k = "general.architecture"
        out.write(le64(k.length.toLong()))
        out.write(k.toByteArray())
        out.write(le32(8))
        out.write(le64(arch.length.toLong()))
        out.write(arch.toByteArray())
        return out.toByteArray()
    }

    private fun profile(id: String, pack: ModelPack = ModelPack.DEFAULT): ModelProfile =
        ModelProfile(id, id, "http://x/$id.gguf", "$id.gguf", "1.0", "Apache-2.0", "Q4_K_M", "qwen2", "0B", pack = pack)

    private fun installedManager(vararg profiles: ModelProfile): ModelManager {
        val mgr = ModelManager(
            File(tmp.root, "models/default").path,
            File(tmp.root, "models/optional").path,
            downloader = ModelStore.Downloader { _, dest, _ -> dest.writeBytes(ggufBytes()) },
            profiles = profiles.toList(),
        )
        profiles.forEach { mgr.install(it) }
        return mgr
    }

    private fun managerWith(scope: CoroutineScope, models: ModelManager, rt: AiRuntime = FakeAiRuntime(script = { "ok:$it".take(60) })): AiRuntimeManager =
        AiRuntimeManager(scope, rt, models, File(tmp.root, "runtime").path)

    @Test
    fun initializeLoadsActiveModel(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val mgr = managerWith(scope, installedManager(profile("a")))
            assertEquals(AiRuntimeState.UNINITIALIZED, mgr.state.value)
            mgr.initialize().join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            assertTrue(!mgr.crashedLastRun)
            val reply = mgr.chat(listOf(ChatMessage(ChatRole.USER, "hi"))) as Outcome.Success
            assertTrue(reply.value.text.startsWith("ok:"))
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            mgr.shutdown().join()
            assertEquals(AiRuntimeState.UNINITIALIZED, mgr.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingModelGoesOffline(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val models = ModelManager(
                File(tmp.root, "models/default").path,
                File(tmp.root, "models/optional").path,
                profiles = listOf(profile("ghost")),
            )
            val mgr = managerWith(scope, models)
            mgr.initialize().join()
            assertEquals(AiRuntimeState.OFFLINE, mgr.state.value)
            val res = mgr.chat(listOf(ChatMessage(ChatRole.USER, "hi")))
            assertTrue(res is Outcome.Failure)
            assertTrue((res as Outcome.Failure).error.message.contains("OFFLINE"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun switchModelReloads(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val mgr = managerWith(scope, installedManager(profile("a"), profile("b")))
            mgr.initialize().join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            mgr.switchModel("b").join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            mgr.switchModel("ghost").join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            assertTrue(mgr.error.value!!.contains("ghost"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun generationFailureGoesErrorThenRecovers(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val bad = FakeAiRuntime(script = { throw IllegalStateException("boom") })
            val mgr = managerWith(scope, installedManager(profile("a")), bad)
            mgr.initialize().join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            val res = mgr.chat(listOf(ChatMessage(ChatRole.USER, "hi")))
            assertTrue(res is Outcome.Failure)
            assertEquals(AiRuntimeState.ERROR, mgr.state.value)
            mgr.recover().join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun installActiveModelDownloadsThenLoads(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            var progress = 0L
            val profile = profile("fresh")
            val models = ModelManager(
                File(tmp.root, "models/default").path,
                File(tmp.root, "models/optional").path,
                downloader = ModelStore.Downloader { _, dest, onProgress ->
                    dest.writeBytes(ggufBytes())
                    onProgress(69, 69)
                },
                profiles = listOf(profile),
            )
            val mgr = AiRuntimeManager(scope, FakeAiRuntime(), models, File(tmp.root, "runtime").path)
            mgr.installActiveModel { dn, _ -> progress = dn }.join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            assertEquals(69L, progress)
            assertEquals("fresh", models.activeId())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sessionMarkerDetectsCrash(): Unit = runBlocking {
        val dir = File(tmp.root, "runtime").path
        assertTrue(!SessionMarker.begin(dir))
        // No clean shutdown → second begin reports the crash.
        assertTrue(SessionMarker.begin(dir))
        SessionMarker.endClean(dir)
        assertTrue(!SessionMarker.begin(dir))
    }

    @Test
    fun crashedFlagSurfacesOnManager(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val dir = File(tmp.root, "runtime").path
            SessionMarker.begin(dir) // stale marker, no shutdown
            val mgr = managerWith(scope, installedManager(profile("a")))
            mgr.initialize().join()
            assertTrue(mgr.crashedLastRun)
            assertEquals(AiRuntimeState.READY, mgr.state.value)
        } finally {
            scope.cancel()
        }
    }
}
