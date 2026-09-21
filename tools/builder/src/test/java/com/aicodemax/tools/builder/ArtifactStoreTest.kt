package com.aicodemax.tools.builder

import com.aicodemax.core.common.Outcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun registerVerifyDelete() {
        val store = ArtifactStore(tmp.newFolder("store"))
        val apk = File(tmp.root, "app.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val artifact = (store.register("p1", ArtifactKind.APK, apk) as Outcome.Success<Artifact>).value
        assertEquals(3L, artifact.sizeBytes)
        assertEquals(64, artifact.sha256.length)
        assertTrue(store.verify(artifact.id) is Outcome.Success)
        assertEquals(1, store.list("p1").size)
        assertEquals(0, store.list("p2").size)

        // Tamper -> verify fails.
        apk.writeBytes(byteArrayOf(9, 9, 9))
        assertTrue(store.verify(artifact.id) is Outcome.Failure)

        assertTrue(store.delete(artifact.id) is Outcome.Success)
        assertTrue(store.get(artifact.id) is Outcome.Failure)
    }

    @Test
    fun missingFileFailsHonestly() {
        val store = ArtifactStore(tmp.root)
        assertTrue(store.register("p1", ArtifactKind.APK, File(tmp.root, "ghost.apk")) is Outcome.Failure)
        assertTrue(store.register("  ", ArtifactKind.LOG, tmp.root) is Outcome.Failure)
        assertTrue(store.verify("ghost") is Outcome.Failure)
    }
}
