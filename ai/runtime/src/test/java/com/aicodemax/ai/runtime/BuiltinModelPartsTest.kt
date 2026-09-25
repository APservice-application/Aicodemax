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
