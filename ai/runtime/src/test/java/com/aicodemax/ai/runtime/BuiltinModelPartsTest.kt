package com.aicodemax.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CP-147: parts/manifest protocol between Gradle and the provisioner. */
class BuiltinModelPartsTest {
    @Test
    fun parsesManifest() {
        val m = BuiltinModelParts.parseManifest(
            "# written by fetchBuiltinModel - do not edit\nparts=5\ntotal=2497280256\n",
        )
        assertEquals(BuiltinModelParts.Manifest(5, 2497280256L), m)
    }

    @Test
    fun recognizesPinnedSmallerModelAndRejectsBadHashes() {
        val hash = "ba491cf470c3cadc624e4c8d6c9a27c998809e8ba8eb938d1689ae87e024b6b7"
        val manifest = BuiltinModelParts.parseManifest(
            "parts=3\ntotal=1107409376\nmodel=qwen3-1.7b-q4_k_m\nsha256=$hash\n",
        )
        assertEquals(BuiltinModelParts.Manifest(3, 1_107_409_376L, "qwen3-1.7b-q4_k_m", hash), manifest)
        assertNull(BuiltinModelParts.parseManifest("parts=3\ntotal=1107409376\nsha256=bad\n"))
        assertNull(BuiltinModelParts.parseManifest("parts=3\ntotal=1107409376\nmodel=../bad\n"))
    }

    @Test
    fun rejectsBadManifest() {
        assertNull(BuiltinModelParts.parseManifest(""))
        assertNull(BuiltinModelParts.parseManifest("parts=0\ntotal=10\n"))
        assertNull(BuiltinModelParts.parseManifest("parts=abc\ntotal=10\n"))
        assertNull(BuiltinModelParts.parseManifest("parts=5\n"))
    }

    @Test
    fun ordersPartsAndDetectsMissing() {
        val m = BuiltinModelParts.Manifest(3, 100L)
        val full = listOf("builtin-model-part00.gguf", "builtin-model-part01.gguf", "builtin-model-part02.gguf", "other.txt")
        assertEquals(full.take(3), BuiltinModelParts.orderedParts(m, full))
        assertNull(BuiltinModelParts.orderedParts(m, full.take(2)))
    }

    @Test
    fun partNameFormat() {
        assertEquals("builtin-model-part00.gguf", BuiltinModelParts.partName(0))
        assertEquals("builtin-model-part04.gguf", BuiltinModelParts.partName(4))
    }
}
