package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackModelRouterTest {
    private fun descriptor(id: String, kind: ModelKind, status: ModelStatus) =
        ModelDescriptor(id = id, name = id, kind = kind, status = status)

    @Test
    fun prefersLocalWhenAsked() {
        val registry: ModelRegistry = InMemoryModelRegistry()
        registry.register(descriptor("ext", ModelKind.EXTERNAL, ModelStatus.READY))
        registry.register(descriptor("local", ModelKind.LOCAL_FULL, ModelStatus.READY))
        val router = FallbackModelRouter(registry)

        val picked = (router.pick(ModelRequirement(preferLocal = true)) as Outcome.Success<ModelDescriptor>).value
        assertEquals("local", picked.id)
    }

    @Test
    fun respectsPreferenceOrder() {
        val registry: ModelRegistry = InMemoryModelRegistry()
        registry.register(descriptor("b", ModelKind.LOCAL_FULL, ModelStatus.READY))
        registry.register(descriptor("a", ModelKind.LOCAL_FULL, ModelStatus.READY))
        val router = FallbackModelRouter(registry, preferenceOrder = listOf("a", "b"))

        val picked = (router.pick() as Outcome.Success<ModelDescriptor>).value
        assertEquals("a", picked.id)
    }

    @Test
    fun failsHonestlyWhenNothingReady() {
        val registry: ModelRegistry = InMemoryModelRegistry()
        val router = FallbackModelRouter(registry)
        val empty = router.pick()
        assertTrue(empty is Outcome.Failure)
        assertEquals("MODEL_NONE_READY", (empty as Outcome.Failure).error.code)

        registry.register(descriptor("broken", ModelKind.LOCAL_FULL, ModelStatus.ERROR))
        assertTrue(router.pick() is Outcome.Failure)
    }
}
