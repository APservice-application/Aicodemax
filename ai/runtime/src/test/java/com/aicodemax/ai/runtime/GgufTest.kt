package com.aicodemax.ai.runtime

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le64(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    private fun kvString(out: ByteArrayOutputStream, key: String, value: String) {
        out.write(le64(key.length.toLong()))
        out.write(key.toByteArray())
        out.write(le32(8)) // STRING
        out.write(le64(value.length.toLong()))
        out.write(value.toByteArray())
    }

    private fun kvUint32(out: ByteArrayOutputStream, key: String, value: Int) {
        out.write(le64(key.length.toLong()))
        out.write(key.toByteArray())
        out.write(le32(4)) // UINT32
        out.write(le32(value))
    }

    private fun tinyGguf(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        out.write(le32(3)) // version
        out.write(le64(0)) // n_tensors
        out.write(le64(3)) // n_kv
        kvString(out, "general.architecture", "qwen2")
        kvUint32(out, "general.quantization_version", 2)
        kvString(out, "general.name", "tiny-test")
        return out.toByteArray()
    }

    @Test
    fun readsArchitectureAndQuant() {
        val file = tmp.newFile("tiny.gguf").apply { writeBytes(tinyGguf()) }
        val info = Gguf.read(file)
        assertEquals(3L, info.version)
        assertEquals("qwen2", info.architecture)
        assertEquals(2L, info.quantizationVersion)
        assertEquals("tiny-test", info.name)
    }

    @Test
    fun rejectsBadMagic() {
        val file = tmp.newFile("bad.gguf").apply { writeBytes("NOPE".toByteArray() + ByteArray(64)) }
        try {
            Gguf.read(file)
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GGUF"))
        }
    }

    @Test
    fun rejectsTruncatedKv() {
        val full = tinyGguf()
        val file = tmp.newFile("cut.gguf").apply { writeBytes(full.copyOf(20)) }
        try {
            Gguf.read(file)
            assertTrue(false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }
}
