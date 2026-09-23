package com.aicodemax.ai.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.runtime.ModelStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {
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
        out.write(le64("general.architecture".length.toLong()))
        out.write("general.architecture".toByteArray())
        out.write(le32(8))
        out.write(le64(arch.length.toLong()))
        out.write(arch.toByteArray())
        return out.toByteArray()
    }

    private val tiny = ModelProfile(
        id = "tiny", displayName = "Tiny", url = "http://x/t.gguf", fileName = "t.gguf",
        version = "1.0", licenseId = "Apache-2.0", quantization = "Q4_K_M",
        architecture = "qwen2", params = "0.0B", pack = ModelPack.DEFAULT,
    )

    private fun manager(vararg profiles: ModelProfile = arrayOf(tiny)): ModelManager {
        val root = tmp.root
        return ModelManager(
            File(root, "models/default").path,
            File(root, "models/optional").path,
            downloader = ModelStore.Downloader { _, dest, _ -> dest.writeBytes(ggufBytes()) },
            profiles = profiles.toList(),
        )
    }

    @Test
    fun missingThenInstallThenReady() {
        val mgr = manager()
        assertTrue(mgr.status(tiny) is ModelInstallStatus.Missing)
        val res = mgr.install(tiny)
        assertTrue(res.toString(), res is Outcome.Success)
        val st = mgr.status(tiny)
        assertTrue(st is ModelInstallStatus.Ready)
        assertEquals("qwen2", (st as ModelInstallStatus.Ready).info.architecture)
    }

    @Test
    fun archMismatchIsInvalid() {
        val mgr = manager()
        mgr.install(tiny)
        val other = tiny.copy(id = "other", fileName = "t.gguf", architecture = "llama")
        val mgr2 = manager(other)
        val st = mgr2.status(other)
        assertTrue(st is ModelInstallStatus.Invalid)
        assertTrue((st as ModelInstallStatus.Invalid).reason.contains("architecture"))
    }

    @Test
    fun defaultPackRequiresRedistributableLicense() {
        val bad = tiny.copy(licenseId = "NOPE-1.0")
        val res = manager(bad).install(bad)
        assertTrue(res is Outcome.Failure)
        assertTrue((res as Outcome.Failure).error.message.contains("license"))
    }

    @Test
    fun switchRequiresInstalledValidModel() {
        val mgr = manager()
        assertTrue(mgr.setActive("tiny") is Outcome.Failure)
        mgr.install(tiny)
        val ok = mgr.setActive("tiny")
        assertTrue(ok is Outcome.Success)
        assertEquals("tiny", mgr.activeId())
        assertEquals("tiny", mgr.active()?.id)
        assertTrue(mgr.setActive("ghost") is Outcome.Failure)
    }

    @Test
    fun deleteRemovesFile() {
        val mgr = manager()
        mgr.install(tiny)
        assertTrue(mgr.delete(tiny) is Outcome.Success)
        assertTrue(mgr.status(tiny) is ModelInstallStatus.Missing)
    }

    @Test
    fun catalogDefaultIsApache() {
        assertEquals(ModelPack.DEFAULT, ModelCatalog.DEFAULT_MODEL.pack)
        assertEquals("Apache-2.0", ModelCatalog.DEFAULT_MODEL.licenseId)
        ModelLicenses.requireRedistributable(ModelCatalog.DEFAULT_MODEL.licenseId)
    }
}
