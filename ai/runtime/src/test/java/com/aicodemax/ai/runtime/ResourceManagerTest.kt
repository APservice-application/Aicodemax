package com.aicodemax.ai.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.aicodemax.tools.runtime.ModelStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun readerOf(res: DeviceResources): ResourceReader = ResourceReader { res }

    private val flagship = DeviceResources(12000, 8000, 50000, 8, true)
    private val mid = DeviceResources(4000, 2000, 20000, 8, true)
    private val low = DeviceResources(2000, 700, 8000, 4, true)
    private val tiny = DeviceResources(1500, 250, 4000, 4, true)

    @Test
    fun recommendAdaptsToDeviceClass() {
        val rm = ResourceManager(readerOf(flagship))
        assertEquals(4096, rm.recommend(wantCtx = 4096).ctxSize)
        assertEquals(7, rm.recommend().threads)
        val rmMid = ResourceManager(readerOf(mid))
        assertEquals(2048, rmMid.recommend().ctxSize)
        val rmLow = ResourceManager(readerOf(low))
        assertEquals(1024, rmLow.recommend().ctxSize)
        assertEquals(4, rmLow.recommend().threads)
    }

    @Test
    fun canLoadOkOnFlagship() {
        val rm = ResourceManager(readerOf(flagship))
        val verdict = rm.canLoad(397_000_000L, LoadOpts(ctxSize = 2048))
        assertTrue(verdict is LoadVerdict.Ok)
    }

    @Test
    fun canLoadDegradesOnLowRam() {
        val rm = ResourceManager(readerOf(low))
        // 510MB: need(2048)=712MB > 700 avail, need(1024)=672MB fits → Degrade.
        val verdict = rm.canLoad(510_000_000L, LoadOpts(ctxSize = 2048))
        assertTrue(verdict.toString(), verdict is LoadVerdict.Degrade)
        assertEquals(1024, (verdict as LoadVerdict.Degrade).opts.ctxSize)
    }

    @Test
    fun canLoadRefusesHopeless() {
        val rm = ResourceManager(readerOf(tiny))
        val verdict = rm.canLoad(4_700_000_000L, LoadOpts(ctxSize = 2048))
        assertTrue(verdict is LoadVerdict.Refuse)
        assertTrue((verdict as LoadVerdict.Refuse).reason.contains("RAM"))
    }

    @Test
    fun canLoadRefuses32Bit() {
        val rm = ResourceManager(readerOf(flagship.copy(arch64 = false)))
        val verdict = rm.canLoad(100L, LoadOpts())
        assertTrue(verdict is LoadVerdict.Refuse)
        assertTrue((verdict as LoadVerdict.Refuse).reason.contains("64-bit"))
    }

    @Test
    fun pressureLevels() {
        val rm = ResourceManager(readerOf(flagship))
        assertEquals(Pressure.NORMAL, rm.pressure())
        assertEquals(Pressure.WARNING, rm.pressure(low))
        assertEquals(Pressure.CRITICAL, rm.pressure(tiny))
        assertEquals(Pressure.CRITICAL, rm.pressure(flagship.copy(availRamMb = 200)))
    }

    @Test
    fun jvmReaderReturnsSaneValues() {
        val res = JvmResourceReader(tmp.root.path).read()
        assertTrue(res.totalRamMb > 0)
        assertTrue(res.cpuCores >= 1)
        assertTrue(res.storageFreeMb >= 0)
    }

    // -- AiRuntimeManager integration --------------------------------------

    private fun ggufBytes(): ByteArray {
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
        out.write(le64(5))
        out.write("qwen2".toByteArray())
        return out.toByteArray()
    }

    /** ModelManager reporting a fake huge-but-valid model (no giant files). */
    private fun hugeManager(profile: ModelProfile, fakeBytes: Long): ModelManager {
        val real = File(tmp.root, "models/default/${profile.fileName}").apply {
            parentFile?.mkdirs()
            writeBytes(ggufBytes())
        }
        val info = Gguf.Info(3, "qwen2", 2, profile.id, 0)
        return object : ModelManager(
            File(tmp.root, "models/default").path,
            File(tmp.root, "models/optional").path,
            profiles = listOf(profile),
        ) {
            override fun status(p: ModelProfile): ModelInstallStatus =
                ModelInstallStatus.Ready(real.path, fakeBytes, info)
        }
    }

    @Test
    fun managerRefusesWhenRamHopeless(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val profile = ModelProfile("big", "big", "http://x/b.gguf", "b.gguf", "1.0", "Apache-2.0", "Q4_K_M", "qwen2", "7B", pack = ModelPack.DEFAULT)
            val models = hugeManager(profile, 4_700_000_000L)
            val mgr = AiRuntimeManager(
                scope, FakeAiRuntime(), models, File(tmp.root, "runtime").path,
                resources = ResourceManager(readerOf(tiny)),
            )
            mgr.initialize().join()
            assertEquals(AiRuntimeState.OFFLINE, mgr.state.value)
            assertTrue(mgr.error.value!!.contains("RAM"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun managerDegradesCtxOnLowRam(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val profile = ModelProfile("m", "m", "http://x/m.gguf", "m.gguf", "1.0", "Apache-2.0", "Q4_K_M", "qwen2", "1.5B", pack = ModelPack.DEFAULT)
            val models = hugeManager(profile, 1_000_000_000L)
            val rt = FakeAiRuntime()
            // 1GB model: need(2048)=1320MB, need(1024)=1280MB; avail 1300 → Degrade.
            val tight = DeviceResources(4000, 1300, 20000, 8, true)
            val mgr = AiRuntimeManager(
                scope, rt, models, File(tmp.root, "runtime").path,
                loadOpts = LoadOpts(ctxSize = 2048),
                resources = ResourceManager(readerOf(tight)),
            )
            mgr.initialize().join()
            assertEquals(AiRuntimeState.READY, mgr.state.value)
            assertEquals(1024, rt.getModelInfo()!!.ctxSize)
        } finally {
            scope.cancel()
        }
    }
}
