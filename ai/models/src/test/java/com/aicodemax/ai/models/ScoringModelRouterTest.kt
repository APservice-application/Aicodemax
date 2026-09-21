package com.aicodemax.ai.models

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.resources.ResourceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringModelRouterTest {
    private fun snapshot(ramAvail: Long, network: Boolean = true) = ResourceSnapshot(
        ramAvailableBytes = ramAvail,
        ramTotalBytes = 8L * 1024 * 1024 * 1024,
        storageAvailableBytes = 10L * 1024 * 1024 * 1024,
        storageTotalBytes = 64L * 1024 * 1024 * 1024,
        batteryPercent = 80,
        batteryCharging = false,
        networkAvailable = network,
        networkUnmetered = true,
    )

    private fun registry(): ModelRegistry {
        val registry = InMemoryModelRegistry()
        registry.register(
            ModelDescriptor("boot", "Bootstrap", ModelKind.LOCAL_BOOTSTRAP, sizeBytes = 50L * 1024 * 1024,
                status = ModelStatus.READY, capabilities = listOf("chat")),
        )
        registry.register(
            ModelDescriptor("full", "Full", ModelKind.LOCAL_FULL, sizeBytes = 2L * 1024 * 1024 * 1024,
                status = ModelStatus.READY, capabilities = listOf("chat", "code")),
        )
        registry.register(
            ModelDescriptor("cloud", "Cloud", ModelKind.EXTERNAL,
                status = ModelStatus.AVAILABLE, capabilities = listOf("chat", "code")),
        )
        return registry
    }

    @Test
    fun prefersLoadedLocalWithRamFit() {
        val registry = registry()
        val router = ScoringModelRouter(registry, resources = { snapshot(6L * 1024 * 1024 * 1024) })
        // full(2GB) fits in 3GB half-RAM; boot is smaller but bootstrap-penalized.
        val picked = (router.pick(ModelRequirement("code")) as Outcome.Success<ModelDescriptor>).value
        assertEquals("full", picked.id)
    }

    @Test
    fun excludesTooBigAndUnhealthy() {
        val registry = registry()
        val router = ScoringModelRouter(
            registry,
            resources = { snapshot(1L * 1024 * 1024 * 1024) },
            health = { it != "boot" },
        )
        // full needs 2GB (half of 1GB = 512MB: excluded); boot unhealthy -> cloud (external).
        val picked = (router.pick(ModelRequirement("chat")) as Outcome.Success<ModelDescriptor>).value
        assertEquals("cloud", picked.id)
    }

    @Test
    fun offlineExcludesExternal() {
        val registry = registry()
        registry.update(registry.get("boot")!!.copy(status = ModelStatus.ERROR))
        registry.update(registry.get("full")!!.copy(status = ModelStatus.ERROR))
        val router = ScoringModelRouter(registry, resources = { snapshot(8L * 1024 * 1024 * 1024, network = false) })
        val result = router.pick(ModelRequirement("chat"))
        assertTrue(result is Outcome.Failure)
        assertEquals("MODEL_NONE_READY", (result as Outcome.Failure).error.code)
    }
}
